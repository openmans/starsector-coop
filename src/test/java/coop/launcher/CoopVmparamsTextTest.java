package coop.launcher;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the four shapes a real {@code vmparams} takes on this project: stock 0.98a-RC8, the dev
 * box's modded-JRE copy, one patched by hand per INSTALL.md, and one a launch script left
 * {@code -Dcoop.*} on.
 *
 * <p>The strings below are trimmed copies of the real files (the flag soup in the middle is elided;
 * nothing here reads it). What matters is kept verbatim: the tail of the JVM flags, the
 * {@code -classpath} marker with its single leading space, and the classpath value itself.
 */
class CoopVmparamsTextTest {

    private static final String FLAGS_TAIL =
            "java.exe -noverify -XX:+UnlockDiagnosticVMOptions -Xmx2048m -Xms2048m -Xss4m"
                    + " -Dcom.fs.starfarer.settings.paths.saves=..\\\\saves"
                    + " -Dcom.fs.starfarer.settings.paths.logs=.";

    private static final String STOCK_CLASSPATH =
            String.join(";", CoopVmparamsText.CANONICAL_STOCK_JARS)
                    + " com.fs.starfarer.StarfarerLauncher";

    private static final String TUNING_FLAGS =
            "java.exe -noverify -XX:+UseAVX=3 -XX:+UseShenandoahGC -XX:ShenandoahGCMode=iu"
                    + " -XX:+ZeroTLAB -Xmx2048m -Xms2048m"
                    + " -Dcom.fs.starfarer.settings.paths.saves=..\\\\saves"
                    + " -Dcom.fs.starfarer.settings.paths.logs=.";

    /** Incident sample 1: RU tuning pack injected quoted absolute path to vmparams itself. */
    static final String INCIDENT_SAMPLE_1 = TUNING_FLAGS + CoopVmparamsText.CLASSPATH_MARKER
            + "..\\mods\\coop\\jars\\coop-forks.jar;\"G:\\Starsector 0.98.5\\Starsector\\vmparams\" "
            + STOCK_CLASSPATH;

    /** Incident sample 2: tuning pack template put quoted absolute forks path with space before janino. */
    static final String INCIDENT_SAMPLE_2 = TUNING_FLAGS + CoopVmparamsText.CLASSPATH_MARKER
            + "\"G:\\Starsector 0.98.5\\Starsector\\mods\\coop\\jars\\coop-forks.jar\" "
            + STOCK_CLASSPATH;

    /** A stock install: no coop entry, no coop properties. */
    private static final String STOCK =
            FLAGS_TAIL + CoopVmparamsText.CLASSPATH_MARKER + STOCK_CLASSPATH;

    /** Patched by hand exactly as INSTALL.md section 3 describes. */
    private static final String PATCHED = FLAGS_TAIL + CoopVmparamsText.CLASSPATH_MARKER
            + CoopVmparamsText.FORKS_ENTRY + STOCK_CLASSPATH;

    /** What launch-host.ps1 leaves behind: the forks entry plus a set of coop properties. */
    private static final String LAUNCH_SCRIPT_PATCHED = FLAGS_TAIL
            + " -Dcoop.hostPort=7777 -Dcoop.newGameSeed=MN-1234567890123456789"
            + " -Dcoop.debug.diagnostics=true -Dcoop.debug.bridge=7801"
            + CoopVmparamsText.CLASSPATH_MARKER + CoopVmparamsText.FORKS_ENTRY + STOCK_CLASSPATH;

    @Test
    void aStockVmparamsHasNoForksEntryAndNoCoopProperties() {
        assertTrue(CoopVmparamsText.hasClasspath(STOCK));
        assertFalse(CoopVmparamsText.hasForksFirstOnClasspath(STOCK));
        assertFalse(CoopVmparamsText.hasForksLaterOnClasspath(STOCK));
        assertEquals(List.of(), CoopVmparamsText.staleCoopProperties(STOCK));
    }

    @Test
    void aHandPatchedVmparamsPasses() {
        assertTrue(CoopVmparamsText.hasForksFirstOnClasspath(PATCHED));
        assertFalse(CoopVmparamsText.hasForksLaterOnClasspath(PATCHED));
        assertEquals(List.of(), CoopVmparamsText.staleCoopProperties(PATCHED));
    }

