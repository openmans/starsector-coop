package coop.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.econ.CommoditySpecAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.CutStyle;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.ScrollPanelAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import coop.trade.CoopTradeSession;
import coop.util.CoopLog;
import org.lwjgl.input.Keyboard;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Rich native Starsector UI dialog for direct player-to-player commodity exchange and credit
 * transfers, backed by {@link CoopTradeSession}.
 *
 * <p>Features:
 * <ul>
 *   <li>Live auto-refresh via {@link #advance(float)} whenever the partner edits, locks,
 *       unlocks, confirms, or cancels.</li>
 *   <li>Lists every real commodity stack in the player's fleet cargo with native icons,
 *       localized names, owned counts, and clamped {@code -50/-10/-1/+1/+10/+50/All} controls.</li>
 *   <li>Flexible credit transfer with a direct numeric input field plus quick increment buttons
 *       ({@code +1k/+10k/+50k/+100k/Reset}), capped to owned credits.</li>
 *   <li>Two-step mutual confirmation ({@code Lock} -> {@code Confirm}) with automatic unlock on
 *       any edit by either side to prevent bait-and-switch.</li>
 *   <li>Supports both two-way swaps and one-way gifts (e.g. sending credits or supplies with
 *       nothing requested in return).</li>
 * </ul>
 */
public final class CoopTradeDialogPlugin implements InteractionDialogPlugin, CoopDismissableDialog {

    private static final float PANEL_W = 900f;
    private static final float PANEL_H = 620f;
    private static final float HEADER_H = 64f;
    private static final float FOOTER_H = 56f;

    private static final String BTN_CREDITS_SET = "trade.credits.set";
    private static final String BTN_CREDITS_ADD_1K = "trade.credits.add.1000";
    private static final String BTN_CREDITS_ADD_10K = "trade.credits.add.10000";
    private static final String BTN_CREDITS_ADD_50K = "trade.credits.add.50000";
    private static final String BTN_CREDITS_ADD_100K = "trade.credits.add.100000";
    private static final String BTN_CREDITS_ZERO = "trade.credits.zero";
    private static final String BTN_CLEAR_ALL = "trade.clear";
    private static final String BTN_LOCK = "trade.lock";
    private static final String BTN_UNLOCK = "trade.unlock";
    private static final String BTN_CONFIRM = "trade.confirm";
    private static final String BTN_CANCEL = "trade.cancel";
    private static final String BTN_LANG = "trade.lang";
    private static final String BTN_CARGO_PREFIX = "trade.cargo.";

    private static final Color HEADER_GREEN = new Color(110, 255, 165);
    private static final Color TARGET_CYAN = new Color(130, 225, 255);
    private static final Color BODY_TEXT = new Color(224, 240, 250);
    private static final Color GOLD = new Color(255, 214, 102);
    private static final Color MUTED = new Color(145, 185, 205);
    private static final Color WARN_AMBER = new Color(255, 195, 95);
    private static final Color BADGE_LOCKED = new Color(120, 230, 255);
    private static final Color BADGE_CONFIRMED = new Color(110, 255, 155);

    private static final Color BTN_BG = new Color(18, 42, 58);
    private static final Color BTN_TEXT = new Color(210, 240, 255);
    private static final Color BTN_ACCENT_BG = new Color(20, 74, 96);
    private static final Color BTN_CONFIRM_BG = new Color(22, 108, 60);
    private static final Color BTN_CONFIRM_TEXT = new Color(165, 255, 195);
    private static final Color BTN_WARN_BG = new Color(98, 68, 22);
    private static final Color BTN_CANCEL_BG = new Color(96, 30, 34);
    private static final Color BTN_CANCEL_TEXT = new Color(255, 190, 190);
    private static final Color BTN_DISABLED_BG = new Color(28, 36, 44);
    private static final Color BTN_DISABLED_TEXT = new Color(115, 130, 140);

    private final CoopTradeSession session;
    private final String localPlayerId;
    private final String partnerName;
    private final Consumer<String> onSyncFrame;
    private final CoopTradeSession.Settlement settlement;
    private final Runnable onClosed;

    private InteractionDialogAPI dialog;
    private TextFieldAPI creditsField;
    private TooltipMakerAPI cargoListUi;
    private float savedCargoScrollY;

    private long draftCredits;
    private final Map<String, Integer> draftCargo = new LinkedHashMap<>();

    private long lastRenderedRevision = -1L;
    private boolean lastRenderedRussian;
    private boolean dismissed;
    private boolean outcomeNotified;
    private String inlineStatusMessage = "";

    public CoopTradeDialogPlugin(CoopTradeSession session,
                                 String localPlayerId,
                                 String partnerName,
                                 Consumer<String> onSyncFrame,
                                 CoopTradeSession.Settlement settlement) {
        this(session, localPlayerId, partnerName, onSyncFrame, settlement, null);
    }

    public CoopTradeDialogPlugin(CoopTradeSession session,
                                 String localPlayerId,
                                 String partnerName,
                                 Consumer<String> onSyncFrame,
                                 CoopTradeSession.Settlement settlement,
                                 Runnable onClosed) {
        this.session = Objects.requireNonNull(session, "session");
        this.localPlayerId = Objects.requireNonNull(localPlayerId, "localPlayerId");
        this.partnerName = partnerName == null || partnerName.isBlank() ? "Partner" : partnerName.trim();
        this.onSyncFrame = onSyncFrame == null ? frame -> {} : onSyncFrame;
        this.settlement = Objects.requireNonNull(settlement, "settlement");
        this.onClosed = onClosed == null ? () -> {} : onClosed;
        syncDraftFromSession();
    }

    public String tradeId() {
        return session.tradeId();
    }

    @Override
    public String bridgeTitle() {
        return CoopUiLang.tr("Trade with " + partnerName, "Обмен с " + partnerName);
    }

    @Override
    public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        try {
            dialog.hideTextPanel();
            dialog.getOptionPanel().clearOptions();
        } catch (Throwable ignored) {
        }
        rebuild();
    }

    @Override
    public void advance(float amount) {
        if (dismissed) {
            return;
        }
        CoopTradeSession.State st = session.state();
        if (st == CoopTradeSession.State.COMMITTED) {
            notifyCommittedOnce();
            dismissInternal(false);
            return;
        }
        if (st == CoopTradeSession.State.CANCELLED) {
            notifyCancelledOnce();
            dismissInternal(false);
            return;
        }
        long rev = session.revision();
        boolean ru = CoopUiLang.isRussian();
        if (rev != lastRenderedRevision || ru != lastRenderedRussian) {
            syncDraftFromSession();
            rebuild();
        }
    }

    @Override
    public void close() {
        dismissInternal(false);
    }

    private void syncDraftFromSession() {
        CoopTradeSession.Offer mine = session.offerOf(localPlayerId);
        draftCredits = mine.credits();
        draftCargo.clear();
        for (CoopTradeSession.CargoItem item : mine.cargo()) {
            if (item.quantity() > 0 && !item.commodityId().isEmpty()) {
                draftCargo.put(item.commodityId(), item.quantity());
            }
        }
    }

    private void captureScrollPosition() {
        if (cargoListUi == null) {
            return;
        }
        try {
            ScrollPanelAPI scroller = cargoListUi.getExternalScroller();
            if (scroller != null) {
                savedCargoScrollY = scroller.getYOffset();
            }
        } catch (Throwable ignored) {
        }
    }

    private void rebuild() {
        if (dialog == null || dismissed) {
            return;
        }
        captureScrollPosition();
        try {
            lastRenderedRevision = session.revision();
            lastRenderedRussian = CoopUiLang.isRussian();

            String partnerId = session.otherPlayerId(localPlayerId);
            CoopTradeSession.Offer mine = session.offerOf(localPlayerId);
            CoopTradeSession.Offer theirs = session.offerOf(partnerId);
            long ownedCredits = availableCredits();
            Map<String, Integer> ownedCargo = snapshotOwnedCommodities();

            CustomPanelAPI panel = dialog.getVisualPanel().showCustomPanel(
                    PANEL_W, PANEL_H, new TradePanelPlugin());

            // ---- 1. Top Header Bar ----
            TooltipMakerAPI header = panel.createUIElement(PANEL_W, HEADER_H, false);
            LabelAPI title = header.addPara(
                    CoopUiLang.tr("Direct Trade with \"" + partnerName + "\"",
                            "Прямой обмен с «" + partnerName + "»"),
                    HEADER_GREEN, 0f);
            title.setAlignment(Alignment.MID);
            title.getPosition().setSize(PANEL_W - 230f, 24f);
            title.getPosition().inTMid(9f);

            LabelAPI subtitle = header.addPara(headerStatusText(mine, theirs), headerStatusColor(mine, theirs), 0f);
            subtitle.setAlignment(Alignment.MID);
            subtitle.getPosition().setSize(PANEL_W - 230f, 20f);
            subtitle.getPosition().inTMid(36f);

            ButtonAPI langBtn = header.addButton(CoopUiLang.isRussian() ? "EN" : "RU", BTN_LANG,
                    BTN_TEXT, BTN_BG, Alignment.MID, CutStyle.ALL, 42f, 24f, 0f);
            langBtn.getPosition().inTR(98f, 18f);

            ButtonAPI closeBtn = header.addButton(CoopUiLang.tr("Cancel", "Отмена"), BTN_CANCEL,
                    BTN_CANCEL_TEXT, BTN_CANCEL_BG, Alignment.MID, CutStyle.ALL, 78f, 24f, 0f);
            closeBtn.getPosition().inTR(14f, 18f);
            closeBtn.setShortcut(Keyboard.KEY_ESCAPE, true);
            panel.addUIElement(header).inTL(0f, 0f);

            float pad = 16f;
            float gap = 14f;
            float bodyY = HEADER_H + 8f;
            float bodyH = PANEL_H - HEADER_H - FOOTER_H - 16f;
            float leftW = 472f;
            float rightW = PANEL_W - pad * 2f - leftW - gap;
            float rightX = pad + leftW + gap;

            // ---- 2. Left Column: Credits box + Cargo inventory ----
            float creditsBoxH = 104f;
            buildCreditsBox(panel, pad, bodyY, leftW, creditsBoxH, ownedCredits);
            buildCargoList(panel, pad, bodyY + creditsBoxH + 6f, leftW, bodyH - creditsBoxH - 6f, ownedCargo);

            // ---- 3. Right Column: Live Two-Sided Trade Summary ----
            float halfH = (bodyH - 10f) * 0.5f;
            buildOfferSummaryCard(panel, rightX, bodyY, rightW, halfH,
                    CoopUiLang.tr("You give  ->  " + partnerName, "Вы отдаёте  ->  " + partnerName),
                    mine, true);
            buildOfferSummaryCard(panel, rightX, bodyY + halfH + 10f, rightW, halfH,
                    CoopUiLang.tr(partnerName + " gives  ->  You", partnerName + " отдаёт  ->  Вам"),
                    theirs, false);

            // ---- 4. Bottom Action Bar ----
            buildFooterBar(panel, pad, PANEL_H - FOOTER_H + 6f, PANEL_W - pad * 2f, FOOTER_H - 12f, mine, theirs);
        } catch (Throwable ex) {
            CoopLog.warn(CoopTradeDialogPlugin.class, "Could not render direct trade dialog", ex);
        }
    }

    private void buildCreditsBox(CustomPanelAPI panel, float x, float y, float w, float h, long ownedCredits) {
        TooltipMakerAPI ui = panel.createUIElement(w, h, false);
        ui.addSectionHeading(
                CoopUiLang.tr("Credit Transfer", "Передача кредитов"),
                GOLD, new Color(46, 38, 16), Alignment.MID, 0f);

        String availStr = formatCredits(ownedCredits);
        String offerStr = formatCredits(draftCredits);
        LabelAPI balanceLbl = ui.addPara(
                CoopUiLang.tr("Available: " + availStr + "   |   Sending: " + offerStr,
                        "Доступно: " + availStr + "   |   Передаёте: " + offerStr),
                2f, BODY_TEXT, GOLD, availStr, offerStr);
        balanceLbl.getPosition().inTL(4f, 20f);

        creditsField = ui.addTextField(178f, 22f, Fonts.DEFAULT_SMALL, 0f);
        creditsField.setText(draftCredits > 0L ? String.valueOf(draftCredits) : "");
        creditsField.getPosition().inTL(4f, 40f);

        ButtonAPI setBtn = ui.addButton(CoopUiLang.tr("Set amount", "Задать сумму"), BTN_CREDITS_SET,
                BTN_TEXT, BTN_ACCENT_BG, Alignment.MID, CutStyle.ALL, 126f, 22f, 0f);
        setBtn.getPosition().inTL(188f, 40f);

        ButtonAPI zeroBtn = ui.addButton(CoopUiLang.tr("Reset 0¢", "Сброс 0¢"), BTN_CREDITS_ZERO,
                BTN_TEXT, BTN_BG, Alignment.MID, CutStyle.ALL, 92f, 22f, 0f);
        zeroBtn.getPosition().inTL(320f, 40f);

        float qY = 66f;
        float qW = 86f;
        float qGap = 6f;
        ButtonAPI b1 = ui.addButton("+1 000¢", BTN_CREDITS_ADD_1K,
                GOLD, BTN_BG, Alignment.MID, CutStyle.ALL, qW, 20f, 0f);
        b1.getPosition().inTL(4f, qY);

        ButtonAPI b2 = ui.addButton("+10 000¢", BTN_CREDITS_ADD_10K,
                GOLD, BTN_BG, Alignment.MID, CutStyle.ALL, qW, 20f, 0f);
        b2.getPosition().inTL(4f + (qW + qGap), qY);

        ButtonAPI b3 = ui.addButton("+50 000¢", BTN_CREDITS_ADD_50K,
                GOLD, BTN_BG, Alignment.MID, CutStyle.ALL, qW, 20f, 0f);
        b3.getPosition().inTL(4f + (qW + qGap) * 2f, qY);

        ButtonAPI b4 = ui.addButton("+100 000¢", BTN_CREDITS_ADD_100K,
                GOLD, BTN_BG, Alignment.MID, CutStyle.ALL, qW + 10f, 20f, 0f);
        b4.getPosition().inTL(4f + (qW + qGap) * 3f, qY);

        if (!inlineStatusMessage.isEmpty()) {
            LabelAPI statusLbl = ui.addPara(inlineStatusMessage, WARN_AMBER, 0f);
            statusLbl.getPosition().inTL(4f, 88f);
        }

        panel.addUIElement(ui).inTL(x, y);
    }

    private void buildCargoList(CustomPanelAPI panel, float x, float y, float w, float h,
                                Map<String, Integer> ownedCargo) {
        TooltipMakerAPI hdr = panel.createUIElement(w, 42f, false);
        hdr.addSectionHeading(
                CoopUiLang.tr("Your Cargo (tabular transfer)",
                        "Ваш груз (таблица обмена)"),
                TARGET_CYAN, new Color(16, 40, 56), Alignment.MID, 0f);
        RowForwardPlugin rowPlugin = new RowForwardPlugin();
        CustomPanelAPI colHdr = panel.createCustomPanel(w - 16f, 18f, rowPlugin);
        TooltipMakerAPI colUi = colHdr.createUIElement(w - 16f, 18f, false);
        addTableLabel(colUi, CoopUiLang.tr("Item", "Товар"), MUTED, 4f, 1f, 134f);
        addTableLabel(colUi, CoopUiLang.tr("Stock", "Трюм"), MUTED, 142f, 1f, 44f);
        addTableLabel(colUi, CoopUiLang.tr("Offer", "Отдам"), MUTED, 188f, 1f, 46f);
        addTableLabel(colUi, CoopUiLang.tr("Amount", "Кол-во"), MUTED, 238f, 1f, 200f);
        colHdr.addUIElement(colUi).inTL(0f, 0f);
        hdr.addCustom(colHdr, 2f);
        panel.addUIElement(hdr).inTL(x, y);

        cargoListUi = panel.createUIElement(w, Math.max(80f, h - 44f), true);

        if (ownedCargo.isEmpty()) {
            cargoListUi.addPara(CoopUiLang.tr(
                    "No commodities in your fleet cargo. You can still transfer credits above.",
                    "В трюме вашего флота нет товаров. Вы всё равно можете передать кредиты выше."),
                    MUTED, 10f);
        } else {
            float rowW = w - 18f;
            for (Map.Entry<String, Integer> entry : ownedCargo.entrySet()) {
                String commodityId = entry.getKey();
                int owned = entry.getValue();
                int offered = Math.min(owned, draftCargo.getOrDefault(commodityId, 0));
                String name = commodityDisplayName(commodityId);
                String icon = commodityIcon(commodityId);

                CustomPanelAPI rowPanel = panel.createCustomPanel(rowW, 25f, rowPlugin);
                float nameX = 4f;
                if (!icon.isEmpty()) {
                    try {
                        TooltipMakerAPI iconUi = rowPanel.createUIElement(20f, 20f, false);
                        iconUi.addImage(icon, 18f, 18f, 0f);
                        rowPanel.addUIElement(iconUi).inTL(2f, 3f);
                        nameX = 24f;
                    } catch (Throwable ignored) {
                    }
                }
                TooltipMakerAPI rowUi = rowPanel.createUIElement(rowW, 25f, false);
                addTableLabel(rowUi, trimCell(name, 16), offered > 0 ? HEADER_GREEN : BODY_TEXT,
                        nameX, 4f, 138f - nameX);
                addTableLabel(rowUi, String.valueOf(owned), TARGET_CYAN, 142f, 4f, 44f);
                addTableLabel(rowUi, String.valueOf(offered), offered > 0 ? GOLD : MUTED, 188f, 4f, 46f);

                float bx = 238f;
                float bh = 20f;
                bx = addQtyButton(rowUi, "-10", cargoBtnId(commodityId, -10), bx, 34f, bh, offered > 0);
                bx = addQtyButton(rowUi, "-1", cargoBtnId(commodityId, -1), bx, 28f, bh, offered > 0);
                bx = addQtyButton(rowUi, "+1", cargoBtnId(commodityId, 1), bx + 2f, 28f, bh, offered < owned);
                bx = addQtyButton(rowUi, "+10", cargoBtnId(commodityId, 10), bx, 34f, bh, offered < owned);
                bx = addQtyButton(rowUi, CoopUiLang.tr("All", "Всё"), cargoBtnId(commodityId, Integer.MAX_VALUE),
                        bx + 2f, 38f, bh, offered < owned);
                addQtyButton(rowUi, "0", cargoBtnId(commodityId, Integer.MIN_VALUE),
                        bx, 24f, bh, offered > 0);
                rowPanel.addUIElement(rowUi).inTL(0f, 0f);
                cargoListUi.addCustom(rowPanel, 2f);
            }
        }

        panel.addUIElement(cargoListUi).inTL(x, y + 44f);
        if (savedCargoScrollY > 0f) {
            try {
                ScrollPanelAPI scroller = cargoListUi.getExternalScroller();
                if (scroller != null) {
                    scroller.setYOffset(savedCargoScrollY);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void addTableLabel(TooltipMakerAPI ui, String text, Color color,
                                      float x, float y, float w) {
        LabelAPI lbl = ui.addPara(text == null ? "" : text, color, 0f);
        lbl.getPosition().setSize(w, 18f);
        lbl.getPosition().inTL(x, y);
    }

    private static String trimCell(String s, int maxLen) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= maxLen ? t : t.substring(0, Math.max(1, maxLen - 1)) + "…";
    }

    private float addQtyButton(TooltipMakerAPI ui, String label, String id,
                               float x, float w, float h, boolean enabled) {
        ButtonAPI btn = ui.addButton(label, id,
                enabled ? BTN_TEXT : BTN_DISABLED_TEXT,
                enabled ? BTN_BG : BTN_DISABLED_BG,
                Alignment.MID, CutStyle.ALL, w, h, 0f);
        btn.setEnabled(enabled);
        btn.getPosition().inTL(x, 1f);
        return x + w + 4f;
    }

    private void buildOfferSummaryCard(CustomPanelAPI panel, float x, float y, float w, float h,
                                       String title, CoopTradeSession.Offer offer, boolean isLocal) {
        TooltipMakerAPI card = panel.createUIElement(w, h, true);
        Color headingBg = isLocal ? new Color(18, 48, 38) : new Color(22, 42, 62);
        Color headingFg = isLocal ? HEADER_GREEN : TARGET_CYAN;
        card.addSectionHeading(title + "  " + statusBadge(offer), headingFg, headingBg, Alignment.MID, 0f);

        boolean empty = offer.credits() <= 0L && offer.cargo().isEmpty() && offer.shipMemberIds().isEmpty();
        if (offer.credits() > 0L) {
            String cr = formatCredits(offer.credits());
            card.addPara(CoopUiLang.tr("Credits: ", "Кредиты: ") + cr, 6f, BODY_TEXT, GOLD, cr);
        } else {
            card.addPara(CoopUiLang.tr("Credits: 0¢", "Кредиты: 0¢"), MUTED, 6f);
        }

        for (CoopTradeSession.CargoItem item : offer.cargo()) {
            if (item.quantity() <= 0) {
                continue;
            }
            String name = commodityDisplayName(item.commodityId());
            String icon = commodityIcon(item.commodityId());
            String qtyStr = "x" + item.quantity();
            String line = name + "  " + qtyStr;
            if (!icon.isEmpty()) {
                try {
                    TooltipMakerAPI row = card.beginImageWithText(icon, 22f);
                    row.addPara(line, 2f, BODY_TEXT, GOLD, qtyStr);
                    card.addImageWithText(4f);
                } catch (Throwable ignored) {
                    card.addPara(line, 4f, BODY_TEXT, GOLD, qtyStr);
                }
            } else {
                card.addPara(line, 4f, BODY_TEXT, GOLD, qtyStr);
            }
        }

        if (empty) {
            card.addPara(isLocal
                    ? CoopUiLang.tr("Nothing offered yet (credits or cargo).",
                    "Вы пока ничего не добавили (можно оставить пустым при получении подарка).")
                    : CoopUiLang.tr(partnerName + " has not added anything yet (one-way transfer).",
                    partnerName + " пока ничего не добавил (передача в одну сторону)."),
                    MUTED, 8f);
        }

        panel.addUIElement(card).inTL(x, y);
    }

    private void buildFooterBar(CustomPanelAPI panel, float x, float y, float w, float h,
                                CoopTradeSession.Offer mine, CoopTradeSession.Offer theirs) {
        TooltipMakerAPI bar = panel.createUIElement(w, h, false);

        ButtonAPI clearBtn = bar.addButton(CoopUiLang.tr("Clear my offer", "Очистить всё"), BTN_CLEAR_ALL,
                BTN_TEXT, BTN_BG, Alignment.MID, CutStyle.ALL, 146f, 34f, 0f);
        clearBtn.getPosition().inTL(0f, 4f);

        boolean bothLocked = session.state() == CoopTradeSession.State.LOCKED;
        boolean canConfirm = bothLocked && !mine.confirmed();

        if (!mine.locked()) {
            ButtonAPI lockBtn = bar.addButton(
                    CoopUiLang.tr("1. Lock Offer", "1. Зафиксировать"),
                    BTN_LOCK, BTN_TEXT, BTN_ACCENT_BG, Alignment.MID, CutStyle.ALL, 210f, 34f, 0f);
            lockBtn.getPosition().inTL(160f, 4f);
        } else {
            ButtonAPI unlockBtn = bar.addButton(
                    CoopUiLang.tr("Unlock & Edit", "Снять фиксацию"),
                    BTN_UNLOCK, WARN_AMBER, BTN_WARN_BG, Alignment.MID, CutStyle.ALL, 210f, 34f, 0f);
            unlockBtn.getPosition().inTL(160f, 4f);
        }

        String confirmLabel;
        if (mine.confirmed()) {
            confirmLabel = CoopUiLang.tr("Confirmed! Waiting for " + partnerName + "...",
                    "Подтверждено! Ждём " + partnerName + "...");
        } else if (bothLocked) {
            confirmLabel = CoopUiLang.tr("2. CONFIRM TRADE", "2. ПОДТВЕРДИТЬ ОБМЕН");
        } else {
            confirmLabel = CoopUiLang.tr("2. Confirm (both must lock)", "2. Подтвердить (зафиксируйте оба)");
        }
        ButtonAPI confirmBtn = bar.addButton(confirmLabel, BTN_CONFIRM,
                canConfirm ? BTN_CONFIRM_TEXT : BTN_DISABLED_TEXT,
                canConfirm ? BTN_CONFIRM_BG : BTN_DISABLED_BG,
                Alignment.MID, CutStyle.ALL, 310f, 34f, 0f);
        confirmBtn.setEnabled(canConfirm);
        confirmBtn.getPosition().inTL(384f, 4f);

        ButtonAPI cancelBtn = bar.addButton(CoopUiLang.tr("Cancel Trade", "Отменить"), BTN_CANCEL,
                BTN_CANCEL_TEXT, BTN_CANCEL_BG, Alignment.MID, CutStyle.ALL, 158f, 34f, 0f);
        cancelBtn.getPosition().inTL(w - 158f, 4f);

        panel.addUIElement(bar).inTL(x, y);
    }

    private void handleButton(Object buttonId) {
        if (dismissed || !(buttonId instanceof String id)) {
            return;
        }
        inlineStatusMessage = "";
        switch (id) {
            case BTN_LANG -> {
                CoopUiLang.toggle();
                rebuild();
            }
            case BTN_CREDITS_SET -> {
                long typed = parseCreditsInput(creditsField == null ? "" : creditsField.getText());
                long owned = availableCredits();
                if (typed > owned) {
                    inlineStatusMessage = CoopUiLang.tr(
                            "Capped to available credits (" + formatCredits(owned) + ").",
                            "Ограничено вашими кредитами (" + formatCredits(owned) + ").");
                    typed = owned;
                }
                draftCredits = Math.max(0L, typed);
                pushOffer();
                rebuild();
            }
            case BTN_CREDITS_ADD_1K -> adjustCredits(1_000L);
            case BTN_CREDITS_ADD_10K -> adjustCredits(10_000L);
            case BTN_CREDITS_ADD_50K -> adjustCredits(50_000L);
            case BTN_CREDITS_ADD_100K -> adjustCredits(100_000L);
            case BTN_CREDITS_ZERO -> {
                draftCredits = 0L;
                pushOffer();
                rebuild();
            }
            case BTN_CLEAR_ALL -> {
                draftCredits = 0L;
                draftCargo.clear();
                pushOffer();
                rebuild();
            }
            case BTN_LOCK -> {
                clampDraftToOwned();
                // Ensure latest clamped offer is synced before locking if cargo changed
                if (!matchesCurrentOffer()) {
                    pushOffer();
                }
                if (session.lock(localPlayerId)) {
                    onSyncFrame.accept(CoopTradeSession.encodeOffer(
                            session.tradeId(), localPlayerId, "LOCK", session.offerOf(localPlayerId)));
                }
                rebuild();
            }
            case BTN_UNLOCK -> {
                if (session.unlock(localPlayerId)) {
                    onSyncFrame.accept(CoopTradeSession.encodeOffer(
                            session.tradeId(), localPlayerId, "UNLOCK", session.offerOf(localPlayerId)));
                }
                rebuild();
            }
            case BTN_CONFIRM -> {
                if (session.state() != CoopTradeSession.State.LOCKED) {
                    return;
                }
                if (session.confirm(localPlayerId, settlement)) {
                    onSyncFrame.accept(CoopTradeSession.encodeOffer(
                            session.tradeId(), localPlayerId, "CONFIRM", session.offerOf(localPlayerId)));
                }
                if (session.state() == CoopTradeSession.State.COMMITTED) {
                    notifyCommittedOnce();
                    dismissInternal(false);
                    return;
                }
                if (session.state() == CoopTradeSession.State.CANCELLED) {
                    notifyCancelledOnce();
                    dismissInternal(false);
                    return;
                }
                rebuild();
            }
            case BTN_CANCEL -> cancelByLocalUser();
            default -> {
                if (id.startsWith(BTN_CARGO_PREFIX)) {
                    handleCargoButton(id.substring(BTN_CARGO_PREFIX.length()));
                }
            }
        }
    }

    private void adjustCredits(long delta) {
        long owned = availableCredits();
        long next = Math.max(0L, draftCredits + delta);
        if (next > owned) {
            next = owned;
            inlineStatusMessage = CoopUiLang.tr(
                    "Maximum available credits reached (" + formatCredits(owned) + ").",
                    "Достигнут максимум доступных кредитов (" + formatCredits(owned) + ").");
        }
        draftCredits = next;
        pushOffer();
        rebuild();
    }

    private void handleCargoButton(String tail) {
        int sep = tail.lastIndexOf(':');
        if (sep <= 0 || sep >= tail.length() - 1) {
            return;
        }
        String commodityId = tail.substring(0, sep);
        int delta;
        try {
            delta = Integer.parseInt(tail.substring(sep + 1));
        } catch (NumberFormatException ex) {
            return;
        }
        int owned = ownedCommodityQuantity(commodityId);
        int current = draftCargo.getOrDefault(commodityId, 0);
        int next;
        if (delta == Integer.MAX_VALUE) {
            next = owned;
        } else if (delta == Integer.MIN_VALUE) {
            next = 0;
        } else {
            next = Math.max(0, Math.min(owned, current + delta));
        }
        if (next <= 0) {
            draftCargo.remove(commodityId);
        } else {
            draftCargo.put(commodityId, next);
        }
        pushOffer();
        rebuild();
    }

    private void clampDraftToOwned() {
        long ownedCredits = availableCredits();
        if (draftCredits > ownedCredits) {
            draftCredits = ownedCredits;
        }
        Map<String, Integer> ownedCargo = snapshotOwnedCommodities();
        List<String> keys = new ArrayList<>(draftCargo.keySet());
        for (String id : keys) {
            int max = ownedCargo.getOrDefault(id, 0);
            int cur = draftCargo.getOrDefault(id, 0);
            if (max <= 0 || cur <= 0) {
                draftCargo.remove(id);
            } else if (cur > max) {
                draftCargo.put(id, max);
            }
        }
    }

    private boolean matchesCurrentOffer() {
        CoopTradeSession.Offer cur = session.offerOf(localPlayerId);
        if (cur.credits() != draftCredits) {
            return false;
        }
        List<CoopTradeSession.CargoItem> draftList = buildCargoList();
        return cur.cargo().equals(draftList);
    }

    private List<CoopTradeSession.CargoItem> buildCargoList() {
        List<CoopTradeSession.CargoItem> items = new ArrayList<>();
        for (Map.Entry<String, Integer> e : draftCargo.entrySet()) {
            if (e.getValue() > 0) {
                items.add(new CoopTradeSession.CargoItem(e.getKey(), e.getValue()));
            }
        }
        return items;
    }

    private void pushOffer() {
        session.updateOffer(localPlayerId, draftCredits, buildCargoList(), List.of());
        onSyncFrame.accept(CoopTradeSession.encodeOffer(
                session.tradeId(), localPlayerId, "OFFER", session.offerOf(localPlayerId)));
    }

    private void cancelByLocalUser() {
        if (session.state() != CoopTradeSession.State.COMMITTED
                && session.state() != CoopTradeSession.State.CANCELLED) {
            session.cancel("player-cancelled");
            onSyncFrame.accept(CoopTradeSession.encodeOffer(
                    session.tradeId(), localPlayerId, "CANCEL", CoopTradeSession.Offer.EMPTY));
        }
        notifyCancelledOnce();
        dismissInternal(true);
    }

    private void dismissInternal(boolean byUser) {
        if (dismissed) {
            return;
        }
        dismissed = true;
        if (dialog != null) {
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {
            }
        }
        onClosed.run();
    }

    private void notifyCommittedOnce() {
        if (outcomeNotified) {
            return;
        }
        outcomeNotified = true;
        CoopFeed.post(CoopUiLang.tr(
                        "Trade with " + partnerName + " completed!",
                        "Обмен с " + partnerName + " успешно завершён!"),
                Misc.getPositiveHighlightColor(), CoopFeed.Severity.IMPORTANT);
    }

    private void notifyCancelledOnce() {
        if (outcomeNotified) {
            return;
        }
        outcomeNotified = true;
        CoopFeed.post(CoopUiLang.tr(
                        "Trade with " + partnerName + " was cancelled.",
                        "Обмен с " + partnerName + " отменён."),
                WARN_AMBER, CoopFeed.Severity.IMPORTANT);
    }

    private String headerStatusText(CoopTradeSession.Offer mine, CoopTradeSession.Offer theirs) {
        if (session.state() == CoopTradeSession.State.LOCKED) {
            if (mine.confirmed()) {
                return CoopUiLang.tr(
                        "You confirmed! Waiting for " + partnerName + " to confirm...",
                        "Вы подтвердили! Ожидаем подтверждения от " + partnerName + "...");
            }
            if (theirs.confirmed()) {
                return CoopUiLang.tr(
                        partnerName + " confirmed! Press CONFIRM TRADE to complete.",
                        partnerName + " уже подтвердил! Нажмите «ПОДТВЕРДИТЬ ОБМЕН».");
            }
            return CoopUiLang.tr(
                    "Both locked! Review both sides and press CONFIRM TRADE.",
                    "Обе стороны зафиксировали! Проверьте итог и нажмите «ПОДТВЕРДИТЬ ОБМЕН».");
        }
        if (mine.locked() && !theirs.locked()) {
            return CoopUiLang.tr(
                    "Your offer is locked — waiting for " + partnerName + " to lock...",
                    "Ваше предложение зафиксировано — ждём " + partnerName + "...");
        }
        if (!mine.locked() && theirs.locked()) {
            return CoopUiLang.tr(
                    partnerName + " locked their offer! Review and lock yours to proceed.",
                    partnerName + " зафиксировал предложение! Проверьте и зафиксируйте своё.");
        }
        return CoopUiLang.tr(
                "Pick goods and/or credits to transfer, then Lock & Confirm together.",
                "Выберите товары и/или кредиты для передачи, затем зафиксируйте и подтвердите.");
    }

    private Color headerStatusColor(CoopTradeSession.Offer mine, CoopTradeSession.Offer theirs) {
        if (session.state() == CoopTradeSession.State.LOCKED) {
            return BADGE_CONFIRMED;
        }
        if (mine.locked() || theirs.locked()) {
            return GOLD;
        }
        return TARGET_CYAN;
    }

    private static String statusBadge(CoopTradeSession.Offer offer) {
        if (offer.confirmed()) {
            return CoopUiLang.tr("[CONFIRMED]", "[ПОДТВЕРЖДЕНО]");
        }
        if (offer.locked()) {
            return CoopUiLang.tr("[LOCKED]", "[ЗАФИКСИРОВАНО]");
        }
        return CoopUiLang.tr("[EDITING]", "[ВЫБОР]");
    }

    private static String cargoBtnId(String commodityId, int delta) {
        return BTN_CARGO_PREFIX + commodityId + ":" + delta;
    }

    private static long parseCreditsInput(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(digits));
        } catch (NumberFormatException ex) {
            return Long.MAX_VALUE;
        }
    }

    private static String formatCredits(long credits) {
        try {
            return Misc.getDGSCredits(credits);
        } catch (Throwable ignored) {
            return credits + "¢";
        }
    }

    private static long availableCredits() {
        try {
            SectorAPI sector = Global.getSector();
            CampaignFleetAPI fleet = sector == null ? null : sector.getPlayerFleet();
            CargoAPI cargo = fleet == null ? null : fleet.getCargo();
            if (cargo == null || cargo.getCredits() == null) {
                return 0L;
            }
            return Math.max(0L, (long) Math.floor(cargo.getCredits().get()));
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static int ownedCommodityQuantity(String commodityId) {
        if (commodityId == null || commodityId.isBlank()) {
            return 0;
        }
        try {
            SectorAPI sector = Global.getSector();
            CampaignFleetAPI fleet = sector == null ? null : sector.getPlayerFleet();
            CargoAPI cargo = fleet == null ? null : fleet.getCargo();
            if (cargo == null) {
                return 0;
            }
            return Math.max(0, (int) Math.floor(cargo.getCommodityQuantity(commodityId)));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static Map<String, Integer> snapshotOwnedCommodities() {
        Map<String, Integer> totals = new LinkedHashMap<>();
        try {
            SectorAPI sector = Global.getSector();
            CampaignFleetAPI fleet = sector == null ? null : sector.getPlayerFleet();
            CargoAPI cargo = fleet == null ? null : fleet.getCargo();
            if (cargo == null) {
                return totals;
            }
            for (CargoStackAPI stack : cargo.getStacksCopy()) {
                if (stack == null || !stack.isCommodityStack()) {
                    continue;
                }
                String id = stack.getCommodityId();
                int qty = (int) Math.floor(stack.getSize());
                if (id != null && !id.isBlank() && qty > 0) {
                    totals.merge(id, qty, Integer::sum);
                }
            }
        } catch (Throwable ignored) {
            return totals;
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(totals.entrySet());
        sorted.sort(Comparator
                .comparingInt((Map.Entry<String, Integer> e) -> commoditySortPriority(e.getKey()))
                .thenComparing(e -> commodityDisplayName(e.getKey()), String.CASE_INSENSITIVE_ORDER));
        Map<String, Integer> ordered = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : sorted) {
            ordered.put(e.getKey(), e.getValue());
        }
        return ordered;
    }

    private static int commoditySortPriority(String id) {
        return switch (id) {
            case "supplies" -> 0;
            case "fuel" -> 1;
            case "heavy_machinery" -> 2;
            case "crew" -> 3;
            case "marines" -> 4;
            default -> 10;
        };
    }

    private static String commodityDisplayName(String commodityId) {
        if (commodityId == null || commodityId.isBlank()) {
            return "";
        }
        try {
            CommoditySpecAPI spec = Global.getSettings().getCommoditySpec(commodityId);
            if (spec != null && spec.getName() != null && !spec.getName().isBlank()) {
                return spec.getName();
            }
        } catch (Throwable ignored) {
        }
        return commodityId;
    }

    private static String commodityIcon(String commodityId) {
        if (commodityId == null || commodityId.isBlank()) {
            return "";
        }
        try {
            CommoditySpecAPI spec = Global.getSettings().getCommoditySpec(commodityId);
            return spec == null || spec.getIconName() == null ? "" : spec.getIconName();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private final class RowForwardPlugin implements CustomUIPanelPlugin {
        @Override public void positionChanged(PositionAPI position) {}
        @Override public void renderBelow(float alphaMult) {}
        @Override public void render(float alphaMult) {}
        @Override public void advance(float amount) {}
        @Override public void processInput(List<InputEventAPI> events) {}

        @Override
        public void buttonPressed(Object buttonId) {
            handleButton(buttonId);
        }
    }

    private final class TradePanelPlugin implements CustomUIPanelPlugin {
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
            // Main background
            CoopTitleScreenOverlay.drawRect(px, py, pw, ph, 0.04f, 0.08f, 0.13f, 0.97f * alphaMult);
            // Header bar
            CoopTitleScreenOverlay.drawRect(px, py + ph - HEADER_H, pw, HEADER_H,
                    0.06f, 0.16f, 0.14f, 0.96f * alphaMult);
            // Footer bar
            CoopTitleScreenOverlay.drawRect(px, py, pw, FOOTER_H,
                    0.05f, 0.10f, 0.16f, 0.96f * alphaMult);
            // Subtle divider line between columns
            CoopTitleScreenOverlay.drawRect(px + 16f + 472f + 6f, py + FOOTER_H + 6f,
                    2f, ph - HEADER_H - FOOTER_H - 12f,
                    0.22f, 0.56f, 0.44f, 0.45f * alphaMult);
            // Outer border
            CoopTitleScreenOverlay.drawBorder(px, py, pw, ph, 0.28f, 0.88f, 0.51f, 0.95f * alphaMult);
        }

        @Override public void render(float alphaMult) {}
        @Override public void advance(float amount) {}

        @Override
        public void processInput(List<InputEventAPI> events) {
            if (events == null) {
                return;
            }
            for (InputEventAPI ev : events) {
                if (!ev.isConsumed() && ev.isKeyDownEvent() && ev.getEventValue() == Keyboard.KEY_ESCAPE) {
                    ev.consume();
                    cancelByLocalUser();
                    return;
                }
            }
        }

        @Override
        public void buttonPressed(Object buttonId) {
            handleButton(buttonId);
        }
    }

    @Override
    public void optionSelected(String optionText, Object optionData) {
    }

    @Override
    public void optionMousedOver(String optionText, Object optionData) {
    }

    @Override
    public void backFromEngagement(EngagementResultAPI battleResult) {
    }

    @Override
    public Object getContext() {
        return session;
    }

    @Override
    public Map<String, MemoryAPI> getMemoryMap() {
        return Map.of();
    }
}
