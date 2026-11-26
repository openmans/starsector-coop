package coop.ui;

import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.CutStyle;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import coop.util.CoopLog;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Baldur's Gate 3-style live dialog spectator window.
 *
 * <p>Automatically opened when the partner (Host OR Guest) enters any gameplay
 * {@code InteractionDialog}. Renders:
 * <ul>
 *   <li>Top-center title: {@code Диалог игрока «Ник»} / {@code Dialog of "Name"}</li>
 *   <li>Live mirrored dialog text and target name</li>
 *   <li>All current dialog options with live speaker hover highlight</li>
 *   <li>Clickable option voting (recommends an option to the speaking partner)</li>
 *   <li>Smoothly interpolated OpenGL pointer showing the speaker's live mouse cursor</li>
 * </ul>
 */
public final class CoopDialogSpectatorPlugin implements InteractionDialogPlugin, CoopDismissableDialog {

    private static final float PANEL_W = 840f;
    private static final float PANEL_H = 580f;
    private static final String ID_CLOSE = "spectator.close";
    private static final String ID_VOTE_PREFIX = "spectator.vote.";

    private static final Color HEADER_GREEN = new Color(110, 255, 165);
    private static final Color TARGET_CYAN = new Color(130, 225, 255);
    private static final Color BODY_TEXT = new Color(224, 240, 250);
    private static final Color MUTED = new Color(145, 185, 205);
    private static final Color OPT_BG = new Color(16, 38, 54);
    private static final Color OPT_TEXT = new Color(205, 238, 255);
    private static final Color OPT_DISABLED_TEXT = new Color(115, 135, 145);
    private static final Color OPT_HOVER_BG = new Color(36, 92, 118);
    private static final Color OPT_HOVER_TEXT = new Color(255, 225, 120);
    private static final Color OPT_VOTE_BG = new Color(24, 96, 56);
    private static final Color OPT_VOTE_TEXT = new Color(130, 255, 175);

    private final CoopDialogSpectatorState state;
    private final BiConsumer<Integer, String> onVote;
    private final Runnable onClosed;

    private InteractionDialogAPI dialog;
    private String lastRenderedKey = "";
    private boolean dismissed;
    private float smoothCursorX = 0.5f;
    private float smoothCursorY = 0.5f;
    private String currentDialogId = "";
    private String currentSpeakerName = "";
    private CoopBitmapFont cursorFont;
    private boolean cursorFontAttempted;

    public CoopDialogSpectatorPlugin(CoopDialogSpectatorState state,
                                     BiConsumer<Integer, String> onVote,
                                     Runnable onClosed) {
        this.state = Objects.requireNonNull(state, "state");
        this.onVote = onVote == null ? (idx, txt) -> {} : onVote;
        this.onClosed = onClosed == null ? () -> {} : onClosed;
    }