    @Test
    void theDevLaunchScriptsLeaveTheForksEntryAndFourCoopProperties() {
        assertTrue(CoopVmparamsText.hasForksFirstOnClasspath(LAUNCH_SCRIPT_PATCHED));
        assertEquals(
                List.of("-Dcoop.hostPort=7777",
                        "-Dcoop.newGameSeed=MN-1234567890123456789",
                        "-Dcoop.debug.diagnostics=true",
                        "-Dcoop.debug.bridge=7801"),
                CoopVmparamsText.staleCoopProperties(LAUNCH_SCRIPT_PATCHED));
    }

    @Test
    void theForksEntryBehindAnotherJarIsNotFirst() {
        String behind = FLAGS_TAIL + CoopVmparamsText.CLASSPATH_MARKER
                + "janino.jar;" + CoopVmparamsText.FORKS_ENTRY + STOCK_CLASSPATH;

        assertFalse(CoopVmparamsText.hasForksFirstOnClasspath(behind));
        assertTrue(CoopVmparamsText.hasForksLaterOnClasspath(behind));
    }

    /**
     * A player who followed a forum post rather than INSTALL.md gets an absolute path. The JVM
     * loads exactly the same jar from it, so the launcher must not call the install broken and
     * refuse to launch.
     */
    @Test
    void anAbsolutePathToTheForksJarCountsAsMuchAsTheRelativeOne() {
        String absolute = FLAGS_TAIL + CoopVmparamsText.CLASSPATH_MARKER
                + "K:\\Starsector\\mods\\coop\\jars\\coop-forks.jar;" + STOCK_CLASSPATH;

        assertTrue(CoopVmparamsText.hasForksFirstOnClasspath(absolute));
        assertFalse(CoopVmparamsText.hasForksLaterOnClasspath(absolute));
    }

    @Test
    void anAbsolutePathBehindAnotherJarIsStillReportedAsNotFirst() {
        String behind = FLAGS_TAIL + CoopVmparamsText.CLASSPATH_MARKER
                + "janino.jar;K:\\Starsector\\mods\\coop\\jars\\coop-forks.jar;" + STOCK_CLASSPATH;

        assertFalse(CoopVmparamsText.hasForksFirstOnClasspath(behind));
        assertTrue(CoopVmparamsText.hasForksLaterOnClasspath(behind));
    }

    /** A jar whose name merely ends in the same letters is a different jar. */
    @Test
    void aSimilarlyNamedJarIsNotTheForksJar() {
        String lookalike = FLAGS_TAIL + CoopVmparamsText.CLASSPATH_MARKER
                + "..\\mods\\coop\\jars\\not-coop-forks.jar;" + STOCK_CLASSPATH;

        assertFalse(CoopVmparamsText.hasForksFirstOnClasspath(lookalike));
        assertFalse(CoopVmparamsText.hasForksLaterOnClasspath(lookalike));
    }

    @Test
    void forwardSlashesAndOddCasingStillCount() {
        String slashes = FLAGS_TAIL + CoopVmparamsText.CLASSPATH_MARKER
                + "../Mods/Coop/Jars/COOP-FORKS.JAR;" + STOCK_CLASSPATH;

        assertTrue(CoopVmparamsText.hasForksFirstOnClasspath(slashes));
    }

    @Test
    void aFileWithNoClasspathIsRejectedRatherThanGuessedAt() {
        assertFalse(CoopVmparamsText.hasClasspath("java.exe -Xmx2048m"));
        assertFalse(CoopVmparamsText.hasForksFirstOnClasspath("java.exe -Xmx2048m"));
        assertFalse(CoopVmparamsText.hasClasspath(null));
    }

