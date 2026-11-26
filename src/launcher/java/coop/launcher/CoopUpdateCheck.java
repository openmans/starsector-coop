package coop.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Asks GitHub whether there is a newer release than the build that is running.
 *
 * <p>This exists for one reason: a co-op session needs both players on the same build, and the
 * failure mode when they are not is a desync that looks like a bug in the mod. The check is
 * advisory in the strongest sense - every failure path (offline, no release published yet, rate
 * limited, a body that does not parse) lands on a neutral row that says so and never touches the
 * Launch button. A launcher that refuses to start the game because api.github.com is down would be
 * worse than no check at all.
 *
 * <p>Check order:
 * <ol>
 *   <li>{@link #RELEASES_URL} ({@code /releases/latest}) — stable GitHub release.</li>
 *   <li>{@link #RELEASES_LIST_URL} ({@code /releases?per_page=5}) — fallback on HTTP 404 so
 *       pre-releases are still discovered before a stable release exists.</li>
 *   <li>{@link #MASTER_VERSION_URL} ({@code raw.githubusercontent.com/.../coop.version}) —
 *       fallback on HTTP 404 or GitHub API rate limits (403/429, common behind CGNAT), since raw
 *       file fetches do not consume the 60 req/hr unauthenticated REST API quota.</li>
 * </ol>
 *
 * <p>Nothing is sent: a bare GET with no body. The 5 s timeouts keep a startup from hanging on a
 * captive portal that accepts the connection and then says nothing.
 */
public final class CoopUpdateCheck {

    /** The release the launcher is built from. */
    public static final String RELEASES_URL =
            "https://api.github.com/repos/openmans/starsector-coop/releases/latest";

    /** Fallback when {@link #RELEASES_URL} returns 404 (for example, pre-release only). */
    public static final String RELEASES_LIST_URL =
            "https://api.github.com/repos/openmans/starsector-coop/releases?per_page=5";

    /** Fallback when no release is published yet or {@code api.github.com} rate-limits the IP. */
    public static final String MASTER_VERSION_URL =
            "https://raw.githubusercontent.com/openmans/starsector-coop/main/coop.version";

    /** Human-facing GitHub releases page used when a version source omits a download URL. */
    public static final String RELEASES_PAGE_URL =
            "https://github.com/openmans/starsector-coop/releases";

    /** Optional Wingman Project GitHub Releases endpoints (for manual co-op battle piloting). */
    public static final String WINGMAN_RELEASES_URL =
            "https://api.github.com/repos/NEOysod/TheWingmanProject/releases/latest";
    public static final String WINGMAN_RELEASES_LIST_URL =
            "https://api.github.com/repos/NEOysod/TheWingmanProject/releases?per_page=5";
    public static final String WINGMAN_RELEASES_PAGE_URL =
            "https://github.com/NEOysod/TheWingmanProject/releases";

    /** Optional HTTPS endpoint override for custom forks or local verification. */
    public static final String UPDATE_URL_PROPERTY = "coop.updateUrl";

    private static final int TIMEOUT_MILLIS = 5000;

    /** GitHub's release JSON is small; anything larger than this is not the answer we asked for. */
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    public enum Kind {
        /** GitHub has a release newer than this build. */
        UPDATE_AVAILABLE,
        /** This build is the latest release, or newer than it. */
        UP_TO_DATE,
        /** The question could not be answered. Never a complaint about the install. */
        UNAVAILABLE
    }

    /**
     * @param kind    what was concluded
     * @param version the release version for the first two kinds, empty otherwise
     * @param url     the release page, empty unless there is one to open
     * @param reason  a few words for the unavailable row, empty otherwise
     */
    public record Outcome(Kind kind, String version, String url, String reason) {
        public Outcome {
            version = version == null ? "" : version;
            url = url == null ? "" : url;
            reason = reason == null ? "" : reason;
        }

        static Outcome unavailable(String reason) {
            return new Outcome(Kind.UNAVAILABLE, "", "", reason);
        }
    }

    /** A downloadable release archive discovered from GitHub Releases. */
    public record ReleaseAsset(String version, String pageUrl, String zipUrl, String assetName) {
        public ReleaseAsset {
            version = version == null ? "" : version;
            pageUrl = pageUrl == null ? "" : pageUrl;
            zipUrl = zipUrl == null ? "" : zipUrl;
            assetName = assetName == null ? "" : assetName;
        }
    }

    /** One HTTP answer, separated out so tests never touch the network. */
    record Response(int status, String body) {
    }

    /** What {@link #fetch} does, as an interface a test can stand in for. */
    interface Fetcher {
        Response get(String url, String userAgent) throws IOException;
    }

    private CoopUpdateCheck() {
    }

    /** Runs the real check. Blocking; call it off the event dispatch thread. */
    public static Outcome check(String currentVersion) {
        String override = System.getProperty(UPDATE_URL_PROPERTY, "").trim();
        if (!override.isEmpty()) {
            return check(currentVersion, override, CoopUpdateCheck::fetch);
        }
        return checkWithFallback(currentVersion, CoopUpdateCheck::fetch);
    }

    /**
     * Resolves the newest downloadable {@code coop-*.zip} release archive from GitHub
     * ({@code releases/latest} with fallback to {@code releases?per_page=5} for pre-releases).
     *
     * @throws IOException if GitHub cannot be reached or no release zip asset exists
     */
    public static ReleaseAsset resolveLatestAsset(String currentVersion) throws IOException {
        return resolveLatestAsset(currentVersion, CoopUpdateCheck::fetch);
    }

    static ReleaseAsset resolveLatestAsset(String currentVersion, Fetcher fetcher) throws IOException {
        String agent = userAgent(currentVersion);
        Response latest = fetcher.get(RELEASES_URL, agent);
        if (latest != null && latest.status() == 200) {
            ReleaseAsset asset = parseReleaseAsset(latest.body());
            if (asset != null) {
                return asset;
            }
        }
        if (latest != null && (latest.status() == 403 || latest.status() == 429)) {
            throw new IOException("GitHub API rate limit (HTTP " + latest.status()
                    + "). Try again later or open " + RELEASES_PAGE_URL);
        }
        Response list = fetcher.get(RELEASES_LIST_URL, agent);
        if (list != null && list.status() == 200) {
            ReleaseAsset asset = parseReleaseListAsset(list.body());
            if (asset != null) {
                return asset;
            }
        }
        int status = latest == null ? 0 : latest.status();
        throw new IOException("No downloadable coop-*.zip release found on GitHub"
                + (status > 0 ? " (HTTP " + status + ")" : ""));
    }

    /**
     * Resolves the newest downloadable {@code Wingman-*.zip} archive (excluding the separate
     * {@code WingmanPvP-*.zip} addon) from {@code NEOysod/TheWingmanProject}.
     */
    public static ReleaseAsset resolveLatestWingmanAsset(String currentVersion) throws IOException {
        return resolveLatestWingmanAsset(currentVersion, CoopUpdateCheck::fetch);
    }

    static ReleaseAsset resolveLatestWingmanAsset(String currentVersion, Fetcher fetcher) throws IOException {
        String agent = userAgent(currentVersion);
        Response latest = fetcher.get(WINGMAN_RELEASES_URL, agent);
        if (latest != null && latest.status() == 200) {
            ReleaseAsset asset = parseWingmanReleaseAsset(latest.body());
            if (asset != null) {
                return asset;
            }
        }
        if (latest != null && (latest.status() == 403 || latest.status() == 429)) {
            throw new IOException("GitHub API rate limit while fetching Wingman (HTTP "
                    + latest.status() + "). Open " + WINGMAN_RELEASES_PAGE_URL);
        }
        Response list = fetcher.get(WINGMAN_RELEASES_LIST_URL, agent);
        if (list != null && list.status() == 200) {
            try {
                JSONArray array = new JSONArray(list.body());
                for (int i = 0; i < array.length(); i++) {
                    JSONObject release = array.optJSONObject(i);
                    if (release == null || release.optBoolean("draft", false)) {
                        continue;
                    }
                    ReleaseAsset asset = extractWingmanAsset(release);
                    if (asset != null) {
                        return asset;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        throw new IOException("No downloadable Wingman-*.zip release found on GitHub ("
                + WINGMAN_RELEASES_PAGE_URL + ")");
    }

    static ReleaseAsset parseWingmanReleaseAsset(String jsonBody) {
        try {
            return extractWingmanAsset(new JSONObject(jsonBody));
        } catch (Exception ex) {
            return null;
        }
    }

    private static ReleaseAsset extractWingmanAsset(JSONObject release) {
        if (release == null) {
            return null;
        }
        String version = normalise(release.optString("tag_name", ""));
        if (version.isEmpty()) {
            return null;
        }
        String pageUrl = release.optString("html_url", "");
        if (pageUrl.isEmpty()) {
            pageUrl = WINGMAN_RELEASES_PAGE_URL;
        }
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) {
            return null;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject item = assets.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String name = item.optString("name", "").trim();
            String lower = name.toLowerCase(Locale.ROOT);
            String downloadUrl = item.optString("browser_download_url", "").trim();
            if (downloadUrl.isEmpty() || !lower.endsWith(".zip")) {
                continue;
            }
            if (lower.startsWith("wingman-") && !lower.startsWith("wingmanpvp")) {
                return new ReleaseAsset(version, pageUrl, downloadUrl, name);
            }
        }
        return null;
    }

    static ReleaseAsset parseReleaseAsset(String jsonBody) {
        try {
            return extractAsset(new JSONObject(jsonBody));
        } catch (Exception ex) {
            return null;
        }
    }

    static ReleaseAsset parseReleaseListAsset(String jsonBody) {
        try {
            JSONArray array = new JSONArray(jsonBody);
            for (int i = 0; i < array.length(); i++) {
                JSONObject release = array.optJSONObject(i);
                if (release == null || release.optBoolean("draft", false)) {
                    continue;
                }
                ReleaseAsset asset = extractAsset(release);
                if (asset != null) {
                    return asset;
                }
            }
        } catch (Exception ex) {
            return null;
        }
        return null;
    }

    private static ReleaseAsset extractAsset(JSONObject release) {
        if (release == null) {
            return null;
        }
        String version = normalise(release.optString("tag_name", ""));
        if (version.isEmpty()) {
            return null;
        }
        String pageUrl = release.optString("html_url", "");
        if (pageUrl.isEmpty()) {
            pageUrl = RELEASES_PAGE_URL;
        }
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) {
            return null;
        }
        ReleaseAsset fallbackZip = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject item = assets.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String name = item.optString("name", "").trim();
            String downloadUrl = item.optString("browser_download_url", "").trim();
            if (downloadUrl.isEmpty() || !name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                continue;
            }
            ReleaseAsset candidate = new ReleaseAsset(version, pageUrl, downloadUrl, name);
            if (name.toLowerCase(Locale.ROOT).startsWith("coop-")) {
                return candidate;
            }
            if (fallbackZip == null) {
                fallbackZip = candidate;
            }
        }
        return fallbackZip;
    }

    /**
     * Runs the full GitHub check with automatic fallbacks: {@code releases/latest} ->
     * {@code releases?per_page=5} (pre-releases) -> {@code raw/.../main/coop.version}.
     */
    static Outcome checkWithFallback(String currentVersion, Fetcher fetcher) {
        String userAgent = userAgent(currentVersion);
        Response latest;
        try {
            latest = fetcher.get(RELEASES_URL, userAgent);
        } catch (IOException | RuntimeException ex) {
            return Outcome.unavailable(describe(ex));
        }

        Outcome primary = evaluate(currentVersion, latest);
        if (primary.kind() != Kind.UNAVAILABLE) {
            return primary;
        }

        // On 404 ("no stable release published yet"), check the releases list for pre-releases.
        if (latest != null && latest.status() == 404) {
            try {
                Response listResponse = fetcher.get(RELEASES_LIST_URL, userAgent);
                Outcome prerelease = evaluateReleaseList(currentVersion, listResponse);
                if (prerelease.kind() != Kind.UNAVAILABLE) {
                    return prerelease;
                }
            } catch (IOException | RuntimeException ignored) {
                // Fall through to raw coop.version before giving up.
            }
        }

        // Fall back to coop.version on raw.githubusercontent.com (no REST API rate limit, works
        // behind CGNAT and before the first GitHub Release is tagged).
        try {
            Response versionFile = fetcher.get(MASTER_VERSION_URL, userAgent);
            Outcome fromFile = evaluateVersionFile(currentVersion, versionFile);
            if (fromFile.kind() != Kind.UNAVAILABLE) {
                return fromFile;
            }
        } catch (IOException | RuntimeException ignored) {
            // Keep the primary reason so the row reports the first concrete failure.
        }
        return primary;
    }

    static Outcome check(String currentVersion, String url, Fetcher fetcher) {
        Response response;
        try {
            response = fetcher.get(url, userAgent(currentVersion));
        } catch (IOException | RuntimeException ex) {
            return Outcome.unavailable(describe(ex));
        }
        return evaluate(currentVersion, response);
    }

    private static String userAgent(String currentVersion) {
        String version = currentVersion == null || currentVersion.isBlank() ? "dev" : currentVersion.trim();
        return "starsector-coop-launcher/" + version;
    }

    /** The pure half: an HTTP answer in, a row's worth of conclusion out. */
    static Outcome evaluate(String currentVersion, Response response) {
        if (response == null) {
            return Outcome.unavailable("no answer");
        }
        if (response.status() == 404) {
            // The repository has no published release yet, which is the normal state before the
            // first one ships. Not a problem with anything.
            return Outcome.unavailable("no release published yet");
        }
        if (response.status() == 403 || response.status() == 429) {
            return Outcome.unavailable("GitHub rate limit");
        }
        if (response.status() != 200) {
            return Outcome.unavailable("HTTP " + response.status());
        }
        String tag;
        String htmlUrl;
        try {
            JSONObject json = new JSONObject(response.body());
            tag = json.optString("tag_name", "");
            htmlUrl = json.optString("html_url", "");
        } catch (Exception ex) {
            // org.json in starsector-core throws a checked JSONException, so this has to be broad.
            return Outcome.unavailable("unreadable answer");
        }
        return compareOutcome(currentVersion, tag, htmlUrl);
    }

    /** Parses GitHub's {@code /releases} JSON array, picking the newest non-draft release. */
    static Outcome evaluateReleaseList(String currentVersion, Response response) {
        if (response == null || response.status() != 200) {
            return Outcome.unavailable("no release published yet");
        }
        try {
            JSONArray array = new JSONArray(response.body());
            for (int i = 0; i < array.length(); i++) {
                JSONObject release = array.optJSONObject(i);
                if (release == null || release.optBoolean("draft", false)) {
                    continue;
                }
                String tag = release.optString("tag_name", "");
                if (!normalise(tag).isEmpty()) {
                    String htmlUrl = release.optString("html_url", "");
                    return compareOutcome(currentVersion, tag, htmlUrl.isEmpty() ? RELEASES_PAGE_URL : htmlUrl);
                }
            }
        } catch (Exception ex) {
            return Outcome.unavailable("unreadable answer");
        }
        return Outcome.unavailable("no release published yet");
    }

    /**
     * Parses a LazyWizard/Version-Checker {@code coop.version} file from GitHub raw content
     * (whole-line and end-of-line {@code #} comments outside strings are stripped first).
     */
    static Outcome evaluateVersionFile(String currentVersion, Response response) {
        if (response == null || response.status() != 200) {
            return Outcome.unavailable("version file unavailable");
        }
        String stripped = stripHashComments(response.body());
        try {
            JSONObject json = new JSONObject(stripped);
            String remote = parseModVersion(json.opt("modVersion"));
            if (remote.isEmpty()) {
                return Outcome.unavailable("no modVersion in coop.version");
            }
            String downloadUrl = json.optString("directDownloadURL", "").trim();
            if (downloadUrl.isEmpty()) {
                downloadUrl = RELEASES_PAGE_URL;
            }
            return compareOutcome(currentVersion, remote, downloadUrl);
        } catch (Exception ex) {
            return Outcome.unavailable("unreadable coop.version");
        }
    }

    private static Outcome compareOutcome(String currentVersion, String tagOrVersion, String url) {
        String released = normalise(tagOrVersion);
        if (released.isEmpty()) {
            return Outcome.unavailable("no tag_name in the answer");
        }
        if (compare(released, normalise(currentVersion)) > 0) {
            return new Outcome(Kind.UPDATE_AVAILABLE, released, url, "");
        }
        return new Outcome(Kind.UP_TO_DATE, released, url, "");
    }

    private static String parseModVersion(Object raw) {
        if (raw instanceof JSONObject obj) {
            if (!obj.has("major") && !obj.has("minor") && !obj.has("patch")) {
                return "";
            }
            int major = obj.optInt("major", 0);
            int minor = obj.optInt("minor", 0);
            int patch = obj.optInt("patch", 0);
            return major + "." + minor + "." + patch;
        }
        if (raw instanceof String text) {
            return text.trim();
        }
        return "";
    }

    /** Strips {@code #} comments from a {@code .version} file while preserving {@code #} inside strings. */
    static String stripHashComments(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        boolean inString = false;
        boolean escaped = false;
        boolean inComment = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inComment) {
                if (ch == '\n' || ch == '\r') {
                    inComment = false;
                    out.append(ch);
                }
                continue;
            }
            if (inString) {
                out.append(ch);
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
                out.append(ch);
            } else if (ch == '#') {
                inComment = true;
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    /** The row for an outcome. Never {@link CoopInstallCheck.Status#FAIL}: this cannot block a launch. */
    public static CoopInstallCheck.Row row(Outcome outcome) {
        return row(outcome, "");
    }

    /**
     * The row for an outcome, optionally naming the installed version in its detail line.
     * Never {@link CoopInstallCheck.Status#FAIL}: this cannot block a launch.
     */
    public static CoopInstallCheck.Row row(Outcome outcome, String currentVersion) {
        String installed = normalise(currentVersion);
        return switch (outcome.kind()) {
            case UPDATE_AVAILABLE -> {
                String targetUrl = outcome.url().isEmpty() ? RELEASES_PAGE_URL : outcome.url();
                String prefix = installed.isEmpty()
                        ? ""
                        : "Installed " + installed + ", latest on GitHub is " + outcome.version() + ". ";
                yield new CoopInstallCheck.Row(
                        "Update available: " + outcome.version(),
                        CoopInstallCheck.Status.WARN,
                        prefix + "Both players must install the same release; open " + targetUrl,
                        "");
            }
            case UP_TO_DATE -> {
                String detail;
                if (installed.isEmpty()) {
                    detail = "GitHub has no newer release";
                } else if (compare(installed, outcome.version()) > 0) {
                    detail = "Local build " + installed + " is ahead of GitHub release " + outcome.version();
                } else {
                    detail = "Installed " + installed + " matches latest GitHub release (" + outcome.version() + ")";
                }
                yield new CoopInstallCheck.Row(
                        "Up to date: " + outcome.version(),
                        CoopInstallCheck.Status.OK,
                        detail,
                        "");
            }
            case UNAVAILABLE -> new CoopInstallCheck.Row(
                    "Update check: unavailable (" + outcome.reason() + ")",
                    CoopInstallCheck.Status.INFO,
                    "This does not stop you launching. Check the release page by hand if you want"
                            + " to be sure you both have the same build.",
                    "");
        };
    }

    /**
     * A tag as a version: a leading {@code v} goes, and so does everything from the first
     * {@code -}. {@code v1.2.0-rc1} and {@code 1.2.0} therefore compare equal, which is the safe
     * direction - a pre-release is not something to nag a player about.
     */
    static String normalise(String tag) {
        String trimmed = tag == null ? "" : tag.trim();
        if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            trimmed = trimmed.substring(1);
        }
        int dash = trimmed.indexOf('-');
        if (dash >= 0) {
            trimmed = trimmed.substring(0, dash);
        }
        int plus = trimmed.indexOf('+');
        if (plus >= 0) {
            trimmed = trimmed.substring(0, plus);
        }
        return trimmed.trim();
    }

    /**
     * Numeric {@code major.minor.patch} compare. Missing parts count as zero, so {@code 1.2} and
     * {@code 1.2.0} are equal, and a part that is not a number counts as zero rather than throwing -
     * a garbage tag has to produce "up to date", never a crash on a background thread.
     */
    static int compare(String left, String right) {
        String[] a = split(left);
        String[] b = split(right);
        int parts = Math.max(a.length, b.length);
        for (int i = 0; i < parts; i++) {
            int result = Integer.compare(part(a, i), part(b, i));
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private static String[] split(String version) {
        String value = version == null ? "" : version.trim();
        return value.isEmpty() ? new String[0] : value.split("\\.");
    }

    private static int part(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index].trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static Response fetch(String endpoint, String userAgent) throws IOException {
        URL url;
        try {
            url = URI.create(endpoint).toURL();
        } catch (RuntimeException ex) {
            throw new IOException("bad endpoint " + endpoint, ex);
        }
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("refusing a non-HTTPS update endpoint: " + endpoint);
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setInstanceFollowRedirects(true);
        if ("api.github.com".equalsIgnoreCase(url.getHost())) {
            connection.setRequestProperty("Accept", "application/vnd.github+json");
        } else {
            connection.setRequestProperty("Accept", "application/json, text/plain");
        }
        connection.setRequestProperty("User-Agent", userAgent);
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream()
                    : connection.getInputStream();
            String body = "";
            if (stream != null) {
                try (InputStream open = stream) {
                    body = new String(open.readNBytes(MAX_RESPONSE_BYTES), StandardCharsets.UTF_8);
                }
            }
            return new Response(status, body);
        } finally {
            connection.disconnect();
        }
    }

    private static String describe(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        }
        String trimmed = message.trim();
        return trimmed.length() > 80 ? trimmed.substring(0, 77) + "..." : trimmed;
    }
}
