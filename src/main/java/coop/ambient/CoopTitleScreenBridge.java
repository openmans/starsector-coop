package coop.ambient;

import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/**
 * System-classloader bridge for the Starsector Title Screen and desktop clipboard/preferences.
 *
 * <p>Lives in {@code coop-forks.jar} (outside Starsector's reflection-restricted script
 * classloader) so {@code coop.jar} can safely attach a native green CO-OP button to
 * {@code TitleScreenState.screenPanel}, query {@code isShowingDialog()}, read/write the OS
 * clipboard, and share the RU/EN language preference with {@code coop-launcher.jar}.
 *
 * <p>Every reflection lookup is lazy, cached, and guarded so any unexpected engine update
 * degrades cleanly to {@code null} / {@code false} without ever affecting gameplay.
 */
public final class CoopTitleScreenBridge {

    private static final String APP_DRIVER_CLASS = "com.fs.state.AppDriver";
    private static final String TITLE_STATE_CLASS = "com.fs.starfarer.title.TitleScreenState";
    private static final String PREF_NODE = "/coop/launcher";
    private static final String PREF_LANG_KEY = "launcher.lang";
    private static final String PREF_NAME_KEY = "launcher.playerName";
    public static final String SHARED_SAVE_DIR_NAME = "save_coop_shared";
    public static final String PLAYER_VAULT_DIR_NAME = "coop_vault";
    private static final int MAX_SHARED_SAVE_ZIP_BYTES = 32 * 1024 * 1024;

    private static volatile boolean handlesResolved;
    private static volatile MethodHandle appDriverGetInstance;
    private static volatile MethodHandle appDriverGetCurrentState;
    private static volatile Class<?> titleStateClass;
    private static volatile VarHandle titleScreenPanelField;
    private static volatile VarHandle titleWidgetField;
    private static volatile VarHandle titleSessionField;
    private static volatile MethodHandle titleIsShowingDialog;
    private static volatile MethodHandle titleMenuItemSelected;
    private static volatile Object titleContinueAction;
    private static volatile MethodHandle titleShowNewGameDialog;
    private static volatile MethodHandle titleShowLoadGameDialog;
    private static volatile MethodHandle titleGoToState;

    private CoopTitleScreenBridge() {
    }

    private static void ensureHandles() {
        if (handlesResolved) {
            return;
        }
        synchronized (CoopTitleScreenBridge.class) {
            if (handlesResolved) {
                return;
            }
            try {
                ClassLoader loader = CoopTitleScreenBridge.class.getClassLoader();
                Class<?> appDriver = Class.forName(APP_DRIVER_CLASS, false, loader);
                MethodHandles.Lookup pub = MethodHandles.publicLookup();
                appDriverGetInstance = pub.findStatic(appDriver, "getInstance",
                        MethodType.methodType(appDriver));
                Class<?> appState = Class.forName("com.fs.state.AppState", false, loader);
                appDriverGetCurrentState = pub.findVirtual(appDriver, "getCurrentState",
                        MethodType.methodType(appState));

                Class<?> titleCls = Class.forName(TITLE_STATE_CLASS, false, loader);
                titleStateClass = titleCls;
                MethodHandles.Lookup priv = MethodHandles.privateLookupIn(titleCls, MethodHandles.lookup());
                titleScreenPanelField = findFieldHandle(priv, loader, titleCls, "screenPanel", "com.fs.starfarer.ui.z");
                titleWidgetField = findFieldHandle(priv, loader, titleCls, "titleWidget", "com.fs.starfarer.title.B");
                try {
                    titleSessionField = priv.findVarHandle(titleCls, "session", java.util.Map.class);
                } catch (Throwable ignored) {
                }
                titleIsShowingDialog = pub.findVirtual(titleCls, "isShowingDialog",
                        MethodType.methodType(boolean.class));
                try {
                    titleGoToState = pub.findVirtual(titleCls, "goToState",
                            MethodType.methodType(void.class, String.class));
                } catch (Throwable ignored) {
                }
                try {
                    Class<?> menuActionCls = Class.forName("com.fs.starfarer.title.C$o$o", false, loader);
                    Object[] constants = menuActionCls.getEnumConstants();
                    if (constants != null && constants.length > 0) {
                        titleContinueAction = constants[0]; // OO0000 -> case 1 (Continue)
                        titleMenuItemSelected = pub.findVirtual(titleCls, "menuItemSelected",
                                MethodType.methodType(void.class, menuActionCls));
                    }
                } catch (Throwable ignored) {
                }
                try {
                    titleShowNewGameDialog = priv.findSpecial(titleCls, "showNewGameDialog",
                            MethodType.methodType(void.class), titleCls);
                } catch (Throwable ignored) {
                }
                try {
                    titleShowLoadGameDialog = priv.findSpecial(titleCls, "showLoadGameDialog",
                            MethodType.methodType(void.class), titleCls);
                } catch (Throwable ignored) {
                }
            } catch (Throwable ignored) {
                // Headless tests or unknown engine version: degrade silently.
            } finally {
                handlesResolved = true;
            }
        }
    }

