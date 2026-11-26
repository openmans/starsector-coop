package coop.launcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure inspection of a {@code vmparams} file's text. Nothing here reads or writes a file; the edit
 * itself lives in {@link CoopInstallFixer}, which reuses this class's idea of what a forks entry
 * looks like so that what the check accepts and what the fix produces cannot drift apart.
 *
 * <p>Two questions matter. First, whether {@code coop-forks.jar} sits at the front of the JVM
 * {@code -classpath}: the forked engine classes only win over the originals if the system
 * classloader reaches them first, and Starsector's mod loader is a child loader, so the mod's own
 * jar list cannot do it. Second, whether any {@code -Dcoop.*} is still on the line: a system
 * property outranks the settings file the launcher writes, so a leftover from a dev launch script
 * silently overrides whatever the player just typed into the launcher.
 */
public final class CoopVmparamsText {

    /** The literal that separates the JVM flags from the classpath value. */
    public static final String CLASSPATH_MARKER = " -classpath ";

    /** The entry that has to come first, semicolon included. Mirrors INSTALL.md section 3. */
    public static final String FORKS_ENTRY = "..\\mods\\coop\\jars\\coop-forks.jar;";

    /** Default main class launched by a stock Starsector {@code vmparams}. */
    public static final String DEFAULT_MAIN_CLASS = "com.fs.starfarer.StarfarerLauncher";

    /**
     * Canonical stock Starsector 0.98.5a-RC9 classpath jars (relative to {@code starsector-core}),
     * in installer order. Used by {@link CoopInstallFixer#repairClasspath(String)} to rebuild a
     * classpath corrupted by third-party {@code vmparams} tuning packs.
     */
    public static final List<String> CANONICAL_STOCK_JARS = List.of(
            "janino.jar",
            "commons-compiler.jar",
            "commons-compiler-jdk.jar",
            "starfarer.api.jar",
            "starfarer_obf.jar",
            "jogg-0.0.7.jar",
            "jorbis-0.0.15.jar",
            "json.jar",
            "lwjgl.jar",
            "jinput.jar",
            "log4j-1.2.9.jar",
            "lwjgl_util.jar",
            "fs.sound_obf.jar",
            "fs.common_obf.jar",
            "xstream-1.4.10.jar",
            "txw2-3.0.2.jar",
            "jaxb-api-2.4.0-b180830.0359.jar",
            "webp-imageio-0.1.6.jar");

    /**
     * Bare jar filenames that must be present on a valid Starsector classpath; missing any of them
     * causes immediate startup crashes (e.g. {@code NoClassDefFoundError: janino} in ScriptStore).
     */
    public static final List<String> REQUIRED_STOCK_JARS = List.of(
            "janino.jar",
            "commons-compiler.jar",
            "commons-compiler-jdk.jar",
            "starfarer.api.jar",
            "lwjgl.jar",
            "lwjgl_util.jar",
            "log4j-1.2.9.jar");

    /** Verdict of {@link #classpathSanity(String)}. */
    public record ClasspathSanity(boolean ok, String reason) {
        public static final ClasspathSanity OK = new ClasspathSanity(true, "");

        public static ClasspathSanity fail(String reason) {
            return new ClasspathSanity(false, reason == null ? "broken classpath" : reason);
        }
    }

    private static final Pattern COOP_PROPERTY = Pattern.compile("-Dcoop\\.\\S+");

    private CoopVmparamsText() {
    }

    /** True when the file has a {@code -classpath} at all. A file without one is not a vmparams. */
    public static boolean hasClasspath(String text) {
        return classpathValue(text) != null;
    }

    /**
     * Everything after {@code " -classpath "}, or {@code null} when the marker is absent. Includes
     * the main class at the end; callers only look at the front.
     */
    public static String classpathValue(String text) {
        if (text == null) {
            return null;
        }
        int index = text.indexOf(CLASSPATH_MARKER);
        if (index < 0) {
            return null;
        }
        return text.substring(index + CLASSPATH_MARKER.length());
    }

