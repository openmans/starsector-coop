package coop.ui;

import coop.ambient.CoopTitleScreenBridge;

/**
 * Shared in-game RU / EN language state for all Starsector Coop UI surfaces (Title Screen CO-OP
 * panel, {@code F7} campaign dialog, {@code Intel -> Coop Options}, {@code Intel -> Coop Session},
 * Lobby, Partner Comm Link, Ally Battle Reinforcement, Trade, and Chat).
 *
 * <p>Defaults to English in headless unit tests until {@link #initFromPreferences()} or
 * {@link #setRussian(boolean)} is called, while real game sessions automatically load and persist
 * the same preference used by the external launcher.
 */
public final class CoopUiLang {

    private static volatile Boolean russianOverride;

    private CoopUiLang() {
    }

    /** Loads the persisted RU / EN preference (or OS locale default) on live game startup. */
    public static void initFromPreferences() {
        if (russianOverride == null) {
            russianOverride = CoopTitleScreenBridge.loadPreferRussian();
        }
    }

    /** Returns {@code true} when Russian UI is active; defaults to {@code false} (EN) in tests. */
    public static boolean isRussian() {
        Boolean v = russianOverride;
        return v != null && v;
    }

    /** Sets the live UI language without persisting (useful for tests). */
    public static void setRussian(boolean russian) {
        russianOverride = russian;
    }

    /** Resets override back to the default test state (English). */
    public static void resetForTests() {
        russianOverride = null;
    }

    /** Toggles RU <-> EN live and persists the choice for both in-game UI and desktop launcher. */
    public static boolean toggle() {
        boolean next = !isRussian();
        russianOverride = next;
        CoopTitleScreenBridge.savePreferRussian(next);
        return next;
    }

    /** Returns {@code ru} when Russian is active, otherwise {@code en}. */
    public static String tr(String en, String ru) {
        return isRussian() ? ru : en;
    }

    /** Full button label for Intel pages and menus. */
    public static String toggleButtonLabel() {
        return isRussian() ? "Язык: RU / EN (переключить на EN)" : "Language: EN / RU (switch to RU)";
    }

    /** Compact button label for tight headers. */
    public static String shortToggleLabel() {
        return isRussian() ? "RU / EN" : "EN / RU";
    }
}