    private static VarHandle findFieldHandle(MethodHandles.Lookup priv, ClassLoader loader,
                                             Class<?> owner, String name, String fieldTypeName) {
        try {
            Class<?> fieldType = Class.forName(fieldTypeName, false, loader);
            return priv.findVarHandle(owner, name, fieldType);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object currentTitleState() {
        ensureHandles();
        if (appDriverGetInstance == null || appDriverGetCurrentState == null || titleStateClass == null) {
            return null;
        }
        try {
            Object driver = appDriverGetInstance.invoke();
            if (driver == null) {
                return null;
            }
            Object state = appDriverGetCurrentState.invoke(driver);
            if (state != null && titleStateClass.isInstance(state)) {
                return state;
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    /** Returns the live {@code TitleScreenState.screenPanel} when on the Title Screen, else {@code null}. */
    public static UIPanelAPI currentTitleScreenPanel() {
        Object state = currentTitleState();
        if (state == null || titleScreenPanelField == null) {
            return null;
        }
        try {
            Object panel = titleScreenPanelField.get(state);
            return panel instanceof UIPanelAPI uiPanel ? uiPanel : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Returns {@code true} when a vanilla Title Screen modal dialog (Load/New/Settings/Codex) is open. */
    public static boolean isTitleScreenShowingDialog() {
        Object state = currentTitleState();
        if (state == null || titleIsShowingDialog == null) {
            return false;
        }
        try {
            return (boolean) titleIsShowingDialog.invoke(state);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Programmatically triggers entering the game from the Title Screen when the pre-game lobby
     * starts: calls Starsector's Continue action if at least one save exists, otherwise opens the
     * New Game dialog (with the synced seed already armed).
     */
    public static boolean enterGameFromTitleScreen() {
        Object state = currentTitleState();
        if (state == null) {
            return false;
        }
        try {
            if (hasAnySaveGame() && titleMenuItemSelected != null && titleContinueAction != null) {
                titleMenuItemSelected.invoke(state, titleContinueAction);
                return true;
            }
            if (titleShowNewGameDialog != null) {
                titleShowNewGameDialog.invoke(state);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Opens Starsector's Load Game screen directly from the Title Screen. */
    public static boolean openLoadGameDialog() {
        Object state = currentTitleState();
        if (state == null) {
            return false;
        }
        try {
            if (titleShowLoadGameDialog != null) {
                titleShowLoadGameDialog.invoke(state);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Loads a specific save folder path directly from the Title Screen (reproducing vanilla's
     * {@code TitleScreenState.dialogDismissed} LoadGameDialog path without user interaction).
     */
    public static boolean loadSaveFromTitleScreen(String saveDirPath) {
        if (saveDirPath == null || saveDirPath.trim().isEmpty()) {
            return false;
        }
        Object state = currentTitleState();
        if (state == null || titleSessionField == null || titleGoToState == null) {
            return false;
        }
        try {
            java.io.File dir = new java.io.File(saveDirPath.trim());
            if (!dir.isDirectory() || !new java.io.File(dir, "campaign.xml").isFile()) {
                return false;
            }
            Object sessionObj = titleSessionField.get(state);
            if (!(sessionObj instanceof java.util.Map<?, ?> sessionMap)) {
                return false;
            }
            Object campaignState = sessionMap.get("campaign state in session");
            if (campaignState == null) {
                return false;
            }
            ClassLoader loader = CoopTitleScreenBridge.class.getClassLoader();
            MethodHandles.Lookup pub = MethodHandles.publicLookup();
            Class<?> ceCls = Class.forName("com.fs.starfarer.campaign.CampaignEngine", false, loader);
            MethodHandle resetInstance = pub.findStatic(ceCls, "resetInstance", MethodType.methodType(void.class));
            resetInstance.invoke();

            Class<?> cgmCls = Class.forName("com.fs.starfarer.campaign.save.CampaignGameManager", false, loader);
            Class<?> cbIface = null;
            Class<?> progressIface = null;
            for (Class<?> iface : campaignState.getClass().getInterfaces()) {
                if (iface.getName().endsWith(".CampaignEngine$o")) {
                    progressIface = iface;
                } else if (iface.getName().startsWith("com.fs.starfarer.campaign.oOoO")) {
                    cbIface = iface;
                }
            }
            if (cbIface == null || progressIface == null) {
                return false;
            }
            MethodHandle loadMethod = pub.findStatic(cgmCls, "super",
                    MethodType.methodType(String.class, String.class, cbIface, progressIface));
            String err = (String) loadMethod.invoke(dir.getAbsolutePath(), campaignState, campaignState);
            if (err == null) {
                titleGoToState.invoke(state, "Campaign State");
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Finds the newest non-shared {@code save_*} directory containing a valid {@code campaign.xml}
     * for the host to broadcast, falling back to any {@code save_*} only if nothing else exists.
     */
    public static String findLatestHostSaveDirPath() {
        String primary = findLatestSaveDirInternal(false);
        if (!primary.isEmpty()) {
            return primary;
        }
        return findLatestSaveDirInternal(true);
    }

    /** Finds the newest {@code save_*} directory including {@code save_coop_*} guest save folders. */
    public static String findLatestLocalSaveDirPath() {
        return findLatestSaveDirInternal(true);
    }

    private static String findLatestSaveDirInternal(boolean includeCoopFolders) {
        try {
            java.io.File[] dirs = {
                    new java.io.File("../saves"),
                    new java.io.File("saves")
            };
            java.io.File bestDir = null;
            long bestModified = Long.MIN_VALUE;
            for (java.io.File savesDir : dirs) {
                if (!savesDir.isDirectory()) {
                    continue;
                }
                java.io.File[] entries = savesDir.listFiles();
                if (entries == null) {
                    continue;
                }
                for (java.io.File f : entries) {
                    if (f == null || !f.isDirectory() || !f.getName().startsWith("save_")) {
                        continue;
                    }
                    if (!includeCoopFolders && f.getName().startsWith("save_coop_")) {
                        continue;
                    }
                    java.io.File campaignXml = new java.io.File(f, "campaign.xml");
                    java.io.File descriptorXml = new java.io.File(f, "descriptor.xml");
                    if (!campaignXml.isFile() || !descriptorXml.isFile()) {
                        continue;
                    }
                    long mod = campaignXml.lastModified();
                    if (mod > bestModified) {
                        bestModified = mod;
                        bestDir = f;
                    }
                }
            }
            return bestDir == null ? "" : bestDir.getCanonicalPath();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Sanitizes a lobby player name into a safe filesystem key (supports Latin & Cyrillic). */
    public static String sanitizePlayerVaultKey(String playerName) {
        String raw = playerName == null ? "" : playerName.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        boolean prevUnderscore = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-') {
                sb.append(c);
                prevUnderscore = false;
            } else if (!prevUnderscore && sb.length() > 0) {
                sb.append('_');
                prevUnderscore = true;
            }
        }
        String key = sb.toString();
        while (key.startsWith("_")) {
            key = key.substring(1);
        }
        while (key.endsWith("_")) {
            key = key.substring(0, key.length() - 1);
        }
        if (key.length() > 48) {
            key = key.substring(0, 48);
            while (key.endsWith("_")) {
                key = key.substring(0, key.length() - 1);
            }
        }
        return key.isEmpty() ? "guest" : key;
    }

    private static java.io.File resolveSavesRootDir() {
        java.io.File parentSaves = new java.io.File("../saves");
        if (parentSaves.isDirectory()) {
            return parentSaves;
        }
        java.io.File localSaves = new java.io.File("saves");
        if (localSaves.isDirectory()) {
            return localSaves;
        }
        if (parentSaves.mkdirs()) {
            return parentSaves;
        }
        localSaves.mkdirs();
        return localSaves;
    }

    /** Stores a guest's personal save ZIP inside the host's {@code coop_vault} by lobby player name. */
    public static boolean storePlayerSaveZip(String playerName, byte[] zipBytes) {
        if (zipBytes == null || zipBytes.length == 0 || zipBytes.length > MAX_SHARED_SAVE_ZIP_BYTES) {
            return false;
        }
        try {
            java.io.File savesDir = resolveSavesRootDir();
            if (!savesDir.isDirectory()) {
                return false;
            }
            java.io.File vaultDir = new java.io.File(savesDir, PLAYER_VAULT_DIR_NAME);
            if (!vaultDir.isDirectory() && !vaultDir.mkdirs()) {
                return false;
            }
            String key = sanitizePlayerVaultKey(playerName);
            java.io.File tmp = new java.io.File(vaultDir, key + ".zip.tmp");
            java.io.File dest = new java.io.File(vaultDir, key + ".zip");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
                fos.write(zipBytes);
            }
            if (dest.exists()) {
                dest.delete();
            }
            if (!tmp.renameTo(dest)) {
                byte[] buf = new byte[16 * 1024];
                try (java.io.FileInputStream in = new java.io.FileInputStream(tmp);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
                tmp.delete();
            }
            return dest.isFile() && dest.length() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Loads a stored personal save ZIP for {@code playerName} from {@code coop_vault}, or {@code null}. */
    public static byte[] loadPlayerSaveZip(String playerName) {
        try {
            java.io.File savesDir = resolveSavesRootDir();
            java.io.File vaultFile = new java.io.File(new java.io.File(savesDir, PLAYER_VAULT_DIR_NAME),
                    sanitizePlayerVaultKey(playerName) + ".zip");
            if (!vaultFile.isFile() || vaultFile.length() <= 0 || vaultFile.length() > MAX_SHARED_SAVE_ZIP_BYTES) {
                return null;
            }
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream((int) vaultFile.length());
            byte[] buf = new byte[16 * 1024];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(vaultFile)) {
                int n;
                while ((n = fis.read(buf)) > 0) {
                    baos.write(buf, 0, n);
                }
            }
            byte[] out = baos.toByteArray();
            return out.length == 0 ? null : out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** True when the host's {@code coop_vault} currently holds a personal save for {@code playerName}. */
    public static boolean hasPlayerSaveZip(String playerName) {
        try {
            java.io.File savesDir = resolveSavesRootDir();
            java.io.File vaultFile = new java.io.File(new java.io.File(savesDir, PLAYER_VAULT_DIR_NAME),
                    sanitizePlayerVaultKey(playerName) + ".zip");
            return vaultFile.isFile() && vaultFile.length() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Compresses {@code campaign.xml} and {@code descriptor.xml} from {@code saveDirPath} into a ZIP. */
    public static byte[] packageSaveDirectoryAsZip(String saveDirPath) {
        if (saveDirPath == null || saveDirPath.trim().isEmpty()) {
            return null;
        }
        try {
            java.io.File dir = new java.io.File(saveDirPath.trim());
            if (!dir.isDirectory()) {
                return null;
            }
            String[] names = { "campaign.xml", "descriptor.xml" };
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(256 * 1024);
            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
                zos.setLevel(6);
                byte[] buf = new byte[16 * 1024];
                boolean wroteAny = false;
                for (String name : names) {
                    java.io.File src = new java.io.File(dir, name);
                    if (!src.isFile()) {
                        continue;
                    }
                    zos.putNextEntry(new java.util.zip.ZipEntry(name));
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(src)) {
                        int n;
                        while ((n = fis.read(buf)) > 0) {
                            zos.write(buf, 0, n);
                        }
                    }
                    zos.closeEntry();
                    wroteAny = true;
                }
                if (!wroteAny) {
                    return null;
                }
            }
            byte[] zip = baos.toByteArray();
            if (zip.length == 0 || zip.length > MAX_SHARED_SAVE_ZIP_BYTES) {
                return null;
            }
            return zip;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Extracts a shared host save ZIP into {@code <saves>/save_coop_shared/} safely (only
     * {@code campaign.xml} and {@code descriptor.xml}). Returns the extracted directory path or {@code ""}.
     */
    public static String extractSharedSaveZip(byte[] zipBytes) {
        return extractPlayerSaveZip("", zipBytes);
    }

    /**
     * Extracts a downloaded co-op save ZIP into {@code <saves>/save_coop_<key>/} (or
     * {@code save_coop_shared} when {@code playerName} is blank).
     */
    public static String extractPlayerSaveZip(String playerName, byte[] zipBytes) {
        if (zipBytes == null || zipBytes.length == 0 || zipBytes.length > MAX_SHARED_SAVE_ZIP_BYTES) {
            return "";
        }
        try {
            java.io.File savesDir = resolveSavesRootDir();
            if (!savesDir.isDirectory()) {
                return "";
            }
            String folderName = (playerName == null || playerName.trim().isEmpty())
                    ? SHARED_SAVE_DIR_NAME
                    : "save_coop_" + sanitizePlayerVaultKey(playerName);
            java.io.File targetDir = new java.io.File(savesDir, folderName);
            if (!targetDir.isDirectory() && !targetDir.mkdirs()) {
                return "";
            }
            boolean hasCampaign = false;
            boolean hasDescriptor = false;
            byte[] buf = new byte[16 * 1024];
            try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                    new java.io.ByteArrayInputStream(zipBytes))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (!"campaign.xml".equals(name) && !"descriptor.xml".equals(name)) {
                        zis.closeEntry();
                        continue;
                    }
                    java.io.File tmp = new java.io.File(targetDir, name + ".tmp");
                    java.io.File dest = new java.io.File(targetDir, name);
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
                        int n;
                        while ((n = zis.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                        }
                    }
                    if (dest.exists()) {
                        dest.delete();
                    }
                    if (!tmp.renameTo(dest)) {
                        try (java.io.FileInputStream in = new java.io.FileInputStream(tmp);
                             java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                out.write(buf, 0, n);
                            }
                        }
                        tmp.delete();
                    }
                    if ("campaign.xml".equals(name)) hasCampaign = true;
                    if ("descriptor.xml".equals(name)) hasDescriptor = true;
                    zis.closeEntry();
                }
            }
            if (!hasCampaign || !hasDescriptor) {
                return "";
            }
            return targetDir.getCanonicalPath();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean hasAnySaveGame() {
        try {
            java.io.File[] dirs = {
                    new java.io.File("../saves"),
                    new java.io.File("saves")
            };
            for (java.io.File savesDir : dirs) {
                if (!savesDir.isDirectory()) {
                    continue;
                }
                java.io.File[] entries = savesDir.listFiles();
                if (entries == null) {
                    continue;
                }
                for (java.io.File f : entries) {
                    if (f != null && f.isDirectory() && f.getName().startsWith("save_")) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Returns {@code [menuLeft, menuBottom, menuWidth, menuHeight]} for the vanilla {@code 250x400}
     * main menu panel inside {@code TitleWidget}, or {@code null} if unavailable.
     */
    public static float[] titleMenuBounds() {
        Object state = currentTitleState();
        if (state == null || titleWidgetField == null) {
            return null;
        }
        try {
            Object widget = titleWidgetField.get(state);
            if (widget instanceof UIComponentAPI comp) {
                PositionAPI pos = comp.getPosition();
                if (pos != null && pos.getWidth() > 0f && pos.getHeight() > 0f) {
                    float menuW = 250f;
                    float menuH = 400f;
                    float menuLeft = pos.getX() + pos.getWidth() - menuW;
                    float menuBottom = pos.getY() + (pos.getHeight() - menuH) * 0.5f;
                    return new float[] { menuLeft, menuBottom, menuW, menuH };
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    /** Reads plain text from the OS clipboard, or returns {@code ""} if unavailable. */
    public static String readClipboard() {
        try {
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (cb != null && cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                Object data = cb.getData(DataFlavor.stringFlavor);
                return data == null ? "" : data.toString();
            }
        } catch (Throwable ignored) {
            // Headless or clipboard locked by another process.
        }
        return "";
    }

    /** Copies plain text to the OS clipboard. Returns {@code true} on success. */
    public static boolean writeClipboard(String text) {
        try {
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (cb != null) {
                cb.setContents(new StringSelection(text == null ? "" : text), null);
                return true;
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    /** Returns the best non-loopback LAN IPv4 address, or {@code ""} if none is up. */
    public static String detectLocalLanAddress() {
        try {
            String fallback = "";
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface nif = ifaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress() || addr.isLinkLocalAddress()) {
                        continue;
                    }
                    String ip = addr.getHostAddress();
                    if (addr.isSiteLocalAddress()) {
                        return ip;
                    }
                    if (fallback.isEmpty()) {
                        fallback = ip;
                    }
                }
            }
            return fallback;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Resolves the public IPv4 address off-thread, falling back to LAN IPv4 if offline. */
    public static void lookupPublicIpAsync(Consumer<String> onResult) {
        if (onResult == null) {
            return;
        }
        Thread worker = new Thread(() -> {
            String found = fetchPublicIp("api.ipify.org");
            if (found.isEmpty()) {
                found = fetchPublicIp("checkip.amazonaws.com");
            }
            if (found.isEmpty()) {
                found = detectLocalLanAddress();
            }
            try {
                onResult.accept(found);
            } catch (Throwable ignored) {
            }
        }, "coop-ip-lookup");
        worker.setDaemon(true);
        worker.start();
    }

    private static String fetchPublicIp(String host) {
        try (SocketChannel ch = SocketChannel.open()) {
            ch.socket().connect(new InetSocketAddress(host, 80), 3000);
            ch.socket().setSoTimeout(3000);
            byte[] req = ("GET / HTTP/1.0\r\nHost: " + host
                    + "\r\nUser-Agent: Starsector-Coop\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
            ByteBuffer out = ByteBuffer.wrap(req);
            while (out.hasRemaining()) {
                ch.write(out);
            }
            ByteBuffer in = ByteBuffer.allocate(1024);
            StringBuilder resp = new StringBuilder();
            while (ch.read(in) > 0 && resp.length() < 2048) {
                in.flip();
                resp.append(StandardCharsets.UTF_8.decode(in));
                in.clear();
            }
            String text = resp.toString();
            int sep = text.indexOf("\r\n\r\n");
            if (sep < 0 || !text.startsWith("HTTP/1.") || !text.contains(" 200")) {
                return "";
            }
            String body = text.substring(sep + 4).trim();
            int nl = body.indexOf('\n');
            if (nl >= 0) {
                body = body.substring(0, nl).trim();
            }
            return body;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Loads the shared RU/EN preference (defaults to Russian unless EN was explicitly saved). */
    public static boolean loadPreferRussian() {
        try {
            Preferences prefs = Preferences.userRoot().node(PREF_NODE);
            String saved = prefs.get(PREF_LANG_KEY, "");
            if ("RU".equalsIgnoreCase(saved)) {
                return true;
            }
            if ("EN".equalsIgnoreCase(saved)) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** Persists the shared RU/EN preference so both in-game UI and desktop launcher stay in sync. */
    public static void savePreferRussian(boolean russian) {
        try {
            Preferences.userRoot().node(PREF_NODE).put(PREF_LANG_KEY, russian ? "RU" : "EN");
        } catch (Throwable ignored) {
        }
    }

    /** Loads the player's preferred lobby nickname (falls back to OS username). */
    public static String loadPlayerName() {
        try {
            Preferences prefs = Preferences.userRoot().node(PREF_NODE);
            String saved = prefs.get(PREF_NAME_KEY, "").trim();
            if (!saved.isEmpty()) {
                return saved;
            }
        } catch (Throwable ignored) {
        }
        try {
            String osUser = System.getProperty("user.name", "").trim();
            if (!osUser.isEmpty()) {
                return osUser;
            }
        } catch (Throwable ignored) {
        }
        return "Player";
    }

    private static final String PREF_LAST_JOIN_HOST = "lastJoinHost";
    private static final String PREF_LAST_JOIN_PORT = "lastJoinPort";

    /** Saves the player's lobby nickname across sessions. */
    public static void savePlayerName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        try {
            Preferences.userRoot().node(PREF_NODE).put(PREF_NAME_KEY, name.trim());
        } catch (Throwable ignored) {
        }
    }

    /** Loads the last used Guest host address, or {@code ""} if never saved. */
    public static String loadLastJoinHost() {
        try {
            return Preferences.userRoot().node(PREF_NODE).get(PREF_LAST_JOIN_HOST, "").trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Loads the last used Guest port, or {@code ""} if never saved. */
    public static String loadLastJoinPort() {
        try {
            return Preferences.userRoot().node(PREF_NODE).get(PREF_LAST_JOIN_PORT, "").trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Persists the last Guest host/port so one-click CONNECT works across restarts. The password is
     * deliberately not stored: a session password kept in the registry outlives the session, shows up
     * as a "secret" the player never set, and there is no way to tell a stale one from a live one.
     */
    public static void saveLastJoinSettings(String host, String port) {
        if (host == null || host.trim().isEmpty()) {
            return;
        }
        try {
            Preferences prefs = Preferences.userRoot().node(PREF_NODE);
            prefs.put(PREF_LAST_JOIN_HOST, host.trim());
            if (port != null && !port.trim().isEmpty()) {
                prefs.put(PREF_LAST_JOIN_PORT, port.trim());
            }
        } catch (Throwable ignored) {
        }
    }
}
