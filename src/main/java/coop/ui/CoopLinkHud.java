package coop.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.campaign.listeners.CampaignUIRenderingListener;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import coop.config.CoopOptionsRegistry;
import coop.config.CoopOptionsStore;
import coop.net.CoopNetPump;
import coop.net.CoopNetStartupConfig;
import coop.util.CoopLog;
import org.lwjgl.input.Keyboard;

import java.awt.Color;
import java.util.List;

/**
 * Phase 20.6 milestone 0: a one-line, always-on campaign HUD saying which side of the link you are,
 * what the session is doing, who is holding the shared pause, and — on the guest — how far its
 * campaign clock has drifted from the host's.
 *
 * <p>Drawn in the {@code aboveUIBelowTooltips} pass so it sits over the campaign map but never over
 * a tooltip. Anchored by default under the vanilla fps/version overlay in the top-right corner;
 * {@code -Dcoop.hudCorner=TL|TR|BL|BR} moves it to any corner (see {@link CoopHudCorner}).
 *
 * <p><b>Failure policy.</b> This is cosmetic; nothing it does may ever cost a frame. Every engine
 * touch runs under {@code catch (Throwable)}, and the first failure disables the instance for good
 * after exactly one log line — a HUD that stops drawing is a nuisance, a HUD that throws sixty times
 * a second in the render pass is a crash. {@code coop.hud.disable=true}, on the command line, in
 * {@code saves/common/coop_options.json.data} or on the options page, hides the line; the corner and that
 * flag are both re-read on the refresh tick, so a change from the page applies without a relaunch.
 */
public final class CoopLinkHud implements CampaignUIRenderingListener, CampaignInputListener {

    /** Hotkey to reopen a minimized/closed partner dialog spectator window. */
    public static final int REJOIN_DIALOG_KEY = Keyboard.KEY_F6;

