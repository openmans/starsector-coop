package coop.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.econ.CommoditySpecAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.CutStyle;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.ScrollPanelAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import coop.util.CoopLog;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Baldur's Gate 3-style live spectator window for dialogs, market trading, and Comm Directory.
 *
 * <p>Features:
 * <ul>
 *   <li>Top-center title: {@code Диалог игрока «Ник»} / {@code Dialog of "Name"}</li>
 *   <li>Auto-scrolling dialog text with numeric highlights (credits, days, units, %) and
 *       inline native commodity icons whenever commodities are mentioned</li>
 *   <li>Dedicated Trade view with native commodity icons, stock counts, and buy/sell prices</li>
 *   <li>Dedicated Comm Directory view with NPC portraits, posts, and factions</li>
 *   <li>Clickable BG3-style option voting and live OpenGL partner cursor</li>
 * </ul>
 */
public final class CoopDialogSpectatorPlugin implements InteractionDialogPlugin, CoopDismissableDialog {

    private static final float PANEL_W = 880f;
    private static final float PANEL_H = 610f;
    private static final String ID_CLOSE = "spectator.close";
    private static final String ID_VOTE_PREFIX = "spectator.vote.";

    private static final Color HEADER_GREEN = new Color(110, 255, 165);
    private static final Color TARGET_CYAN = new Color(130, 225, 255);
    private static final Color BODY_TEXT = new Color(224, 240, 250);
    private static final Color HIGHLIGHT_GOLD = new Color(255, 214, 102);
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

    // Lazily cached localized commodity names -> spec (works in both RU and EN Starsector)
    private Map<String, CommoditySpecAPI> commodityByLowerName;

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
            float headerH = 64f;
            TooltipMakerAPI header = panel.createUIElement(PANEL_W, headerH, false);
            LabelAPI title = header.addPara(CoopDialogSpectatorState.headerTitle(snap.speakerName()),
                    HEADER_GREEN, 0f);
            title.setAlignment(Alignment.MID);
            title.getPosition().setSize(PANEL_W - 190f, 24f);
            title.getPosition().inTMid(10f);

            String modeLabel = viewModeBadge(snap.viewMode());
            StringBuilder sub = new StringBuilder();
            if (!snap.targetName().isEmpty()) {
                sub.append(snap.targetName()).append("  ·  ");
            }
            sub.append(modeLabel);
            if (!snap.subHeader().isEmpty()) {
                sub.append("  ·  ").append(snap.subHeader());
            } else {
                sub.append("  ·  ").append(CoopUiLang.tr(
                        "Click to vote · ESC to minimize (F6 to rejoin)",
                        "Нажмите для голоса · ESC — свернуть (F6 — вернуться)"));
            }
            LabelAPI subtitle = header.addPara(sub.toString(), TARGET_CYAN, 0f);
            subtitle.setAlignment(Alignment.MID);
            subtitle.getPosition().setSize(PANEL_W - 210f, 20f);
            subtitle.getPosition().inTMid(36f);

            ButtonAPI closeBtn = header.addButton(CoopUiLang.tr("Minimize", "Свернуть"), ID_CLOSE,
                    OPT_TEXT, OPT_BG, Alignment.MID, CutStyle.ALL, 92f, 24f, 0f);
            closeBtn.getPosition().inTR(14f, 16f);
            closeBtn.setShortcut(Keyboard.KEY_ESCAPE, true);
            panel.addUIElement(header).inTL(0f, 0f);

            float pad = 18f;
            float innerW = PANEL_W - pad * 2f;
            float bodyY = headerH + 6f;
            float bodyH = PANEL_H - bodyY - 14f;

