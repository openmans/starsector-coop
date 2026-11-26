package coop.launcher;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.log4j.Logger;

/**
 * One-click downloader and installer for Starsector Coop.
 *
 * <p>Given a Starsector install layout (chosen via {@code starsector.exe} or the game folder),
 * this class downloads the latest {@code coop-*.zip} release from GitHub, extracts it safely into
 * {@code <Starsector>/mods/coop}, patches/repairs {@code vmparams} (with backup), and enables
 * {@code "coop"} in {@code mods/enabled_mods.json}.
 *
 * <p>On Windows, if the launcher is updating itself while {@code coop-launcher.jar} or
 * {@code flatlaf.jar} is held open by the running JVM, the new jar is staged alongside as
 * {@code <name>.pending} so {@code Coop Launcher.cmd} swaps it in on the next start rather than
 * failing the update.
 */
public final class CoopModInstaller {

    private static final Logger LOG = Logger.getLogger(CoopModInstaller.class);

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 20_000;
    private static final int MAX_REDIRECTS = 6;
    /** Safety ceiling for a release zip (current archive is ~3.6 MiB). */
    private static final long MAX_DOWNLOAD_BYTES = 128L * 1024L * 1024L;
    private static final int BUFFER_SIZE = 16 * 1024;

    /** Receives human-readable progress messages and optional percentage (-1 when unknown). */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String stage, int percent);
    }

    /** Downloads {@code url} into {@code targetFile}, reporting progress. Seam for unit tests. */
    @FunctionalInterface
    interface Downloader {
        void download(String url, String userAgent, Path targetFile, ProgressListener progress)
                throws IOException;
    }

    /**
     * Outcome of a full one-click install/update run.
     *
     * @param ok                 true when the mod files were installed and configured
     * @param accessDenied       true when the Starsector folder rejected writes (offer UAC relaunch)
     * @param restartRecommended true when a locked launcher jar was staged as {@code .pending}
     * @param version            the installed release version (empty on failure)
     * @param filesExtracted     number of files unpacked into {@code mods/coop}
     * @param summary            player-facing summary line
     * @param details            per-step log lines
     */
    public record InstallResult(
            boolean ok,
            boolean accessDenied,
            boolean restartRecommended,
            String version,
            int filesExtracted,
            String summary,
            List<String> details) {
        public InstallResult {
            version = version == null ? "" : version;
            summary = summary == null ? "" : summary;
            details = details == null ? List.of() : List.copyOf(details);
        }
    }

    private CoopModInstaller() {
    }

    /**
     * Full one-click install/update against GitHub: resolves the latest release zip, downloads and
     * extracts it into {@code layout.modRoot()}, then runs {@link CoopInstallFixer} for both
     * {@code vmparams} and {@code enabled_mods.json}.
     */
    public static InstallResult installLatest(CoopInstallLayout layout, String currentVersion,
                                              ProgressListener progress) {
        return installLatest(layout, currentVersion, false, progress);
    }

    /**
     * Full one-click install/update against GitHub, optionally also downloading and enabling
     * The Wingman Project ({@code mods/Wingman}, {@code "wingman_project"}) for manual co-op
     * battle piloting.
     */
    public static InstallResult installLatest(CoopInstallLayout layout, String currentVersion,
                                              boolean includeWingman, ProgressListener progress) {
        return installLatest(layout, currentVersion, includeWingman,
                CoopUpdateCheck::resolveLatestAsset,
                CoopUpdateCheck::resolveLatestWingmanAsset,
                CoopModInstaller::downloadHttps, progress);
    }

    @FunctionalInterface
    interface AssetResolver {
        CoopUpdateCheck.ReleaseAsset resolve(String currentVersion) throws IOException;
    }

    static InstallResult installLatest(CoopInstallLayout layout, String currentVersion,
                                       AssetResolver resolver, Downloader downloader,
                                       ProgressListener progress) {
        return installLatest(layout, currentVersion, false, resolver, null, downloader, progress);
    }

    static InstallResult installLatest(CoopInstallLayout layout, String currentVersion,
                                       boolean includeWingman,
                                       AssetResolver coopResolver,
                                       AssetResolver wingmanResolver,
                                       Downloader downloader,
                                       ProgressListener progress) {
        Objects.requireNonNull(layout, "layout");
        ProgressListener listener = progress == null ? (s, p) -> {} : progress;
        List<String> details = new ArrayList<>();

        listener.onProgress("Checking GitHub releases...", -1);
        CoopUpdateCheck.ReleaseAsset asset;
        try {
            asset = coopResolver.resolve(currentVersion);
        } catch (IOException | RuntimeException ex) {
            String msg = "Could not find a downloadable release on GitHub: " + ex.getMessage();
            LOG.warn(msg, ex);
            details.add(msg);
            return new InstallResult(false, false, false, "", 0, msg, details);
        }
        details.add("Found GitHub release v" + asset.version() + " (" + asset.assetName() + ").");

        Path tempZip = null;
        try {
            tempZip = Files.createTempFile("starsector-coop-", ".zip");
            String agent = "starsector-coop-launcher/"
                    + (currentVersion == null || currentVersion.isBlank() ? "dev" : currentVersion.trim());
            listener.onProgress("Downloading " + asset.assetName() + "...", 0);
            downloader.download(asset.zipUrl(), agent, tempZip, listener);
            details.add("Downloaded " + asset.assetName() + " (" + Files.size(tempZip) + " bytes).");

            listener.onProgress("Extracting mod files...", -1);
            ExtractOutcome extracted = extractArchive(tempZip, layout.modRoot().toPath(), "coop");
            int totalFiles = extracted.filesExtracted();
            details.add("Extracted " + extracted.filesExtracted() + " files to " + layout.modRoot() + ".");
            if (!extracted.pendingFiles().isEmpty()) {
                details.add("Staged locked launcher jar(s) for next restart: "
                        + String.join(", ", extracted.pendingFiles()) + ".");
            }

            listener.onProgress("Configuring vmparams and enabled_mods.json...", -1);
            CoopInstallFixer.Result vmResult = CoopInstallFixer.apply(layout, CoopInstallFixer.Target.VMPARAMS);
            details.add(vmResult.message());
            CoopInstallFixer.Result modsResult = CoopInstallFixer.apply(layout, CoopInstallFixer.Target.ENABLED_MODS);
            details.add(modsResult.message());

            boolean denied = vmResult.accessDenied() || modsResult.accessDenied();
            if (denied) {
                return new InstallResult(false, true, !extracted.pendingFiles().isEmpty(),
                        asset.version(), totalFiles,
                        "Windows denied write access to the Starsector folder.", details);
            }

            String wingmanSuffix = "";
            if (includeWingman && wingmanResolver != null) {
                listener.onProgress("Checking Wingman Project on GitHub...", -1);
                CoopUpdateCheck.ReleaseAsset wingman = wingmanResolver.resolve(currentVersion);
                details.add("Found Wingman release v" + wingman.version() + " (" + wingman.assetName() + ").");
                Path wingmanZip = Files.createTempFile("starsector-wingman-", ".zip");
                try {
                    listener.onProgress("Downloading " + wingman.assetName() + "...", 0);
                    downloader.download(wingman.zipUrl(), agent, wingmanZip, listener);
                    details.add("Downloaded " + wingman.assetName() + " (" + Files.size(wingmanZip) + " bytes).");
                    listener.onProgress("Extracting Wingman to mods\\Wingman...", -1);
                    ExtractOutcome wingmanOut = extractArchive(wingmanZip, layout.wingmanModRoot().toPath(),
                            CoopInstallLayout.WINGMAN_FOLDER);
                    totalFiles += wingmanOut.filesExtracted();
                    details.add("Extracted " + wingmanOut.filesExtracted() + " Wingman files to "
                            + layout.wingmanModRoot() + ".");
                    CoopInstallFixer.Result wmEnable = CoopInstallFixer.applyEnabledMod(
                            layout, CoopInstallLayout.WINGMAN_MOD_ID);
                    details.add(wmEnable.message());
                    if (wmEnable.accessDenied()) {
                        return new InstallResult(false, true, !extracted.pendingFiles().isEmpty(),
                                asset.version(), totalFiles,
                                "Windows denied write access while enabling Wingman.", details);
                    }
                    wingmanSuffix = " + Wingman v" + wingman.version();
                } finally {
                    try {
                        Files.deleteIfExists(wingmanZip);
                    } catch (IOException ignored) {
                    }
                }
            }

            listener.onProgress("Installed v" + asset.version(), 100);
            String summary = "Installed Starsector Coop v" + asset.version() + wingmanSuffix
                    + " (" + totalFiles + " files)"
                    + (extracted.pendingFiles().isEmpty()
                    ? "."
                    : ". Restart Coop Launcher to swap the locked jar file(s).");
            return new InstallResult(true, false, !extracted.pendingFiles().isEmpty(),
                    asset.version(), totalFiles, summary, details);
        } catch (AccessDeniedException ex) {
            String msg = "Windows would not let the installer write into " + layout.modRoot() + ".";
            LOG.warn(msg, ex);
            details.add(msg);
            return new InstallResult(false, true, false, asset.version(), 0, msg, details);
        } catch (IOException | RuntimeException ex) {
            String msg = "Install failed: " + ex.getMessage();
            LOG.warn(msg, ex);
            details.add(msg);
            return new InstallResult(false, false, false, asset.version(), 0, msg, details);
        } finally {
            if (tempZip != null) {
                try {
                    Files.deleteIfExists(tempZip);
                } catch (IOException ignored) {
                }
            }
        }
    }

    record ExtractOutcome(int filesExtracted, List<String> pendingFiles) {
    }

    static ExtractOutcome extractArchive(Path zipPath, Path modRoot) throws IOException {
        return extractArchive(zipPath, modRoot, "coop");
    }

    /**
     * Unpacks a release zip into {@code modRoot}. Strips a leading {@code <expectedPrefix>/}
     * folder prefix (for example {@code coop/} or {@code Wingman/}) and rejects any entry that
     * would escape {@code modRoot}.
     */
    static ExtractOutcome extractArchive(Path zipPath, Path modRoot, String expectedPrefix) throws IOException {
        Path normalizedRoot = modRoot.toAbsolutePath().normalize();
        Files.createDirectories(normalizedRoot);

        int count = 0;
        List<String> pending = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipPath)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String rawName = entry.getName().replace('\\', '/');
                String relative = stripLeadingPrefix(rawName, expectedPrefix);
                if (relative.isEmpty() || entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }
                Path dest = normalizedRoot.resolve(relative).normalize();
                if (!dest.startsWith(normalizedRoot)) {
                    throw new IOException("Refusing unsafe zip entry outside mod folder: " + rawName);
                }
                Path parent = dest.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                Path staged = Files.createTempFile(parent, ".coop-unpack-", ".tmp");
                try {
                    try (OutputStream out = Files.newOutputStream(staged)) {
                        zis.transferTo(out);
                    }
                    try {
                        moveReplace(staged, dest);
                    } catch (FileSystemException ex) {
                        if (isStageableJar(relative)) {
                            Path pendingTarget = dest.resolveSibling(dest.getFileName() + ".pending");
                            moveReplace(staged, pendingTarget);
                            pending.add(relative);
                            LOG.info("Staged locked jar to " + pendingTarget);
                        } else {
                            throw ex;
                        }
                    }
                    count++;
                } finally {
                    Files.deleteIfExists(staged);
                }
                zis.closeEntry();
            }
        }
        if (count == 0) {
            throw new IOException("The downloaded archive contained no mod files.");
        }
        return new ExtractOutcome(count, pending);
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String stripLeadingCoopPrefix(String entryName) {
        return stripLeadingPrefix(entryName, "coop");
    }

    static String stripLeadingPrefix(String entryName, String prefix) {
        String clean = entryName == null ? "" : entryName.replace('\\', '/').trim();
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        if (prefix == null || prefix.isEmpty()) {
            return clean;
        }
        String folder = prefix + "/";
        if (clean.equalsIgnoreCase(prefix) || clean.equalsIgnoreCase(folder)) {
            return "";
        }
        if (clean.regionMatches(true, 0, folder, 0, folder.length())) {
            return clean.substring(folder.length());
        }
        return clean;
    }

    /**
     * Any mod jar under {@code jars/} may be held open by the running launcher JVM on Windows
     * ({@code coop-launcher.jar}, {@code flatlaf.jar}, {@code coop.jar}). Staging as
     * {@code .pending} lets {@code Coop Launcher.cmd} swap them cleanly on restart.
     */
    static boolean isStageableJar(String relativePath) {
        if (relativePath == null) {
            return false;
        }
        String lower = relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        return lower.startsWith("jars/") && lower.endsWith(".jar");
    }

    /** Downloads an HTTPS URL to {@code targetFile}, following HTTPS redirects (GitHub CDN). */
    static void downloadHttps(String initialUrl, String userAgent, Path targetFile,
                              ProgressListener progress) throws IOException {
        String current = initialUrl;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            URL url;
            try {
                url = URI.create(current).toURL();
            } catch (RuntimeException ex) {
                throw new IOException("Invalid download URL: " + current, ex);
            }
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                throw new IOException("Refusing non-HTTPS download URL: " + current);
            }
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            conn.setReadTimeout(READ_TIMEOUT_MILLIS);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", userAgent);
            try {
                int status = conn.getResponseCode();
                if (status == HttpURLConnection.HTTP_MOVED_PERM
                        || status == HttpURLConnection.HTTP_MOVED_TEMP
                        || status == HttpURLConnection.HTTP_SEE_OTHER
                        || status == 307 || status == 308) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isBlank()) {
                        throw new IOException("Redirect without Location header from " + current);
                    }
                    current = URI.create(current).resolve(location).toString();
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IOException("Download failed with HTTP " + status + " from " + current);
                }
                long total = conn.getContentLengthLong();
                if (total > MAX_DOWNLOAD_BYTES) {
                    throw new IOException("Archive exceeds safety limit (" + total + " bytes).");
                }
                try (InputStream in = conn.getInputStream();
                     OutputStream out = Files.newOutputStream(targetFile)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    long copied = 0;
                    int lastPercent = -1;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        copied += read;
                        if (copied > MAX_DOWNLOAD_BYTES) {
                            throw new IOException("Archive exceeded safety limit while downloading.");
                        }
                        out.write(buffer, 0, read);
                        if (total > 0) {
                            int pct = (int) Math.min(100, (copied * 100L) / total);
                            if (pct != lastPercent) {
                                lastPercent = pct;
                                progress.onProgress("Downloading... " + pct + "%", pct);
                            }
                        }
                    }
                }
                return;
            } finally {
                conn.disconnect();
            }
        }
        throw new IOException("Too many redirects downloading " + initialUrl);
    }
}