    /**
     * Handed back as written, quotes off. Whoever resolves it has to know the paths are relative to
     * starsector-core, which is why this returns the text rather than a file.
     */
    @Test
    void theFirstClasspathEntryComesBackAsWrittenWithItsQuotesOff() {
        assertEquals("..\\mods\\coop\\jars\\coop-forks.jar",
                CoopVmparamsText.firstClasspathEntry(PATCHED));
        assertEquals("janino.jar", CoopVmparamsText.firstClasspathEntry(STOCK));
        assertEquals("C:\\Program Files\\coop\\jars\\coop-forks.jar",
                CoopVmparamsText.firstClasspathEntry(FLAGS_TAIL
                        + CoopVmparamsText.CLASSPATH_MARKER
                        + "\"C:\\Program Files\\coop\\jars\\coop-forks.jar\";" + STOCK_CLASSPATH));
        assertNull(CoopVmparamsText.firstClasspathEntry("java.exe -Xmx2048m"));
        assertNull(CoopVmparamsText.firstClasspathEntry(null));
    }

    @Test
    void theFixTextNamesTheExactEntryToPaste() {
        String fix = CoopVmparamsText.forksFixText();

        assertTrue(fix.contains(CoopVmparamsText.FORKS_ENTRY), fix);
        assertTrue(fix.contains("-classpath"), fix);
        assertTrue(fix.contains("INSTALL.md"), fix);
    }

    @Test
    void theStalePropertyFixNamesEveryLeftoverProperty() {
        String fix = CoopVmparamsText.stalePropertyFixText(
                CoopVmparamsText.staleCoopProperties(LAUNCH_SCRIPT_PATCHED));

        assertTrue(fix.contains("-Dcoop.hostPort=7777"), fix);
        assertTrue(fix.contains("-Dcoop.debug.bridge=7801"), fix);
    }

    @Test
    void classpathSanityPassesOnStockAndPatchedCanonicalLines() {
        assertTrue(CoopVmparamsText.classpathSanity(STOCK).ok());
        assertTrue(CoopVmparamsText.classpathSanity(PATCHED).ok());
        assertTrue(CoopVmparamsText.classpathSanity(LAUNCH_SCRIPT_PATCHED).ok());
    }

    @Test
    void incidentSamplesKeepForksFirstTrueButFailClasspathSanity() {
        assertTrue(CoopVmparamsText.hasForksFirstOnClasspath(INCIDENT_SAMPLE_1));
        assertTrue(CoopVmparamsText.hasForksFirstOnClasspath(INCIDENT_SAMPLE_2));

        CoopVmparamsText.ClasspathSanity s1 = CoopVmparamsText.classpathSanity(INCIDENT_SAMPLE_1);
        assertFalse(s1.ok(), "sample 1 must fail sanity");
        assertTrue(s1.reason().contains("quote"), s1.reason());

        CoopVmparamsText.ClasspathSanity s2 = CoopVmparamsText.classpathSanity(INCIDENT_SAMPLE_2);
        assertFalse(s2.ok(), "sample 2 must fail sanity");
        assertTrue(s2.reason().contains("quote"), s2.reason());
    }

    @Test
    void classpathSanityRejectsAbsolutePathsNonJarEntriesAndMissingStockJars() {
        String abs = FLAGS_TAIL + CoopVmparamsText.CLASSPATH_MARKER
                + "K:\\Starsector\\mods\\coop\\jars\\coop-forks.jar;" + STOCK_CLASSPATH;
        assertFalse(CoopVmparamsText.classpathSanity(abs).ok());

        String unc = FLAGS_TAIL + CoopVmparamsText.CLASSPATH_MARKER
                + "\\\\server\\share\\coop-forks.jar;" + STOCK_CLASSPATH;
        assertFalse(CoopVmparamsText.classpathSanity(unc).ok());

        String nonJar = FLAGS_TAIL + CoopVmparamsText.CLASSPATH_MARKER
                + CoopVmparamsText.FORKS_ENTRY + "..\\vmparams;" + STOCK_CLASSPATH;
        assertFalse(CoopVmparamsText.classpathSanity(nonJar).ok());

        String missingJanino = FLAGS_TAIL + CoopVmparamsText.CLASSPATH_MARKER
                + CoopVmparamsText.FORKS_ENTRY + "starfarer.api.jar;lwjgl.jar com.fs.starfarer.StarfarerLauncher";
        CoopVmparamsText.ClasspathSanity missing = CoopVmparamsText.classpathSanity(missingJanino);
        assertFalse(missing.ok());
        assertTrue(missing.reason().contains("janino.jar"), missing.reason());
    }
}