    /** File name of the forked-classes jar, which is what an entry is recognised by. */
    private static final String FORKS_JAR = "coop-forks.jar";

    /**
     * True when the first classpath entry is the forks jar. Forward slashes and letter case are
     * tolerated - the JVM accepts both on Windows, so refusing them would report a working install
     * as broken - and so is any path that leads to the jar: an absolute
     * {@code K:\Starsector\mods\coop\jars\coop-forks.jar} loads exactly the same classes as the
     * relative spelling INSTALL.md gives, and reporting it as missing blocked Launch on a working
     * install.
     */
    public static boolean hasForksFirstOnClasspath(String text) {
        String first = firstClasspathEntry(text);
        return first != null && isForksEntry(first);
    }

    /**
     * True when the forks entry is on the classpath but not at the front. Worth its own answer: the
     * fix is different (move it, do not add a second copy) and the symptom is identical to it being
     * absent.
     */
    public static boolean hasForksLaterOnClasspath(String text) {
        List<String> entries = classpathEntries(text);
        for (int i = 1; i < entries.size(); i++) {
            if (isForksEntry(entries.get(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The first {@code -classpath} entry as it is written, quotes stripped and trimmed, or
     * {@code null} when the file has no classpath at all.
     *
     * <p>Whoever resolves this into a real path has to do it against
     * {@code <install>\starsector-core}, not the install root: {@code starsector.exe} starts the JVM
     * with that as its working directory, which is why every stock entry
     * ({@code janino.jar}, {@code starfarer.api.jar}) is a bare file name and why the entry this
     * launcher writes starts with {@code ..\}.
     */
    public static String firstClasspathEntry(String text) {
        List<String> entries = classpathEntries(text);
        if (entries.isEmpty()) {
            return null;
        }
        String first = entries.get(0).trim();
        // A tuning pack can emit `"G:\...\coop-forks.jar" janino.jar` with a space instead of `;`.
        // Extract the leading quoted token so forks detection still recognises the first entry while
        // classpathSanity() flags the broken quoting/space.
        if (first.startsWith("\"")) {
            int closing = first.indexOf('"', 1);
            if (closing > 1) {
                first = first.substring(0, closing + 1);
            }
        }
        return unquote(first);
    }

    /**
     * Structural integrity check for the {@code -classpath} segment of {@code vmparams}. Catches
     * corruption left by third-party tuning packs: embedded double quotes, whitespace splitting the
     * classpath before the main class, absolute drive/UNC paths, non-{@code .jar} entries (such as
     * a path to {@code vmparams} itself), and missing required stock jars.
     */
    public static ClasspathSanity classpathSanity(String text) {
        String raw = classpathValue(text);
        if (raw == null) {
            return ClasspathSanity.fail("vmparams has no \" -classpath \" entry");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return ClasspathSanity.fail("classpath value is empty");
        }
        // Strip the trailing main-class token (last whitespace-separated token if it is not a .jar).
        int lastSpace = trimmed.lastIndexOf(' ');
        String cpPart = lastSpace > 0 ? trimmed.substring(0, lastSpace).trim() : trimmed;
        String tailToken = lastSpace > 0 ? trimmed.substring(lastSpace + 1).trim() : "";
        if (!tailToken.isEmpty() && tailToken.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            cpPart = trimmed;
        }

        if (cpPart.indexOf('"') >= 0) {
            return ClasspathSanity.fail(
                    "classpath contains double quotes (breaks launch when install path has spaces)");
        }
        for (int i = 0; i < cpPart.length(); i++) {
            if (Character.isWhitespace(cpPart.charAt(i))) {
                return ClasspathSanity.fail(
                        "classpath contains whitespace before the main class (splits JVM arguments)");
            }
        }

        String[] tokens = cpPart.split(";", -1);
        java.util.Set<String> bareNames = new java.util.HashSet<>();
        for (String token : tokens) {
            String entry = token.trim();
            if (entry.isEmpty()) {
                return ClasspathSanity.fail("classpath contains an empty entry");
            }
            if (isAbsolutePathEntry(entry)) {
                return ClasspathSanity.fail("classpath contains an absolute path: " + entry);
            }
            String norm = normalise(entry);
            if (!norm.endsWith(".jar")) {
                return ClasspathSanity.fail("classpath contains a non-jar entry: " + entry);
            }
            int slash = norm.lastIndexOf('\\');
            bareNames.add(slash >= 0 ? norm.substring(slash + 1) : norm);
        }

        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED_STOCK_JARS) {
            if (!bareNames.contains(required.toLowerCase(java.util.Locale.ROOT))) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            return ClasspathSanity.fail("classpath is missing required stock jar(s): "
                    + String.join(", ", missing));
        }
        return ClasspathSanity.OK;
    }

    /** Extracts the main-class token at the end of {@code vmparams}, or {@link #DEFAULT_MAIN_CLASS}. */
    static String extractMainClass(String text) {
        String raw = classpathValue(text);
        if (raw == null) {
            return DEFAULT_MAIN_CLASS;
        }
        String trimmed = raw.trim();
        int lastSpace = trimmed.lastIndexOf(' ');
        if (lastSpace > 0 && lastSpace < trimmed.length() - 1) {
            String candidate = trimmed.substring(lastSpace + 1).trim();
            if (!candidate.isEmpty()
                    && !candidate.contains(";")
                    && !candidate.contains("\"")
                    && !candidate.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
                return candidate;
            }
        }
        return DEFAULT_MAIN_CLASS;
    }

    private static boolean isAbsolutePathEntry(String entry) {
        if (entry.startsWith("\\\\") || entry.startsWith("//")) {
            return true;
        }
        return entry.length() >= 3
                && Character.isLetter(entry.charAt(0))
                && entry.charAt(1) == ':'
                && (entry.charAt(2) == '\\' || entry.charAt(2) == '/');
    }

    /**
     * The {@code -classpath} value split into entries. The last one still carries the main class
     * after a space, which no caller here looks at.
     */
    private static List<String> classpathEntries(String text) {
        String value = classpathValue(text);
        if (value == null) {
            return List.of();
        }
        return List.of(value.split(";", -1));
    }

    /** True when one classpath entry, however it is spelled, points at the forks jar. */
    static boolean isForksEntry(String entry) {
        String normalised = unquote(normalise(entry).trim());
        return normalised.equals(FORKS_JAR) || normalised.endsWith("\\" + FORKS_JAR);
    }

    /** One classpath entry with its surrounding double quotes taken off, if it had any. */
    private static String unquote(String value) {
        if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * Every {@code -Dcoop.*} token on the line, in the order they appear. Empty on a clean file.
     * These are a warning, not a failure: they still launch a working game, they just win over the
     * settings file.
     */
    public static List<String> staleCoopProperties(String text) {
        if (text == null) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        Matcher matcher = COOP_PROPERTY.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return Collections.unmodifiableList(found);
    }

    /** The exact edit to make, shown verbatim in the launcher's fix column. */
    public static String forksFixText() {
        return "Open <install>\\vmparams in a text editor, find \" -classpath \" and paste"
                + " \"" + FORKS_ENTRY + "\" immediately after it, semicolon included."
                + " See docs/player/INSTALL.md section 3.";
    }

    /** The fix for a leftover {@code -Dcoop.*}. */
    public static String stalePropertyFixText(List<String> properties) {
        return "Delete " + String.join(" ", properties) + " from <install>\\vmparams."
                + " A -D value wins over the settings file, so the launcher cannot change it.";
    }

    private static String normalise(String value) {
        return value.replace('/', '\\').toLowerCase(java.util.Locale.ROOT);
    }
}
