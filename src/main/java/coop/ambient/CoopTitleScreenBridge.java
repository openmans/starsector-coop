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

    private static volatile boolean handlesResolved;
    private static volatile MethodHandle appDriverGetInstance;
    private static volatile MethodHandle appDriverGetCurrentState;
    private static volatile Class<?> titleStateClass;
    private static volatile VarHandle titleScreenPanelField;
    private static volatile VarHandle titleWidgetField;
    private static volatile MethodHandle titleIsShowingDialog;

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
                titleIsShowingDialog = pub.findVirtual(titleCls, "isShowingDialog",
                        MethodType.methodType(boolean.class));
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
}
