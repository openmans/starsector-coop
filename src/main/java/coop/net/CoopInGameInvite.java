package coop.net;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * In-game parser, formatter, and generator for {@code coop://} invites, deterministic seeds,
 * and lobby passwords — wire-identical to {@code coop.launcher.CoopInvite}.
 *
 * <p>Format: {@code coop://<host>:<port>/?seed=<seed>&pw=<pw>&size=<size>&age=<age>&cid=<cid>}
 */
public final class CoopInGameInvite {

    public static final String SCHEME = "coop://";
    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] PW_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();

    public record Invite(String host, int port, String seed, String password,
                         String sectorSize, String sectorAge, String campaignId) {
        public Invite {
            host = trim(host);
            seed = trim(seed);
            password = password == null ? "" : password;
            sectorSize = trim(sectorSize).toLowerCase(Locale.ROOT);
            sectorAge = trim(sectorAge).toLowerCase(Locale.ROOT);
            campaignId = trim(campaignId);
        }
    }

    public record Parsed(Invite invite, String error) {
        public boolean ok() {
            return invite != null;
        }
    }

    private CoopInGameInvite() {
    }

    /** Generates a valid {@code MN-<18-19 digits>} seed accepted by Starsector's {@code Long.parseLong}. */
    public static String generateSeed() {
        long value = Math.abs(RNG.nextLong());
        if (value == Long.MIN_VALUE || value < 1_000_000_000_000_000_000L) {
            value = 1_000_000_000_000_000_000L + (RNG.nextLong() & 0x3FFFFFFFFFFFFFFFL);
        }
        return "MN-" + value;
    }

    /** Generates a 10-character lowercase alphanumeric lobby password without ambiguous glyphs. */
    public static String generatePassword() {
        char[] chars = new char[10];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = PW_ALPHABET[RNG.nextInt(PW_ALPHABET.length)];
        }
        return new String(chars);
    }

    /** Formats an invite URI. Returns {@code ""} if host or port is invalid. */
    public static String format(String host, int port, String seed, String password,
                                String sectorSize, String sectorAge, String campaignId) {
        String cleanHost = trim(host);
        if (cleanHost.isEmpty() || port < 1 || port > 65535) {
            return "";
        }
        String hostPart = cleanHost.indexOf(':') >= 0 && !cleanHost.startsWith("[")
                ? "[" + cleanHost + "]" : cleanHost;
        StringBuilder sb = new StringBuilder(SCHEME)
                .append(hostPart).append(':').append(port).append("/?");
        boolean first = true;
        first = appendParam(sb, first, "seed", trim(seed));
        first = appendParam(sb, first, "pw", password == null ? "" : password);
        first = appendParam(sb, first, "size", trim(sectorSize).toLowerCase(Locale.ROOT));
        first = appendParam(sb, first, "age", trim(sectorAge).toLowerCase(Locale.ROOT));
        appendParam(sb, first, "cid", trim(campaignId));
        if (sb.charAt(sb.length() - 1) == '?') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private static boolean appendParam(StringBuilder sb, boolean first, String key, String value) {
        if (value == null || value.isEmpty()) {
            return first;
        }
        if (!first) {
            sb.append('&');
        }
        sb.append(key).append('=').append(encode(value));
        return false;
    }

    /** Parses a {@code coop://...} invite string. */
    public static Parsed parse(String rawText) {
        String raw = trim(rawText);
        if (raw.isEmpty()) {
            return new Parsed(null, "Empty invite");
        }
        if (raw.length() < SCHEME.length() || !raw.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            return new Parsed(null, "Must start with coop://");
        }
        String rest = raw.substring(SCHEME.length());
        int slashIdx = rest.indexOf('/');
        int queryIdx = rest.indexOf('?');
        int endAuthority;
        String query = "";
        if (slashIdx >= 0 && (queryIdx < 0 || slashIdx < queryIdx)) {
            endAuthority = slashIdx;
            if (queryIdx > slashIdx) {
                query = rest.substring(queryIdx + 1);
            }
        } else if (queryIdx >= 0) {
            endAuthority = queryIdx;
            query = rest.substring(queryIdx + 1);
        } else {
            endAuthority = rest.length();
        }
        String authority = rest.substring(0, endAuthority).trim();
        if (authority.isEmpty()) {
            return new Parsed(null, "Missing host address");
        }
        String host;
        String portStr;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                return new Parsed(null, "Unclosed IPv6 bracket");
            }
            host = authority.substring(1, close).trim();
            if (close + 1 >= authority.length() || authority.charAt(close + 1) != ':') {
                return new Parsed(null, "Missing port");
            }
            portStr = authority.substring(close + 2).trim();
        } else {
            int colon = authority.lastIndexOf(':');
            if (colon <= 0) {
                return new Parsed(null, "Missing port");
            }
            host = authority.substring(0, colon).trim();
            portStr = authority.substring(colon + 1).trim();
        }
        if (host.isEmpty()) {
            return new Parsed(null, "Missing host address");
        }
        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException ex) {
            return new Parsed(null, "Invalid port: " + portStr);
        }
        if (port < 1 || port > 65535) {
            return new Parsed(null, "Port out of range: " + port);
        }

        String seed = "";
        String password = "";
        String sectorSize = "";
        String sectorAge = "";
        String campaignId = "";
        if (!query.isEmpty()) {
            for (String pair : query.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String k = eq < 0 ? pair : pair.substring(0, eq);
                String v = eq < 0 ? "" : decode(pair.substring(eq + 1));
                switch (k.toLowerCase(Locale.ROOT)) {
                    case "seed" -> seed = v;
                    case "pw" -> password = v;
                    case "size" -> sectorSize = v;
                    case "age" -> sectorAge = v;
                    case "cid" -> campaignId = v;
                    default -> {
                    }
                }
            }
        }
        return new Parsed(new Invite(host, port, seed, password, sectorSize, sectorAge, campaignId), null);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return s;
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
