package coop.launcher;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Objects;

/**
 * Where everything is, relative to one Starsector install root.
 *
 * <p>Discovery starts from the launcher jar's own location. The jar ships at
 * {@code <install>/mods/<modFolder>/jars/coop-launcher.jar}, so the mod folder is two levels up and
 * the install root is three. That answer is right on every ordinary install and does not depend on
 * the mod folder being named {@code coop} - only the {@code enabled_mods.json} row does, and that is
 * a check, not a path.
 *
 * <p>When the jar is not where it should be (run from a build directory, unpacked somewhere odd),
 * {@link #discover()} returns {@code null} and the app asks the player to point at the install. That
 * choice is deliberately not remembered anywhere: the launcher writes nothing outside
 * {@code saves/common} and its own log.
 */
public final class CoopInstallLayout {

    /** Folder name the mod is expected to use; only the enabled-mods check depends on it. */
    public static final String MOD_ID = "coop";

    /** Optional Wingman Project mod id and folder name for manual co-op battle piloting. */
    public static final String WINGMAN_MOD_ID = "wingman_project";
    public static final String WINGMAN_FOLDER = "Wingman";

    private final File installRoot;
    private final File modRoot;

    private CoopInstallLayout(File installRoot, File modRoot) {
        this.installRoot = Objects.requireNonNull(installRoot, "installRoot");
        this.modRoot = Objects.requireNonNull(modRoot, "modRoot");
    }

    /**
     * The layout implied by the running launcher jar, or {@code null} when the jar is not sitting in
     * {@code <install>/mods/<something>/jars}.
     */
    public static CoopInstallLayout discover() {
        File jar = ownJarOrClassesDir();
        if (jar == null) {
            return null;
        }
        File jarsDir = jar.isDirectory() ? jar : jar.getParentFile();
        if (jarsDir == null) {
            return null;
        }
        File mod = jarsDir.getParentFile();
        if (mod == null) {
            return null;
        }
        File mods = mod.getParentFile();
        if (mods == null) {
            return null;
        }
        File root = mods.getParentFile();
        if (root == null || !looksLikeInstall(root)) {
            return null;
        }
        return new CoopInstallLayout(root, mod);
    }

    /**
     * The layout for an install root the player picked by hand. The mod folder is assumed to be
     * {@code mods/coop} there, because a hand-picked root carries no other clue.
     */
    public static CoopInstallLayout ofInstallRoot(File installRoot) {
        Objects.requireNonNull(installRoot, "installRoot");
        return new CoopInstallLayout(installRoot, new File(new File(installRoot, "mods"), MOD_ID));
    }

    /** Test seam: both halves given explicitly. */
    static CoopInstallLayout of(File installRoot, File modRoot) {
        return new CoopInstallLayout(installRoot, modRoot);
    }

    /** The three files that make a folder recognisable as a Starsector install. */
    public static boolean looksLikeInstall(File candidate) {
        if (candidate == null) {
            return false;
        }
        return new File(candidate, "starsector.exe").isFile()
                && new File(candidate, "vmparams").isFile()
                && new File(candidate, "starsector-core").isDirectory();
    }

    /**
     * Resolves a player-selected path (either {@code starsector.exe}, the install root folder, or
     * {@code starsector-core}) into the Starsector install root, or {@code null} if it is not a
     * valid Starsector install.
     */
    public static File resolveInstallRoot(File selected) {
        if (selected == null) {
            return null;
        }
        if (selected.isFile()) {
            String name = selected.getName();
            if ("starsector.exe".equalsIgnoreCase(name) || "vmparams".equalsIgnoreCase(name)) {
                File parent = selected.getParentFile();
                return looksLikeInstall(parent) ? parent : null;
            }
            return null;
        }
        if (looksLikeInstall(selected)) {
            return selected;
        }
        if ("starsector-core".equalsIgnoreCase(selected.getName())
                || "mods".equalsIgnoreCase(selected.getName())) {
            File parent = selected.getParentFile();
            if (looksLikeInstall(parent)) {
                return parent;
            }
        }
        return null;
    }