    @Override
    public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        try {
            dialog.hideTextPanel();
            dialog.getOptionPanel().clearOptions();
        } catch (Throwable ignored) {
        }
        CoopDialogSpectatorState.SyncPayload current = state.activeRemoteDialog();
        if (current != null) {
            smoothCursorX = current.cursorNormX();
            smoothCursorY = current.cursorNormY();
            rebuild(current);
        }
    }

    @Override
    public void advance(float amount) {
        if (dismissed) {
            return;
        }
        CoopDialogSpectatorState.SyncPayload current = state.activeRemoteDialog();
        if (current == null || !current.open()) {
            dismissInternal(false);
            return;
        }
        // Smooth cursor interpolation at 60 Hz between ~8 Hz network samples
        float lerp = Math.min(1f, Math.max(0.15f, amount * 14f));
        smoothCursorX += (current.cursorNormX() - smoothCursorX) * lerp;
        smoothCursorY += (current.cursorNormY() - smoothCursorY) * lerp;

        String key = current.structuralKey() + "|lv=" + state.localVotedIndexFor(current.dialogId())
                + "|ru=" + CoopUiLang.isRussian();
        if (!key.equals(lastRenderedKey)) {
            rebuild(current);
        }
    }

    private void rebuild(CoopDialogSpectatorState.SyncPayload snap) {
        if (dialog == null || dismissed) {
            return;
        }
        try {
            currentDialogId = snap.dialogId();
            currentSpeakerName = snap.speakerName();
            lastRenderedKey = snap.structuralKey() + "|lv=" + state.localVotedIndexFor(snap.dialogId())
                    + "|ru=" + CoopUiLang.isRussian();

            CustomPanelAPI panel = dialog.getVisualPanel().showCustomPanel(
                    PANEL_W, PANEL_H, new SpectatorPanelPlugin());

            // 1. Top-center header: "Диалог игрока «Ник»"
            float headerH = 62f;
            TooltipMakerAPI header = panel.createUIElement(PANEL_W, headerH, false);
            LabelAPI title = header.addPara(CoopDialogSpectatorState.headerTitle(snap.speakerName()),
                    HEADER_GREEN, 0f);
            title.setAlignment(Alignment.MID);
            title.getPosition().setSize(PANEL_W - 180f, 24f);
            title.getPosition().inTMid(10f);

            String sub = snap.targetName().isEmpty()
                    ? CoopUiLang.tr("Live view · Click any option to vote · ESC to leave",
                            "Прямой эфир · Нажмите на вариант, чтобы проголосовать · ESC — выйти")
                    : snap.targetName() + "  ·  " + CoopUiLang.tr(
                            "Click an option to vote · ESC to leave",
                            "Нажмите на вариант для голоса · ESC — выйти");
            LabelAPI subtitle = header.addPara(sub, TARGET_CYAN, 0f);
            subtitle.setAlignment(Alignment.MID);
            subtitle.getPosition().setSize(PANEL_W - 180f, 20f);
            subtitle.getPosition().inTMid(36f);

            ButtonAPI closeBtn = header.addButton(CoopUiLang.tr("Leave", "Выйти"), ID_CLOSE,
                    OPT_TEXT, OPT_BG, Alignment.MID, CutStyle.ALL, 76f, 24f, 0f);
            closeBtn.getPosition().inTR(14f, 14f);
            closeBtn.setShortcut(Keyboard.KEY_ESCAPE, true);
            panel.addUIElement(header).inTL(0f, 0f);

            // 2. Scrollable dialog text area
            float pad = 18f;
            float innerW = PANEL_W - pad * 2f;
            int optCount = snap.options().size();
            float optionsH = Math.min(240f, Math.max(88f, optCount * 34f + 36f));
            float textH = PANEL_H - headerH - optionsH - 18f;

            TooltipMakerAPI textUi = panel.createUIElement(innerW, textH, true);
            if (snap.paragraphs().isEmpty()) {
                textUi.addPara(CoopUiLang.tr("Waiting for dialog text...", "Ожидание текста диалога..."),
                        MUTED, 6f);
            } else {
                float pPad = 4f;
                for (String para : snap.paragraphs()) {
                    textUi.addPara(para, BODY_TEXT, pPad);
                    pPad = 8f;
                }
            }
            panel.addUIElement(textUi).inTL(pad, headerH + 4f);

            // 3. Clickable mirrored options + hover & BG3 vote markers
            TooltipMakerAPI optUi = panel.createUIElement(innerW, optionsH, true);
            optUi.addSectionHeading(
                    CoopUiLang.tr("Choices (click to vote for a reply)",
                            "Варианты ответа (нажмите, чтобы посоветовать)"),
                    HEADER_GREEN, new Color(18, 46, 36), Alignment.MID, 0f);

            int localVote = state.localVotedIndexFor(snap.dialogId());
            int activeVote = localVote >= 0 ? localVote : snap.votedOptionIndex();
            for (int i = 0; i < snap.options().size(); i++) {
                CoopDialogSpectatorState.OptionView opt = snap.options().get(i);
                boolean hovered = i == snap.hoveredOptionIndex();
                boolean voted = i == activeVote;

                StringBuilder label = new StringBuilder();
                if (hovered) {
                    label.append("> ");
                }
                if (voted) {
                    label.append(CoopUiLang.tr("[VOTE] ", "[ГОЛОС] "));
                }
                label.append(i + 1).append(". ").append(opt.text());
                if (hovered) {
                    label.append(CoopUiLang.tr("  <-- " + snap.speakerName(),
                            "  <-- " + snap.speakerName()));
                }

                Color fg = !opt.enabled() ? OPT_DISABLED_TEXT
                        : voted ? OPT_VOTE_TEXT
                        : hovered ? OPT_HOVER_TEXT : OPT_TEXT;
                Color bg = voted ? OPT_VOTE_BG
                        : hovered ? OPT_HOVER_BG : OPT_BG;

                ButtonAPI btn = optUi.addButton(label.toString(), ID_VOTE_PREFIX + i,
                        fg, bg, Alignment.LMID, CutStyle.ALL, innerW - 14f, 26f, 6f);
                btn.setEnabled(opt.enabled());
            }
            if (snap.options().isEmpty()) {
                optUi.addPara(CoopUiLang.tr("No choices shown yet.", "Варианты ответа пока не отображаются."),
                        MUTED, 8f);
            }
            panel.addUIElement(optUi).inTL(pad, headerH + textH + 10f);
        } catch (Throwable ex) {
            CoopLog.warn(CoopDialogSpectatorPlugin.class, "Could not render dialog spectator window", ex);
        }
    }

    @Override
    public void close() {
        dismissInternal(false);
    }

    private void dismissInternal(boolean byUser) {
        if (dismissed) {
            return;
        }
        dismissed = true;
        if (byUser && !currentDialogId.isEmpty()) {
            state.markDismissedByUser(currentDialogId);
        }
        if (dialog != null) {
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {
            }
        }
        onClosed.run();
    }

    private final class SpectatorPanelPlugin implements CustomUIPanelPlugin {
        private float px;
        private float py;
        private float pw = PANEL_W;
        private float ph = PANEL_H;

        @Override
        public void positionChanged(PositionAPI pos) {
            if (pos != null) {
                px = pos.getX();
                py = pos.getY();
                pw = pos.getWidth();
                ph = pos.getHeight();
            }
        }

        @Override
        public void renderBelow(float alphaMult) {
            CoopTitleScreenOverlay.drawRect(px, py, pw, ph, 0.04f, 0.08f, 0.13f, 0.96f * alphaMult);
            // Header band
            CoopTitleScreenOverlay.drawRect(px, py + ph - 62f, pw, 62f, 0.06f, 0.16f, 0.14f, 0.95f * alphaMult);
            CoopTitleScreenOverlay.drawBorder(px, py, pw, ph, 0.28f, 0.88f, 0.51f, 0.95f * alphaMult);
        }

        @Override
        public void render(float alphaMult) {
            // Draw the speaking partner's live mouse cursor over the mirrored dialog
            float cx = px + 14f + smoothCursorX * Math.max(1f, pw - 28f);
            float cy = py + 14f + smoothCursorY * Math.max(1f, ph - 76f);
            drawPartnerCursor(cx, cy, alphaMult);
        }

        @Override public void advance(float amount) {}

        @Override
        public void processInput(List<InputEventAPI> events) {
            if (events == null) return;
            for (InputEventAPI ev : events) {
                if (!ev.isConsumed() && ev.isKeyDownEvent() && ev.getEventValue() == Keyboard.KEY_ESCAPE) {
                    ev.consume();
                    dismissInternal(true);
                    return;
                }
            }
        }

        @Override
        public void buttonPressed(Object buttonId) {
            if (!(buttonId instanceof String id)) {
                return;
            }
            if (ID_CLOSE.equals(id)) {
                dismissInternal(true);
                return;
            }
            if (id.startsWith(ID_VOTE_PREFIX)) {
                try {
                    int idx = Integer.parseInt(id.substring(ID_VOTE_PREFIX.length()));
                    CoopDialogSpectatorState.SyncPayload snap = state.activeRemoteDialog();
                    if (snap != null && idx >= 0 && idx < snap.options().size()) {
                        String optText = snap.options().get(idx).text();
                        state.recordLocalVote(snap.dialogId(), idx);
                        onVote.accept(idx, optText);
                        rebuild(snap);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    private void drawPartnerCursor(float x, float y, float alpha) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        // Pointer triangle (tip at (x, y), pointing up-left like a desktop cursor)
        GL11.glColor4f(0.43f, 1.0f, 0.65f, 0.95f * alpha);
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + 14f, y - 12f);
        GL11.glVertex2f(x + 4f, y - 17f);
        GL11.glEnd();

        GL11.glLineWidth(1.5f);
        GL11.glColor4f(0.05f, 0.14f, 0.10f, 0.95f * alpha);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + 14f, y - 12f);
        GL11.glVertex2f(x + 4f, y - 17f);
        GL11.glEnd();

        // Name tag pill next to cursor
        if (!currentSpeakerName.isEmpty()) {
            ensureCursorFont();
            if (cursorFont != null) {
                float tagW = cursorFont.width(currentSpeakerName) + 12f;
                float tagH = 18f;
                float tx = x + 15f;
                float ty = y - 22f;
                CoopTitleScreenOverlay.drawRect(tx, ty, tagW, tagH, 0.06f, 0.22f, 0.15f, 0.90f * alpha);
                CoopTitleScreenOverlay.drawBorder(tx, ty, tagW, tagH, 0.43f, 1.0f, 0.65f, 0.85f * alpha);
                cursorFont.draw(currentSpeakerName, tx + 6f, ty + 16f, HEADER_GREEN);
            }
        }
    }

    private void ensureCursorFont() {
        if (cursorFontAttempted) {
            return;
        }
        cursorFontAttempted = true;
        try {
            cursorFont = CoopBitmapFont.load(CoopLinkHud.FONT_PATH);
        } catch (Throwable ignored) {
            cursorFont = null;
        }
    }

    @Override public void optionSelected(String optionText, Object optionData) {}
    @Override public void optionMousedOver(String optionText, Object optionData) {}
    @Override public void backFromEngagement(EngagementResultAPI battleResult) {}
    @Override public Object getContext() { return null; }
    @Override public Map<String, MemoryAPI> getMemoryMap() { return null; }
}
