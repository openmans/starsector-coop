package coop.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Computes real SHA-256 digests of mod {@code .jar} binaries on disk from the launcher process
 * (outside Starsector's script sandbox) and formats them as the {@code coop.launcher.modJarDigests}
 * property consumed by {@code CoopHandshakeManifest}.
 */
public final class CoopModJarDigester {

    public static final String PROPERTY_KEY = "coop.launcher.modJarDigests";

    private CoopModJarDigester() {
    }

    /**
     * Scans {@code modsDir} for every {@code <modFolder>/jars/*.jar} and returns a deterministic
     * map of {@code "<modFolder>/jars/<name>.jar" -> lowercase SHA-256 hex}.
     */
    public static Map<String, String> digestModsDirectory(Path modsDir) throws IOException {
        Map<String, String> digests = new LinkedHashMap<>();
        if (modsDir == null || !Files.isDirectory(modsDir)) {
            return digests;
        }
        List<Path> jarPaths = new ArrayList<>();
        try (Stream<Path> modDirs = Files.list(modsDir)) {
            modDirs.filter(Files::isDirectory).forEach(modDir -> {
                Path jarsDir = modDir.resolve("jars");
                if (Files.isDirectory(jarsDir)) {
                    try (Stream<Path> jars = Files.list(jarsDir)) {
                        jars.filter(Files::isRegularFile)
                                .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                                .forEach(jarPaths::add);
                    } catch (IOException ignored) {
                        // skip unreadable mod folder
                    }
                }
            });
        }
        jarPaths.sort(Comparator.comparing(p -> normalizeRelative(modsDir, p)));
        for (Path jar : jarPaths) {
            String key = normalizeRelative(modsDir, jar);
            digests.put(key, sha256File(jar));
        }
        return digests;
    }

    /** Encodes the digest map into a compact semicolon-separated property value. */
    public static String encodeProperty(Map<String, String> digests) {
        if (digests == null || digests.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : digests.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static String normalizeRelative(Path modsDir, Path jar) {
        return modsDir.relativize(jar).toString().replace('\\', '/');
    }

    static String sha256File(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
        byte[] buf = new byte[16 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        byte[] hash = md.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >>> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
