package coop;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Global script-sandbox guard across every class compiled into {@code coop.jar}.
 *
 * <p>Starsector's guarded script classloader ({@code com.fs.starfarer.loading.scripts.B.loadClass})
 * refuses {@code java.lang.reflect.*} (minus four interfaces), {@code java.nio.file.File*} and
 * {@code java.io.*} (minus a narrow allow-list). Violations compile cleanly and pass unit tests,
 * then crash the live game with a Fatal dialog. Earlier sandbox tests only scanned individual
 * packages, which let a {@code java.lang.reflect.Method} call slip into {@code coop.wingman}; this
 * test scans the entire mod source tree instead.
 *
 * <p>Packages excluded from {@code coop.jar} and bundled only into {@code coop-forks.jar} (system
 * classloader: {@code coop/rng}, {@code coop/presence}, {@code coop/ambient}, {@code coop/storm})
 * are skipped for the {@code java.io.} check only, because system-loader classes are not subject to
 * the script classloader.
 */
class CoopSandboxCompatibilityTest {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();
    private static final Path MAIN_ROOT = PROJECT_ROOT.resolve("src/main/java/coop");

    private static final Set<String> SYSTEM_LOADER_PACKAGES =
            Set.of("rng", "presence", "ambient", "storm");

    private static final List<String> ALWAYS_BANNED = List.of(
            "java.lang.reflect.",
            "java.nio.file.",
            "HttpURLConnection",
            "URL.openStream",
            "javax.xml.",
            "getProtectionDomain");

    @Test
    void everyModClassInCoopJarStaysInsideStarsectorsScriptSandbox() throws IOException {
        List<Path> sources;
        try (Stream<Path> files = Files.walk(MAIN_ROOT)) {
            sources = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
        assertFalse(sources.isEmpty(), "expected mod sources under " + MAIN_ROOT);

        List<String> offenders = new ArrayList<>();
        for (Path path : sources) {
            Path relToCoop = MAIN_ROOT.relativize(path);
            String topPkg = relToCoop.getNameCount() > 1 ? relToCoop.getName(0).toString() : "";
            boolean systemLoaderOnly = SYSTEM_LOADER_PACKAGES.contains(topPkg);

            String code = stripComments(Files.readString(path, StandardCharsets.UTF_8));
            String display = PROJECT_ROOT.relativize(path).toString().replace('\\', '/');

            for (String banned : ALWAYS_BANNED) {
                if (code.contains(banned)) {
                    offenders.add(display + " uses " + banned);
                }
            }
            if (!systemLoaderOnly && code.contains("java.io.")) {
                offenders.add(display + " uses java.io.");
            }
        }

        assertEquals(List.of(), offenders,
                "Starsector's script classloader refuses reflection, file I/O and java.io.* at runtime");
    }

    private static String stripComments(String source) {
        StringBuilder code = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
            } else if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? source.length() : end;
            } else {
                code.append(source.charAt(i));
                i++;
            }
        }
        return code.toString();
    }
}