            if ("TRADE".equals(snap.viewMode())) {
                buildTradeView(panel, snap, pad, innerW, bodyY, bodyH);
            } else if ("FLEET".equals(snap.viewMode()) || "REFIT".equals(snap.viewMode())) {
                buildFleetView(panel, snap, pad, innerW, bodyY, bodyH);
            } else if ("COMM".equals(snap.viewMode())) {
                buildCommDirectoryView(panel, snap, pad, innerW, bodyY, bodyH);
            } else {
                buildStandardDialogView(panel, snap, pad, innerW, bodyY, bodyH);
            }
        } catch (Throwable ex) {
            CoopLog.warn(CoopDialogSpectatorPlugin.class, "Could not render dialog spectator window", ex);
        }
    }

    private void buildTradeView(CustomPanelAPI panel, CoopDialogSpectatorState.SyncPayload snap,
                                float pad, float innerW, float bodyY, float bodyH) {
        TooltipMakerAPI hdr = panel.createUIElement(innerW, 44f, false);
        hdr.addSectionHeading(
                CoopUiLang.tr("Market Trade — click a row to recommend",
                        "Торговля на рынке — выберите товар для совета"),
                HEADER_GREEN, new Color(18, 46, 36), Alignment.MID, 0f);

        CustomPanelAPI colHeader = panel.createCustomPanel(innerW, 20f, new TableRowPlugin());
        TooltipMakerAPI colUi = colHeader.createUIElement(innerW, 20f, false);
        addTableCell(colUi, CoopUiLang.tr("Commodity", "Товар"), MUTED, 6f, 2f, 270f);
        addTableCell(colUi, CoopUiLang.tr("Qty", "Кол-во"), MUTED, 290f, 2f, 90f);
        addTableCell(colUi, CoopUiLang.tr("Buy", "Покупка"), MUTED, 390f, 2f, 95f);
        addTableCell(colUi, CoopUiLang.tr("Sell", "Продажа"), MUTED, 500f, 2f, 95f);
        addTableCell(colUi, CoopUiLang.tr("Recommend", "Совет"), MUTED, 636f, 2f, 160f);
        colHeader.addUIElement(colUi).inTL(0f, 0f);
        hdr.addCustom(colHeader, 2f);
        panel.addUIElement(hdr).inTL(pad, bodyY);

        TooltipMakerAPI tradeUi = panel.createUIElement(innerW, Math.max(80f, bodyH - 46f), true);
        int localVote = state.localVotedIndexFor(snap.dialogId());
        int activeVote = localVote >= 0 ? localVote : snap.votedOptionIndex();

        if (snap.tradeItems().isEmpty()) {
            tradeUi.addPara(CoopUiLang.tr(
                    "The partner has the trade screen open (no commodities listed in this market).",
                    "Напарник открыл окно торговли (товары на этом рынке не найдены)."),
                    MUTED, 10f);
        } else {
            TableRowPlugin rowForward = new TableRowPlugin();
            float rowW = innerW - 18f;
            String lastSub = "";
            for (int i = 0; i < snap.tradeItems().size(); i++) {
                CoopDialogSpectatorState.TradeItemView item = snap.tradeItems().get(i);
                if (!item.submarket().isEmpty() && !item.submarket().equals(lastSub)) {
                    lastSub = item.submarket();
                    tradeUi.addPara("— " + lastSub, TARGET_CYAN, i == 0 ? 4f : 8f);
                }
                boolean voted = i == activeVote;
                CustomPanelAPI rowPanel = panel.createCustomPanel(rowW, 24f, rowForward);
                String sprite = commodityIcon(item.commodityId());
                float nameX = 6f;
                if (!sprite.isEmpty()) {
                    try {
                        TooltipMakerAPI iconUi = rowPanel.createUIElement(22f, 22f, false);
                        iconUi.addImage(sprite, 18f, 18f, 0f);
                        rowPanel.addUIElement(iconUi).inTL(4f, 3f);
                        nameX = 28f;
                    } catch (Throwable ignored) {
                    }
                }
                TooltipMakerAPI rowUi = rowPanel.createUIElement(rowW, 24f, false);
                Color nameColor = voted ? OPT_VOTE_TEXT : BODY_TEXT;
                addTableCell(rowUi, trimToFit(item.name(), 28), nameColor, nameX, 4f, 286f - nameX);
                addTableCell(rowUi, String.valueOf(item.qty()), HIGHLIGHT_GOLD, 290f, 4f, 92f);
                addTableCell(rowUi, item.buyPrice() + "¢", HIGHLIGHT_GOLD, 390f, 4f, 100f);
                addTableCell(rowUi, item.sellPrice() + "¢", TARGET_CYAN, 500f, 4f, 100f);

                if (i < snap.options().size()) {
                    ButtonAPI voteBtn = rowUi.addButton(
                            voted ? CoopUiLang.tr("[VOTED]", "[ГОЛОС ✓]")
                                    : CoopUiLang.tr("Suggest", "Советовать"),
                            ID_VOTE_PREFIX + i,
                            voted ? OPT_VOTE_TEXT : OPT_TEXT,
                            voted ? OPT_VOTE_BG : OPT_BG,
                            Alignment.MID, CutStyle.ALL, 150f, 20f, 0f);
                    voteBtn.setEnabled(true);
                    voteBtn.getPosition().inTL(636f, 2f);
                }
                rowPanel.addUIElement(rowUi).inTL(0f, 0f);
                tradeUi.addCustom(rowPanel, 2f);
            }
        }
        panel.addUIElement(tradeUi).inTL(pad, bodyY + 46f);
    }

    private void buildFleetView(CustomPanelAPI panel, CoopDialogSpectatorState.SyncPayload snap,
                                float pad, float innerW, float bodyY, float bodyH) {
        TooltipMakerAPI hdr = panel.createUIElement(innerW, 44f, false);
        boolean isRefit = "REFIT".equals(snap.viewMode());
        hdr.addSectionHeading(
                isRefit ? CoopUiLang.tr("Partner Refit / Fleet View", "Окно оснащения кораблей напарника")
                        : CoopUiLang.tr("Partner Fleet View — click a ship to recommend",
                        "Флот напарника — нажмите на корабль, чтобы посоветовать"),
                HEADER_GREEN, new Color(18, 46, 36), Alignment.MID, 0f);

        CustomPanelAPI colHeader = panel.createCustomPanel(innerW, 20f, new TableRowPlugin());
        TooltipMakerAPI colUi = colHeader.createUIElement(innerW, 20f, false);
        addTableCell(colUi, CoopUiLang.tr("Ship / Hull", "Корабль / Тип"), MUTED, 6f, 2f, 330f);
        addTableCell(colUi, "DP", MUTED, 350f, 2f, 55f);
        addTableCell(colUi, CoopUiLang.tr("Hull", "Корпус"), MUTED, 415f, 2f, 70f);
        addTableCell(colUi, "CR", MUTED, 490f, 2f, 65f);
        addTableCell(colUi, CoopUiLang.tr("Officer", "Офицер"), MUTED, 558f, 2f, 120f);
        colHeader.addUIElement(colUi).inTL(0f, 0f);
        hdr.addCustom(colHeader, 2f);
        panel.addUIElement(hdr).inTL(pad, bodyY);

        TooltipMakerAPI fleetUi = panel.createUIElement(innerW, Math.max(80f, bodyH - 46f), true);
        int localVote = state.localVotedIndexFor(snap.dialogId());
        int activeVote = localVote >= 0 ? localVote : snap.votedOptionIndex();

        if (snap.paragraphs().isEmpty()) {
            fleetUi.addPara(CoopUiLang.tr(
                    "The partner has their Fleet screen open.",
                    "Напарник открыл экран управления флотом."), MUTED, 10f);
        } else {
            TableRowPlugin rowForward = new TableRowPlugin();
            float rowW = innerW - 18f;
            for (int i = 0; i < snap.paragraphs().size(); i++) {
                String line = snap.paragraphs().get(i);
                String[] cols = line.split("\t", -1);
                String shipName = cols.length > 0 ? cols[0] : line;
                String dp = cols.length > 1 ? cols[1] : "";
                String hull = cols.length > 2 ? cols[2] : "";
                String cr = cols.length > 3 ? cols[3] : "";
                String officer = cols.length > 4 ? cols[4] : "";

                boolean voted = i == activeVote;
                CustomPanelAPI rowPanel = panel.createCustomPanel(rowW, 24f, rowForward);
                TooltipMakerAPI rowUi = rowPanel.createUIElement(rowW, 24f, false);
                addTableCell(rowUi, trimToFit(shipName, 40), voted ? OPT_VOTE_TEXT : BODY_TEXT, 6f, 4f, 340f);
                addTableCell(rowUi, dp, HIGHLIGHT_GOLD, 350f, 4f, 55f);
                addTableCell(rowUi, hull, HIGHLIGHT_GOLD, 415f, 4f, 70f);
                addTableCell(rowUi, cr, TARGET_CYAN, 490f, 4f, 65f);
                addTableCell(rowUi, trimToFit(officer, 14), MUTED, 558f, 4f, 118f);

                if (i < snap.options().size()) {
                    ButtonAPI voteBtn = rowUi.addButton(
                            voted ? CoopUiLang.tr("[✓]", "[ГОЛОС]") : CoopUiLang.tr("Pick", "Выбрать"),
                            ID_VOTE_PREFIX + i,
                            voted ? OPT_VOTE_TEXT : OPT_TEXT,
                            voted ? OPT_VOTE_BG : OPT_BG,
                            Alignment.MID, CutStyle.ALL, 114f, 20f, 0f);
                    voteBtn.setEnabled(true);
                    voteBtn.getPosition().inTL(686f, 2f);
                }
                rowPanel.addUIElement(rowUi).inTL(0f, 0f);
                fleetUi.addCustom(rowPanel, 2f);
            }
        }
        panel.addUIElement(fleetUi).inTL(pad, bodyY + 46f);
    }

    private static void addTableCell(TooltipMakerAPI ui, String text, Color color,
                                     float x, float y, float w) {
        LabelAPI lbl = ui.addPara(text == null ? "" : text, color, 0f);
        lbl.getPosition().setSize(w, 18f);
        lbl.getPosition().inTL(x, y);
    }

    private static String trimToFit(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        if (t.length() <= maxChars) {
            return t;
        }
        return t.substring(0, Math.max(1, maxChars - 1)) + "…";
    }

    private void buildCommDirectoryView(CustomPanelAPI panel, CoopDialogSpectatorState.SyncPayload snap,
                                        float pad, float innerW, float bodyY, float bodyH) {
        TooltipMakerAPI commUi = panel.createUIElement(innerW, bodyH, true);
        commUi.addSectionHeading(
                CoopUiLang.tr("Comm Directory — click any NPC to recommend",
                        "Каталог коммуникаций — нажмите на персонажа, чтобы посоветовать"),
                HEADER_GREEN, new Color(18, 46, 36), Alignment.MID, 0f);

        int localVote = state.localVotedIndexFor(snap.dialogId());
        int activeVote = localVote >= 0 ? localVote : snap.votedOptionIndex();

        if (snap.commEntries().isEmpty()) {
            if (snap.paragraphs().isEmpty()) {
                commUi.addPara(CoopUiLang.tr("No contacts available in this Comm Directory.",
                        "В каталоге коммуникаций нет доступных контактов."), MUTED, 10f);
            } else {
                for (String p : snap.paragraphs()) {
                    addHighlightedPara(commUi, p, 6f);
                }
            }
        } else {
            for (int i = 0; i < snap.commEntries().size(); i++) {
                CoopDialogSpectatorState.CommEntryView entry = snap.commEntries().get(i);
                boolean hovered = i == snap.hoveredOptionIndex();
                boolean voted = i == activeVote;

                String portrait = entry.portraitSprite();
                if (!portrait.isEmpty()) {
                    try {
                        TooltipMakerAPI row = commUi.beginImageWithText(portrait, 40f);
                        String titleLine = (hovered ? "> " : "")
                                + (voted ? CoopUiLang.tr("[VOTE] ", "[ГОЛОС] ") : "")
                                + entry.name()
                                + (hovered ? "  <-- " + snap.speakerName() : "");
                        row.addPara(titleLine, voted ? OPT_VOTE_TEXT : (hovered ? OPT_HOVER_TEXT : HEADER_GREEN), 2f);
                        String meta = entry.post()
                                + (!entry.post().isEmpty() && !entry.faction().isEmpty() ? "  ·  " : "")
                                + entry.faction();
                        if (!meta.isEmpty()) {
                            row.addPara(meta, TARGET_CYAN, 2f);
                        }
                        commUi.addImageWithText(8f);
                    } catch (Throwable ignored) {
                        commUi.addPara(entry.name() + " — " + entry.post(), BODY_TEXT, 6f);
                    }
                } else {
                    commUi.addPara(entry.name() + (entry.post().isEmpty() ? "" : " — " + entry.post()),
                            hovered ? OPT_HOVER_TEXT : BODY_TEXT, 8f);
                }

                ButtonAPI btn = commUi.addButton(
                        (voted ? CoopUiLang.tr("[VOTED] Talk to ", "[ГОЛОС] Поговорить с ")
                                : CoopUiLang.tr("Suggest: Talk to ", "Посоветовать: ")) + entry.name(),
                        ID_VOTE_PREFIX + i,
                        voted ? OPT_VOTE_TEXT : (hovered ? OPT_HOVER_TEXT : OPT_TEXT),
                        voted ? OPT_VOTE_BG : (hovered ? OPT_HOVER_BG : OPT_BG),
                        Alignment.LMID, CutStyle.ALL, innerW - 18f, 22f, 3f);
                btn.setEnabled(true);
            }
        }
        panel.addUIElement(commUi).inTL(pad, bodyY);
    }

    private void buildStandardDialogView(CustomPanelAPI panel, CoopDialogSpectatorState.SyncPayload snap,
                                         float pad, float innerW, float bodyY, float bodyH) {
        int optCount = snap.options().size();
        float optionsH = Math.min(244f, Math.max(92f, optCount * 34f + 36f));
        float textH = bodyH - optionsH - 10f;

        // Scrollable dialog text area with numeric highlights + mentioned commodity icons + auto-scroll to bottom
        TooltipMakerAPI textUi = panel.createUIElement(innerW, textH, true);
        if (snap.paragraphs().isEmpty()) {
            textUi.addPara(CoopUiLang.tr("Waiting for dialog text...", "Ожидание текста диалога..."),
                    MUTED, 6f);
        } else {
            float pPad = 4f;
            for (String para : snap.paragraphs()) {
                addHighlightedPara(textUi, para, pPad);
                pPad = 8f;
            }
        }
        panel.addUIElement(textUi).inTL(pad, bodyY);

        // Auto-scroll to the latest lines (fixes bar/quest dialog not scrolling down!)
        try {
            ScrollPanelAPI scroller = textUi.getExternalScroller();
            if (scroller != null) {
                float contentH = textUi.getHeightSoFar();
                if (contentH > textH) {
                    scroller.setYOffset(Math.max(0f, contentH - textH + 12f));
                }
                scroller.scrollToBottom();
            }
        } catch (Throwable ignored) {
        }

        // Clickable mirrored options + hover & BG3 vote markers
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
                label.append("  <-- ").append(snap.speakerName());
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
        panel.addUIElement(optUi).inTL(pad, bodyY + textH + 10f);
    }

    /**
     * Renders a dialog paragraph with gold highlights on numbers (days, cost, units, %) and
     * commodity names, plus compact inline native commodity icons when commodities are mentioned.
     */
    private void addHighlightedPara(TooltipMakerAPI ui, String text, float pad) {
        List<String> highlights = new ArrayList<>(CoopDialogSpectatorState.extractNumberHighlights(text));
        List<CommoditySpecAPI> mentioned = findMentionedCommodities(text);
        for (CommoditySpecAPI spec : mentioned) {
            if (spec.getName() != null && !spec.getName().isEmpty()) {
                highlights.add(spec.getName());
            }
        }
        if (highlights.isEmpty()) {
            ui.addPara(text, BODY_TEXT, pad);
        } else {
            ui.addPara(text, pad, BODY_TEXT, HIGHLIGHT_GOLD, highlights.toArray(new String[0]));
        }
        // Render native commodity icons mentioned in this paragraph (up to 4 icons per paragraph)
        if (!mentioned.isEmpty()) {
            int limit = Math.min(4, mentioned.size());
            for (int i = 0; i < limit; i++) {
                CommoditySpecAPI spec = mentioned.get(i);
                String icon = spec.getIconName();
                if (icon != null && !icon.isEmpty()) {
                    try {
                        TooltipMakerAPI row = ui.beginImageWithText(icon, 20f);
                        String priceHint = Math.round(spec.getBasePrice()) + "¢";
                        row.addPara(spec.getName() + " (" + priceHint + ")", 1f, TARGET_CYAN, HIGHLIGHT_GOLD, priceHint);
                        ui.addImageWithText(3f);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private List<CommoditySpecAPI> findMentionedCommodities(String text) {
        if (text == null || text.length() < 3) {
            return List.of();
        }
        ensureCommodityCache();
        if (commodityByLowerName == null || commodityByLowerName.isEmpty()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        Set<String> addedIds = new LinkedHashSet<>();
        List<CommoditySpecAPI> out = new ArrayList<>();
        for (Map.Entry<String, CommoditySpecAPI> e : commodityByLowerName.entrySet()) {
            String key = e.getKey();
            if (key.length() < 4) {
                continue;
            }
            if (lower.contains(key)) {
                CommoditySpecAPI spec = e.getValue();
                if (addedIds.add(spec.getId())) {
                    out.add(spec);
                    if (out.size() >= 4) {
                        break;
                    }
                }
            }
        }
        return out;
    }

    private void ensureCommodityCache() {
        if (commodityByLowerName != null) {
            return;
        }
        Map<String, CommoditySpecAPI> map = new LinkedHashMap<>();
        try {
            for (CommoditySpecAPI spec : Global.getSettings().getAllCommoditySpecs()) {
                if (spec == null || spec.getId() == null || spec.getName() == null) {
                    continue;
                }
                if (spec.isMeta() || spec.isNonEcon()) {
                    continue;
                }
                String name = spec.getName().trim().toLowerCase(Locale.ROOT);
                if (name.length() >= 4) {
                    map.putIfAbsent(name, spec);
                }
            }
        } catch (Throwable ignored) {
        }
        commodityByLowerName = map;
    }

    private static String commodityIcon(String commodityId) {
        if (commodityId == null || commodityId.isEmpty()) {
            return "";
        }
        try {
            CommoditySpecAPI spec = Global.getSettings().getCommoditySpec(commodityId);
            return spec == null || spec.getIconName() == null ? "" : spec.getIconName();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String viewModeBadge(String viewMode) {
        return switch (viewMode == null ? "DIALOG" : viewMode) {
            case "TRADE" -> CoopUiLang.tr("Trade / Market", "Торговля / Рынок");
            case "COMM" -> CoopUiLang.tr("Comm Directory", "Каталог коммуникаций");
            case "FLEET" -> CoopUiLang.tr("Fleet screen", "Экран флота");
            case "REFIT" -> CoopUiLang.tr("Refit screen", "Переоборудование");
            default -> CoopUiLang.tr("Live dialog", "Прямой эфир");
        };
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
            String speaker = currentSpeakerName == null || currentSpeakerName.isBlank()
                    ? CoopUiLang.tr("partner", "напарника") : currentSpeakerName;
            CoopFeed.post(
                    CoopUiLang.tr(
                            "Minimized dialog of \"" + speaker + "\" — press F6 or click the top banner to rejoin.",
                            "Диалог игрока «" + speaker + "» свёрнут — нажмите F6 или кнопку сверху экрана, чтобы вернуться."),
                    HEADER_GREEN, CoopFeed.Severity.INFO);
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
            CoopTitleScreenOverlay.drawRect(px, py + ph - 64f, pw, 64f, 0.06f, 0.16f, 0.14f, 0.95f * alphaMult);
            CoopTitleScreenOverlay.drawBorder(px, py, pw, ph, 0.28f, 0.88f, 0.51f, 0.95f * alphaMult);
        }

        @Override
        public void render(float alphaMult) {
            float cx = px + 14f + smoothCursorX * Math.max(1f, pw - 28f);
            float cy = py + 14f + smoothCursorY * Math.max(1f, ph - 78f);
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
            handleSpectatorButton(buttonId);
        }
    }

    private final class TableRowPlugin implements CustomUIPanelPlugin {
        @Override public void positionChanged(PositionAPI pos) {}
        @Override public void renderBelow(float alphaMult) {}
        @Override public void render(float alphaMult) {}
        @Override public void advance(float amount) {}
        @Override public void processInput(List<InputEventAPI> events) {}

        @Override
        public void buttonPressed(Object buttonId) {
            handleSpectatorButton(buttonId);
        }
    }

    private void handleSpectatorButton(Object buttonId) {
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

    private void drawPartnerCursor(float x, float y, float alpha) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

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