    /**
     * Best-guess existing Starsector install folder on Windows for pre-positioning the file picker,
     * or {@code null} if none of the standard locations exist.
     */
    public static File detectCommonInstallDir() {
        String[] candidates = {
                "C:\\Program Files (x86)\\Fractal Softworks\\Starsector",
                "C:\\Program Files\\Fractal Softworks\\Starsector",
                "C:\\Starsector",
                "D:\\Starsector",
                "D:\\Games\\Starsector",
                "E:\\Starsector",
                "G:\\Starsector"
        };
        for (String path : candidates) {
            File dir = new File(path);
            if (looksLikeInstall(dir)) {
                return dir;
            }
        }
        return null;
    }

    public File installRoot() {
        return installRoot;
    }

    public File modRoot() {
        return modRoot;
    }

    public File starsectorExe() {
        return new File(installRoot, "starsector.exe");
    }

    public File jreDir() {
        return new File(installRoot, "jre");
    }

    public File javaw() {
        return new File(new File(jreDir(), "bin"), "javaw.exe");
    }

    public File starsectorCore() {
        return new File(installRoot, "starsector-core");
    }

    public File vmparams() {
        return new File(installRoot, "vmparams");
    }

    public File enabledMods() {
        return new File(new File(installRoot, "mods"), "enabled_mods.json");
    }

    public File modInfo() {
        return new File(modRoot, "mod_info.json");
    }

    public File coopJar() {
        return new File(new File(modRoot, "jars"), "coop.jar");
    }

    public File forksJar() {
        return new File(new File(modRoot, "jars"), "coop-forks.jar");
    }

    public File launcherJar() {
        return new File(new File(modRoot, "jars"), "coop-launcher.jar");
    }

    /** True when the core mod files are present under {@link #modRoot()}. */
    public boolean isModInstalled() {
        return modInfo().isFile() && coopJar().isFile() && forksJar().isFile();
    }

    /** Optional Wingman Project mod directory ({@code <install>/mods/Wingman}). */
    public File wingmanModRoot() {
        return new File(new File(installRoot, "mods"), WINGMAN_FOLDER);
    }

    /** True when The Wingman Project is installed under {@code mods/Wingman}. */
    public boolean isWingmanInstalled() {
        File root = wingmanModRoot();
        return new File(root, "mod_info.json").isFile()
                && new File(new File(root, "jars"), "Wingman.jar").isFile();
    }

    /** True when Starsector Russian translation has been applied (strategic RC9 installer or overlay). */
    public boolean isRussianInstalled() {
        return new File(new File(installRoot, ".starsector-ru-strategic"), "active.json").isFile()
                || new File(new File(starsectorCore(), "data"), "strings.en-backup").isDirectory();
    }

    /** The folder holding {@code common} and every {@code save_*}. */
    public File saves() {
        return new File(installRoot, "saves");
    }

    /**
     * The settings file the launcher writes. The engine appends {@code .data} to every
     * {@code saves/common} name, which is why the file on disk carries that suffix.
     */
    public File coopOptions() {
        return new File(new File(saves(), "common"), "coop_options.json.data");
    }

    public File starsectorLog() {
        return new File(starsectorCore(), "starsector.log");
    }

    /**
     * The engine's own jar. Its modification time is the only timestamp on disk that moves when the
     * player updates Starsector in place, which is what lets the launcher notice that
     * {@code starsector.log} still names the version before the update.
     */
    public File starfarerObfJar() {
        return new File(starsectorCore(), "starfarer_obf.jar");
    }

    /**
     * The API jar, used as the update timestamp when {@code starfarer_obf.jar} is not there. Both
     * ship in every build and both are rewritten by an update; the obfuscated one is the engine
     * itself, so it is asked first.
     */
    public File starfarerApiJar() {
        return new File(starsectorCore(), "starfarer.api.jar");
    }

    public File launcherLog() {
        return new File(modRoot, "coop-launcher.log");
    }

    public File installDoc() {
        return new File(new File(new File(modRoot, "docs"), "player"), "INSTALL.md");
    }

    @Override
    public String toString() {
        return "install=" + installRoot + " mod=" + modRoot;
    }

    private static File ownJarOrClassesDir() {
        try {
            ProtectionDomain domain = CoopInstallLayout.class.getProtectionDomain();
            CodeSource source = domain == null ? null : domain.getCodeSource();
            URL location = source == null ? null : source.getLocation();
            if (location == null) {
                return null;
            }
            URI uri = location.toURI();
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }
            return new File(uri);
        } catch (Exception ex) {
            return null;
        }
    }
}
