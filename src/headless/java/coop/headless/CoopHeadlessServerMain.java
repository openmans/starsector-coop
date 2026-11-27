package coop.headless;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Entry point of the windowless dedicated server.
 *
 * <p>Runs the real engine ({@code CombatMain}) on a classpath whose first entry is
 * {@code coop-headless-lwjgl.jar}: every {@code org.lwjgl.*} class the engine links against is a
 * shim that creates no window, loads no native library and issues no GL/AL call. Nothing here
 * reimplements simulation — the engine, the mod, XStream saves and the mod API are the real ones,
 * so a client that connects sees exactly the same world.
 *
 * <p>Dedicated-host behaviour itself (auto-load the newest save, 4 guest slots, auto-pause with
 * zero players, unpause on join, live world in combat) is the existing {@code -Dcoop.dedicatedHost}
 * path in coop.jar; this class only sets those properties before the engine boots.
 */
public final class CoopHeadlessServerMain {

    private static final String LAUNCHER_CLASS = "com.fs.starfarer.StarfarerLauncher";

    private CoopHeadlessServerMain() {
    }

    public static void main(String[] args) throws Throwable {
        // CLI wins, then the -D property (the launcher script passes them that way), then defaults.
        int port = Integer.getInteger("coop.hostPort", 7777);
        int maxGuests = Integer.getInteger("coop.maxGuests", 4);
        int tps = Integer.getInteger("coop.headlessTickRate", 30);
        String resolution = "1024x768";
        boolean headlessAwt = true;
        for (String arg : args) {
            if (arg.startsWith("--port=")) {
                port = parseInt(arg, "--port=", port);
            } else if (arg.startsWith("--max-guests=")) {
                maxGuests = parseInt(arg, "--max-guests=", maxGuests);
            } else if (arg.startsWith("--tps=")) {
                tps = parseInt(arg, "--tps=", tps);
            } else if (arg.startsWith("--res=")) {
                resolution = arg.substring("--res=".length()).trim();
            } else if (arg.equals("--awt=0")) {
                headlessAwt = false;
            }
        }
        if (port < 1 || port > 65535) {
            port = 7777;
        }
        if (maxGuests < 1 || maxGuests > 16) {
            maxGuests = 4;
        }
        if (tps < 5 || tps > 240) {
            tps = 30;
        }
        if (!resolution.matches("\\d+x\\d+")) {
            resolution = "1024x768";
        }

        System.setProperty("coop.dedicatedHost", "true");
        System.setProperty("coop.hostPort", Integer.toString(port));
        System.setProperty("coop.maxGuests", Integer.toString(maxGuests));
        System.setProperty("coop.liveWorldInCombat", "true");
        System.setProperty("coop.allowMidSessionJoin", "true");
        System.setProperty("coop.headless", "true");
        System.setProperty("coop.headlessTickRate", Integer.toString(tps));
        // The launcher's own no-window path: launchDirect + startRes skip the Swing settings window.
        System.setProperty("launchDirect", "true");
        System.setProperty("startRes", resolution);
        System.setProperty("startFS", "false");
        // The Null-LWJGL AL stubs would happily "play" without a device; asking the engine for sound
        // off keeps the mixer thread and the EFX paths out of the way entirely.
        System.setProperty("startSound", "false");
        if (headlessAwt && System.getProperty("java.awt.headless") == null) {
            // Headless AWT everywhere: the engine's Toolkit.getScreenSize probe is gone (the
            // CombatMain shim drops the launcher's three arguments), and font metrics / ImageIO are
            // fully supported by the headless graphics environment. This is what lets the same jar
            // run on a Linux host with no X server.
            System.setProperty("java.awt.headless", "true");
        }

        String[] wh = resolution.split("x");

        System.out.println("=========================================================");
        System.out.println(" Starsector Coop - headless dedicated server");
        System.out.println(" Null-LWJGL: no window, no GPU, no OpenGL/OpenAL driver");
        System.out.println(" port=" + port + " maxGuests=" + maxGuests + " tps=" + tps
                + " res=" + resolution);
        System.out.println(" commands: status | save | kick <name> | stop | help");
        System.out.println("=========================================================");
        System.out.flush();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[headless] JVM shutting down");
            System.out.flush();
        }, "coop-headless-shutdown-log"));

        Class<?> launcher = Class.forName(LAUNCHER_CLASS);
        try {
            Constructor<?> ctor = launcher.getConstructor(boolean.class);
            // false = run the launcher's normal preamble (resource root, ModManager mod scan,
            // StarfarerSettings load). With launchDirect=true the constructor then takes its
            // auto-start branch before any Swing component is built, and calls the Play-button
            // launch method, which blocks until the game exits.
            ctor.newInstance(Boolean.FALSE);
        } catch (InvocationTargetException ex) {
            System.err.println("[headless] launcher threw: " + rootMessage(ex.getCause()));
            ex.getCause().printStackTrace();
        } catch (ReflectiveOperationException ex) {
            System.err.println("[headless] could not construct the launcher: " + rootMessage(ex));
        }

        // Reaching this line means the launcher returned without starting the game. Two cases:
        //  - it declined before the auto-start branch (activation check), so mods are not on the
        //    ScriptStore yet: call the Play-button entry, which scans mods, loads settings and calls
        //    CombatMain itself;
        //  - it ran the entry and CombatMain threw on the way in (HeadlessToolkit.getScreenSize on a
        //    box without a desktop), so the setup is already done: Global.getSettings() is non-null.
        // Either way the engine is then booted with zero arguments, which skips the screen-size probe
        // and uses the 1024x768 default the display shim reports.
        try {
            if (com.fs.starfarer.api.Global.getSettings() == null) {
                System.out.println("[headless] launcher declined; running the launch entry directly");
                System.out.flush();
                invokeLaunchEntry(launcher, wh[0], wh[1]);
            }
        } catch (InvocationTargetException ex) {
            System.err.println("[headless] launch entry threw: " + rootMessage(ex.getCause()));
        }

        System.out.println("[headless] booting the engine (windowless)");
        System.out.flush();
        com.fs.starfarer.combat.CombatMain.main(new String[0]);
    }

    /**
     * {@code StarfarerLauncher.o00000(boolean fullscreen, boolean sound, String width, String height)}
     * is the Play button: it resolves enabled mods, adds their jars, loads settings, installs
     * {@code Global.setSettings}/{@code setFocusManager} and calls {@code CombatMain.main}.
     *
     * <p>Method and class names there are obfuscated, so the lookup is by exact parameter types
     * taken from the engine's own call site; if a future engine build renames the method this fails
     * loudly instead of launching something else.
     */
    private static void invokeLaunchEntry(Class<?> launcher, String width, String height) throws Throwable {
        Method launch;
        try {
            launch = launcher.getMethod("o00000", boolean.class, boolean.class, String.class, String.class);
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("StarsectorLauncher launch entry not found; the engine build "
                    + "changed and CoopHeadlessServerMain needs its reflection updated", ex);
        }
        launch.invoke(null, Boolean.FALSE, Boolean.FALSE, width, height);
    }

    private static int parseInt(String arg, String prefix, int fallback) {
        try {
            return Integer.parseInt(arg.substring(prefix.length()).trim());
        } catch (RuntimeException ex) {
            System.err.println("[headless] bad argument '" + arg + "', keeping " + fallback);
            return fallback;
        }
    }

    private static String rootMessage(Throwable ex) {
        Throwable cur = ex == null ? new IllegalStateException("unknown") : ex;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.toString();
    }
}
