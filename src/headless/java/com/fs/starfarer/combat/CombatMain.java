package com.fs.starfarer.combat;

/**
 * Headless stand-in for the engine's entry class.
 *
 * <p>The real class lives in {@code coop-headless-lwjgl.jar} as {@code coop.headless.RealCombatMain}
 * (see {@code tools/headless/make_combatmain_shim.py}). It is byte-identical apart from its name.
 *
 * <p><b>Why this shim exists.</b> The launcher calls {@code CombatMain.main(new String[]{w, h,
 * fullscreen})}, and with three arguments the real class probes
 * {@code Toolkit.getDefaultToolkit().getScreenSize()} to decide about undecorated fullscreen. Under
 * {@code java.awt.headless}, or on a Linux host with no X server, that probe throws, and the real
 * class's own catch answers with {@code System.exit(1)} before a dedicated server can start. The
 * shim drops the arguments: the Null-LWJGL display is fixed at 1024x768 (or {@code --res=WxH}), so
 * there is no screen to probe and no window to decorate.
 */
public class CombatMain implements com.fs.util.DoNotObfuscate {

    /** Same constant the engine's class declares; inlined by javac at every use site. */
    public static final String CAMPAIGN_STATE_ID = "campaign state in session";

    /** Mirrors the engine's field; the real entry point keeps its own copy (sound uses either). */
    public static volatile boolean AL_DESTROYED = false;

    public CombatMain() {
    }

    public static void main(String[] args) throws Throwable {
        coop.headless.RealCombatMain.main(new String[0]);
    }

    public static java.nio.ByteBuffer convertImageData(java.awt.image.BufferedImage image) {
        return coop.headless.RealCombatMain.convertImageData(image);
    }
}
