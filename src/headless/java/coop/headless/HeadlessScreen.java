package coop.headless;

import java.awt.Dimension;

/**
 * Stand-in screen for the engine's screen-size probe.
 *
 * <p>The launcher settings class computes the screen scale from
 * {@code Toolkit.getDefaultToolkit().getScreenSize()}. With no display (AWT headless, or Linux
 * without X) that call throws, and the engine's own catch turns it into a fatal exit. The committed
 * shim jar carries that class with the probe's six bytes rewritten to
 * {@code invokestatic HeadlessScreen.size()}, so the boot path never touches AWT's display code.
 *
 * <p>The value matters only for the scale factor: 1080p reports a scale of exactly 1.0, which is
 * what a server wants (no upscaled UI, no oversized atlases).
 */
public final class HeadlessScreen {

    /** Deliberately fixed: nothing in the server renders, so this is only a scale baseline. */
    private static final Dimension SIZE = new Dimension(1920, 1080);

    private HeadlessScreen() {
    }

    public static Dimension size() {
        return SIZE;
    }
}