    /** Bounding box of the clickable top-center "Rejoin dialog" HUD pill in UI coordinates. */
    public record PillRect(float x, float y, float w, float h) {
        public boolean contains(float mx, float my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    /** Currently rendered clickable rejoin pill (null whenever hidden or a dialog/menu is open). */
    private static volatile PillRect activeRejoinPillRect;

    public static boolean hasActiveRejoinPill() {
        return activeRejoinPillRect != null;
    }

    public static boolean isPointInRejoinPill(float x, float y) {
        PillRect rect = activeRejoinPillRect;
        return rect != null && rect.contains(x, y);
    }

    public static void setActiveRejoinPillForTest(PillRect rect) {
        activeRejoinPillRect = rect;
    }

    /**
     * Client preference: when true the line is not drawn. Registered in
     * {@link CoopOptionsRegistry#HUD_DISABLE} and therefore settable from
     * {@code saves/common/coop_options.json.data} as well as {@code -D}, which is why it is read through
     * {@link CoopOptionsStore} rather than off {@code System.getProperty} - a player who turns the
     * HUD off in their settings file expects it to stay off.
     */
    public static final String DISABLE_PROPERTY = CoopOptionsRegistry.HUD_DISABLE;

    /** Gap from the left/right screen edge, in UI-coordinate pixels. Tune after a visual check. */
    static final float SIDE_MARGIN = 10f;
    /** Drop from the top screen edge to the line's top, clearing the vanilla fps/version overlay. */
    static final float TOP_OFFSET = 60f;
    /** Gap from the bottom screen edge to the line's bottom, clearing the vanilla bottom bar. */
    static final float BOTTOM_OFFSET = 60f;

    /** The vanilla UI font. */
    static final String FONT_PATH = "graphics/fonts/insignia15LTaa.fnt";

    /** Wall-clock throttle: the link state is re-read at most this often, not once per frame. */
    private static final long REFRESH_INTERVAL_MILLIS = 100L;

    private static final Color TEXT_COLOR = new Color(200, 200, 200);
    private static final Color PAUSED_COLOR = new Color(255, 215, 120);
    /**
     * The {@code netfault} countdown line. Deliberately a harder orange than {@link #PAUSED_COLOR}:
     * a paused world is normal and this is not, and the two lines are adjacent, so the same amber for
     * both would let a tester read the warning as a pause and miss the outage they scheduled. Matches
     * the feed's "bad" colour, which is what carries the same two events into the intel log.
     */
    private static final Color NET_FAULT_COLOR = new Color(255, 170, 90);

    /**
     * The transient {@link CoopHudNotice} row. A cool blue, deliberately neither of the two warning
     * colours above: "your partner pressed the marker key" is an acknowledgement, not a problem, and
     * it appears next to a line that is amber when the world is paused.
     */
    private static final Color NOTICE_COLOR = new Color(150, 200, 255);
    private static final Color VOTE_BANNER_COLOR = new Color(120, 255, 175);

    private final CoopNetPump pump;
    /**
     * Phase 28 milestone 3: no longer fixed at install. {@code coop.hudCorner} and
     * {@code coop.hud.disable} both apply IMMEDIATE, so both are re-read on the 100 ms refresh tick
     * below - a change made on the options page moves the line without a relaunch. Re-reading is a
     * map lookup against the store's cached layers, which is why it can afford to be on a tick at
     * all; it is deliberately not on the render path, which runs at 60 Hz.
     */
    private CoopHudCorner corner;
    /** Re-read with the corner: true hides the line without unregistering the listener. */
    private boolean hiddenByOption;

    private CoopBitmapFont font;
    private String separator = CoopHudState.SEPARATOR_PIPE;
    private boolean fontLoadAttempted;

    /** Sticky: set by the first Throwable out of anything below. Never cleared. */
    private boolean disabled;

    private long nextRefreshAtMillis;
    private String cachedBadge = CoopHudState.BADGE_COOP;
    private String cachedLine = "";
    private boolean cachedPaused;
    /** The 0.1.1 netfault countdown, or {@code ""} when no fault is armed or running. */
    private String cachedNetFaultLine = "";
    /** The transient notice, or {@code ""}; read on the refresh tick like everything else here. */
    private String cachedNoticeLine = "";
    /** Live BG3-style partner dialog vote banner shown top-center over the speaker's open dialog. */
    private String cachedVoteBanner = "";
    /** Clickable top-center prompt shown when the player closed the spectator window while partner is still in a dialog. */
    private String cachedRejoinBanner = "";

    private CoopLinkHud(CoopNetPump pump, CoopHudCorner corner) {
        this.pump = pump;
        this.corner = corner == null ? CoopHudCorner.DEFAULT : corner;
    }

    /**
     * Registers a fresh HUD on {@code sector}, replacing any previous one (a game load installs a new
     * pump, and the listener must point at it). Transient: the listener holds a live pump reference
     * and must never be walked into a save.
     */
    public static void install(SectorAPI sector, CoopNetPump pump) {
        activeRejoinPillRect = null;
        if (sector == null || pump == null) {
            return;
        }
        if (disabledByOption()) {
            // Registered anyway, and drawing nothing: since Phase 28 milestone 3 this option is
            // IMMEDIATE, and a listener that was never installed could not be turned back on from
            // the options page without a game load.
            CoopLog.info(CoopLinkHud.class,
                    "Coop link HUD hidden via " + DISABLE_PROPERTY + "=true");
        }
        try {
            CoopHudCorner corner = CoopNetStartupConfig.hudCornerFromSystemProperties();
            sector.getListenerManager().removeListenerOfClass(CoopLinkHud.class);
            sector.getListenerManager().addListener(new CoopLinkHud(pump, corner), true);
        } catch (Throwable ex) {
            CoopLog.warn(CoopLinkHud.class, "Coop link HUD could not be installed; continuing without it", ex);
        }
    }

    /**
     * The full option stack, not just {@code -D}: command line, then
     * {@code saves/common/coop_options.json.data}, then the shipped defaults. A value that is neither
     * true nor false is warned about once by the store and read as the default (false).
     */
    static boolean disabledByOption() {
        return disabledByOption(CoopOptionsStore.system());
    }

    /** Same question against an explicit store, which is how the file layer is tested. */
    static boolean disabledByOption(CoopOptionsStore store) {
        try {
            return store.bool(DISABLE_PROPERTY);
        } catch (Exception | LinkageError ex) {
            // Cosmetic feature, config-shaped failure: install the HUD rather than lose it.
            CoopLog.warn(CoopLinkHud.class, "Could not read " + DISABLE_PROPERTY
                    + "; installing the link HUD anyway", ex);
            return false;
        }
    }

    @Override
    public void renderInUICoordsBelowUI(ViewportAPI viewport) {
        // Nothing here: the line belongs above the UI.
    }

    @Override
    public void renderInUICoordsAboveUIAndTooltips(ViewportAPI viewport) {
        // Nothing here: never over a tooltip.
    }

    @Override
    public void renderInUICoordsAboveUIBelowTooltips(ViewportAPI viewport) {
        if (disabled) {
            return;
        }
        try {
            render();
        } catch (Throwable ex) {
            disabled = true;
            CoopLog.warn(CoopLinkHud.class, "Coop link HUD failed and is now off for this session", ex);
        }
    }

    private void render() {
        SectorAPI sector = Global.getSector();
        if (sector == null) {
            activeRejoinPillRect = null;
            return;
        }
        CampaignUIAPI ui = sector.getCampaignUI();
        if (ui == null || ui.isHideUI()) {
            activeRejoinPillRect = null;
            return;
        }
        if (!ensureFont()) {
            activeRejoinPillRect = null;
            return;
        }

        refreshIfDue(sector);
        float screenWidth = Global.getSettings().getScreenWidth();
        float screenHeight = Global.getSettings().getScreenHeight();
        float lineHeight = font.lineHeight();

        // Speaker-side BG3 option vote pill renders top-center even over an open interaction dialog.
        if (!cachedVoteBanner.isEmpty()) {
            float textW = font.width(cachedVoteBanner);
            float padX = 16f;
            float padY = 6f;
            float boxW = textW + padX * 2f;
            float boxH = lineHeight + padY * 2f;
            float bx = (screenWidth - boxW) * 0.5f;
            float by = screenHeight - boxH - 18f;
            CoopTitleScreenOverlay.drawRect(bx, by, boxW, boxH, 0.05f, 0.20f, 0.13f, 0.92f);
            CoopTitleScreenOverlay.drawBorder(bx, by, boxW, boxH, 0.38f, 0.95f, 0.60f, 0.95f);
            font.draw(cachedVoteBanner, bx + padX, by + boxH - padY, VOTE_BANNER_COLOR);
        }

        // Clickable top-center "Rejoin partner's dialog" pill when player minimized the spectator window.
        boolean showRejoinPill = !cachedRejoinBanner.isEmpty()
                && !ui.isShowingDialog()
                && !ui.isShowingMenu();
        if (showRejoinPill) {
            float textW = font.width(cachedRejoinBanner);
            float padX = 18f;
            float padY = 8f;
            float boxW = textW + padX * 2f;
            float boxH = lineHeight + padY * 2f;
            float bx = (screenWidth - boxW) * 0.5f;
            float by = screenHeight - boxH - 18f;
            if (!cachedVoteBanner.isEmpty()) {
                by -= (lineHeight + 22f);
            }
            PillRect rect = new PillRect(bx, by, boxW, boxH);
            activeRejoinPillRect = rect;
            boolean hovered = isMouseInside(rect);
            if (hovered) {
                CoopTitleScreenOverlay.drawRect(bx, by, boxW, boxH, 0.10f, 0.36f, 0.22f, 0.96f);
                CoopTitleScreenOverlay.drawBorder(bx, by, boxW, boxH, 0.55f, 1.0f, 0.72f, 1.0f);
            } else {
                CoopTitleScreenOverlay.drawRect(bx, by, boxW, boxH, 0.05f, 0.22f, 0.15f, 0.93f);
                CoopTitleScreenOverlay.drawBorder(bx, by, boxW, boxH, 0.38f, 0.95f, 0.60f, 0.95f);
            }
            font.draw(cachedRejoinBanner, bx + padX, by + boxH - padY,
                    hovered ? Color.WHITE : VOTE_BANNER_COLOR);
        } else {
            activeRejoinPillRect = null;
        }

        if (hiddenByOption
                || (cachedLine.isEmpty() && cachedNetFaultLine.isEmpty() && cachedNoticeLine.isEmpty())) {
            return;
        }
        if (!cachedLine.isEmpty()) {
            float badgeWidth = font.width(cachedBadge);
            float totalWidth = font.width(cachedLine);
            HudAnchor anchor = anchor(corner, screenWidth, screenHeight, totalWidth, lineHeight);
            float x = anchor.x();
            float y = anchor.y();

            // Two draws so the badge keeps the player colour while the rest carries the pause state.
            // formatLine always leads with the badge, so this split is exact.
            String remainder = cachedLine.substring(Math.min(cachedBadge.length(), cachedLine.length()));
            font.draw(cachedBadge, x, y, badgeColor());
            font.draw(remainder, x + badgeWidth, y, cachedPaused ? PAUSED_COLOR : TEXT_COLOR);
        }

        int row = cachedLine.isEmpty() ? 0 : 1;
        if (!cachedNetFaultLine.isEmpty()) {
            // Its own row, never the link line's: the whole reason it exists is to be read at a
            // glance, and a warning drawn over the readout it is warning about is worse than none.
            float faultWidth = font.width(cachedNetFaultLine);
            HudAnchor faultAnchor = anchor(corner, screenWidth, screenHeight, faultWidth, lineHeight);
            font.draw(cachedNetFaultLine, faultAnchor.x(),
                    rowY(corner, faultAnchor.y(), lineHeight, row), NET_FAULT_COLOR);
            row++;
        }

        if (!cachedNoticeLine.isEmpty()) {
            float noticeWidth = font.width(cachedNoticeLine);
            HudAnchor noticeAnchor = anchor(corner, screenWidth, screenHeight, noticeWidth, lineHeight);
            font.draw(cachedNoticeLine, noticeAnchor.x(),
                    rowY(corner, noticeAnchor.y(), lineHeight, row), NOTICE_COLOR);
        }
    }

    /**
     * Where the second line's top edge goes, given the first line's. Top corners stack downward and
     * bottom corners stack upward, so the second row grows away from the screen edge in both cases
     * and neither line ever lands on the other.
     *
     * <p>Pure and GL-free, like {@link #anchor}, so the stacking is unit-testable.
     */
    static float secondRowY(CoopHudCorner corner, float firstRowY, float lineHeight) {
        return rowY(corner, firstRowY, lineHeight, 1);
    }

    /**
     * Generalised {@link #secondRowY}: row 0 is the anchor row, and each row after it grows away
     * from the screen edge. Three lines can be on screen at once since the marker notice arrived
     * (link, netfault countdown, notice), and stacking by index is what keeps any two of them from
     * landing on each other whichever ones are present.
     */
    static float rowY(CoopHudCorner corner, float firstRowY, float lineHeight, int row) {
        CoopHudCorner effective = corner == null ? CoopHudCorner.DEFAULT : corner;
        boolean top = effective == CoopHudCorner.TOP_LEFT || effective == CoopHudCorner.TOP_RIGHT;
        float offset = lineHeight * Math.max(0, row);
        return top ? firstRowY - offset : firstRowY + offset;
    }

    /**
     * The line's top-left in UI coordinates (origin bottom-left, y up — see
     * {@link CoopBitmapFont#draw}), for the requested corner.
     *
     * <p>Pure and GL-free so the four placements are unit-testable without a live renderer.
     * {@code textHeight} is the font's line height, not this particular line's rendered height (the
     * bitmap font has no per-string height, only a per-font one) — it is only used to lift the line
     * clear of the bottom offset for the two bottom corners; the top corners anchor from the line's
     * top and never need it.
     *
     * @param corner      which corner to anchor to
     * @param screenWidth  UI-coordinate screen width
     * @param screenHeight UI-coordinate screen height
     * @param textWidth    the rendered line's width, from {@link CoopBitmapFont#width}
     * @param textHeight   the font's line height, from {@link CoopBitmapFont#lineHeight()}
     */
    static HudAnchor anchor(CoopHudCorner corner, float screenWidth, float screenHeight,
                             float textWidth, float textHeight) {
        CoopHudCorner effective = corner == null ? CoopHudCorner.DEFAULT : corner;
        boolean left = effective == CoopHudCorner.TOP_LEFT || effective == CoopHudCorner.BOTTOM_LEFT;
        boolean top = effective == CoopHudCorner.TOP_LEFT || effective == CoopHudCorner.TOP_RIGHT;
        float x = left ? SIDE_MARGIN : screenWidth - SIDE_MARGIN - textWidth;
        float y = top ? screenHeight - TOP_OFFSET : BOTTOM_OFFSET + textHeight;
        return new HudAnchor(x, y);
    }

    /** The line's top-left anchor point, in UI coordinates; see {@link #anchor}. */
    record HudAnchor(float x, float y) {
    }

    private void refreshIfDue(SectorAPI sector) {
        long now = System.currentTimeMillis();
        if (now < nextRefreshAtMillis) {
            return;
        }
        nextRefreshAtMillis = now + REFRESH_INTERVAL_MILLIS;
        refreshOptions();
        CoopHudState state = pump.hudState(sector.isPaused());
        cachedBadge = state.roleBadge();
        cachedPaused = state.paused();
        cachedLine = CoopHudState.formatLine(state, separator);
        // 100 ms is well inside a one-second countdown step, so the armed line never skips a number.
        cachedNetFaultLine = CoopHudState.formatNetFaultLine(state);
        // The notice expires on its own clock; the tick only decides how often the HUD asks.
        cachedNoticeLine = CoopHudNotice.current(now);
        cachedVoteBanner = pump.dialogVoteBanner(now);
        cachedRejoinBanner = pump.dialogSpectatorRejoinBanner();
    }

    private static boolean isMouseInside(PillRect rect) {
        try {
            float scale = Math.max(0.5f, Global.getSettings().getScreenScaleMult());
            float mx = Global.getSettings().getMouseX() / scale;
            float my = Global.getSettings().getMouseY() / scale;
            return rect.contains(mx, my);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public int getListenerInputPriority() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void processCampaignInputPreCore(List<InputEventAPI> events) {
        if (events == null || disabled || pump == null) {
            return;
        }
        try {
            SectorAPI sector = Global.getSector();
            CampaignUIAPI ui = sector == null ? null : sector.getCampaignUI();
            if (ui == null || ui.isShowingDialog() || ui.isShowingMenu()) {
                return;
            }
            for (InputEventAPI ev : events) {
                if (ev == null || ev.isConsumed()) {
                    continue;
                }
                if (ev.isMouseDownEvent() && ev.getEventValue() == 0
                        && isPointInRejoinPill(ev.getX(), ev.getY())) {
                    ev.consume();
                    activeRejoinPillRect = null;
                    cachedRejoinBanner = "";
                    pump.requestReopenDialogSpectator();
                    return;
                }
                if (ev.isKeyDownEvent() && !ev.isRepeat() && ev.getEventValue() == REJOIN_DIALOG_KEY
                        && pump.isDialogSpectatorRejoinAvailable()) {
                    ev.consume();
                    activeRejoinPillRect = null;
                    cachedRejoinBanner = "";
                    pump.requestReopenDialogSpectator();
                    return;
                }
            }
        } catch (Throwable ignored) {
            // Never disrupt campaign input processing.
        }
    }

    @Override
    public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {
    }

    @Override
    public void processCampaignInputPostCore(List<InputEventAPI> events) {
    }

    /**
     * Re-reads the two IMMEDIATE client options. Wrapped rather than trusted: a settings read that
     * throws must cost the line's position, not the line.
     *
     * <p>{@link #hiddenByOption} hides rather than uninstalls, because an install-time removal is
     * the one thing that could not be undone without a game load - and a player who turns the HUD
     * back on expects it back now.
     */
    private void refreshOptions() {
        try {
            corner = CoopNetStartupConfig.hudCornerFromSystemProperties();
        } catch (Throwable ex) {
            corner = CoopHudCorner.DEFAULT;
        }
        hiddenByOption = disabledByOption();
    }

    private Color badgeColor() {
        Color color = Global.getSettings().getBasePlayerColor();
        return color == null ? TEXT_COLOR : color;
    }

    /** Loads the font once. A failed load is not retried — one warn, then the HUD stays dark. */
    private boolean ensureFont() {
        if (font != null) {
            return true;
        }
        if (fontLoadAttempted) {
            return false;
        }
        fontLoadAttempted = true;
        try {
            CoopBitmapFont loaded = CoopBitmapFont.load(FONT_PATH);
            separator = loaded.hasGlyph(CoopHudState.SEPARATOR_DOT_CODE_POINT)
                    ? CoopHudState.SEPARATOR_DOT
                    : CoopHudState.SEPARATOR_PIPE;
            font = loaded;
            return true;
        } catch (Throwable ex) {
            disabled = true;
            CoopLog.warn(CoopLinkHud.class,
                    "Coop link HUD could not load " + FONT_PATH + "; HUD is off for this session", ex);
            return false;
        }
    }
}
