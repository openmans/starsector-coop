package coop.ui;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.util.Misc;
import coop.trade.CoopTradeSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * In-game direct player-to-player trade dialog (Item 5), backed by {@link CoopTradeSession}.
 */
public final class CoopTradeDialogPlugin implements InteractionDialogPlugin {

    private static final String OPT_ADD_10K = "trade_add_10k";
    private static final String OPT_ADD_50K = "trade_add_50k";
    private static final String OPT_ADD_SUPPLIES = "trade_add_supplies";
    private static final String OPT_ADD_FUEL = "trade_add_fuel";
    private static final String OPT_CLEAR = "trade_clear";
    private static final String OPT_LOCK = "trade_lock";
    private static final String OPT_CONFIRM = "trade_confirm";
    private static final String OPT_CANCEL = "trade_cancel";

    private final CoopTradeSession session;
    private final String localPlayerId;
    private final String partnerName;
    private final Consumer<String> onSyncFrame;
    private final CoopTradeSession.Settlement settlement;

    private InteractionDialogAPI dialog;
    private long draftCredits;
    private int draftSupplies;
    private int draftFuel;

    public CoopTradeDialogPlugin(CoopTradeSession session,
                                 String localPlayerId,
                                 String partnerName,
                                 Consumer<String> onSyncFrame,
                                 CoopTradeSession.Settlement settlement) {
        this.session = Objects.requireNonNull(session, "session");
        this.localPlayerId = Objects.requireNonNull(localPlayerId, "localPlayerId");
        this.partnerName = partnerName == null || partnerName.isBlank() ? "Partner" : partnerName;
        this.onSyncFrame = onSyncFrame == null ? frame -> {} : onSyncFrame;
        this.settlement = Objects.requireNonNull(settlement, "settlement");
    }

    @Override
    public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        render();
    }

    @Override
    public void optionSelected(String optionText, Object optionData) {
        String id = String.valueOf(optionData);
        switch (id) {
            case OPT_ADD_10K -> {
                draftCredits += 10_000L;
                pushOffer();
            }
            case OPT_ADD_50K -> {
                draftCredits += 50_000L;
                pushOffer();
            }
            case OPT_ADD_SUPPLIES -> {
                draftSupplies += 50;
                pushOffer();
            }
            case OPT_ADD_FUEL -> {
                draftFuel += 50;
                pushOffer();
            }
            case OPT_CLEAR -> {
                draftCredits = 0L;
                draftSupplies = 0;
                draftFuel = 0;
                pushOffer();
            }
            case OPT_LOCK -> {
                session.lock(localPlayerId);
                onSyncFrame.accept(CoopTradeSession.encodeOffer(
                        session.tradeId(), localPlayerId, "LOCK", session.offerOf(localPlayerId)));
            }
            case OPT_CONFIRM -> {
                session.confirm(localPlayerId, settlement);
                onSyncFrame.accept(CoopTradeSession.encodeOffer(
                        session.tradeId(), localPlayerId, "CONFIRM", session.offerOf(localPlayerId)));
                if (session.state() == CoopTradeSession.State.COMMITTED) {
                    CoopFeed.post("Co-op trade with " + partnerName + " completed.",
                            Misc.getPositiveHighlightColor(), CoopFeed.Severity.IMPORTANT);
                    dismiss();
                    return;
                }
            }
            case OPT_CANCEL -> {
                session.cancel("player-cancelled");
                onSyncFrame.accept(CoopTradeSession.encodeOffer(
                        session.tradeId(), localPlayerId, "CANCEL", CoopTradeSession.Offer.EMPTY));
                dismiss();
                return;
            }
            default -> {
            }
        }
        render();
    }

    private void pushOffer() {
        List<CoopTradeSession.CargoItem> cargo = new ArrayList<>();
        if (draftSupplies > 0) {
            cargo.add(new CoopTradeSession.CargoItem("supplies", draftSupplies));
        }
        if (draftFuel > 0) {
            cargo.add(new CoopTradeSession.CargoItem("fuel", draftFuel));
        }
        session.updateOffer(localPlayerId, draftCredits, cargo, List.of());
        onSyncFrame.accept(CoopTradeSession.encodeOffer(
                session.tradeId(), localPlayerId, "OFFER", session.offerOf(localPlayerId)));
    }

    private void render() {
        if (dialog == null) {
            return;
        }
        TextPanelAPI text = dialog.getTextPanel();
        OptionPanelAPI opts = dialog.getOptionPanel();
        text.clear();
        opts.clearOptions();

        String partnerId = localPlayerId.equals(session.initiatorId())
                ? session.partnerId() : session.initiatorId();
        CoopTradeSession.Offer mine = session.offerOf(localPlayerId);
        CoopTradeSession.Offer theirs = session.offerOf(partnerId);

        text.addPara("Direct Player Trade with " + partnerName + " [" + session.state() + "]",
                Misc.getHighlightColor());
        text.addPara("Your offer: " + formatOffer(mine));
        text.addPara(partnerName + "'s offer: " + formatOffer(theirs));

        opts.addOption("Add +10,000 credits", OPT_ADD_10K);
        opts.addOption("Add +50,000 credits", OPT_ADD_50K);
        opts.addOption("Add +50 supplies", OPT_ADD_SUPPLIES);
        opts.addOption("Add +50 fuel", OPT_ADD_FUEL);
        opts.addOption("Clear my offer", OPT_CLEAR);
        opts.addOption(mine.locked() ? "Offer locked (waiting for partner)" : "Lock offer", OPT_LOCK);
        if (session.state() == CoopTradeSession.State.LOCKED) {
            opts.addOption("Confirm atomic swap", OPT_CONFIRM);
        }
        opts.addOption("Cancel trade", OPT_CANCEL);
    }

    private static String formatOffer(CoopTradeSession.Offer offer) {
        StringBuilder sb = new StringBuilder();
        sb.append(Misc.getDGSCredits(offer.credits()));
        for (CoopTradeSession.CargoItem item : offer.cargo()) {
            sb.append(", ").append(item.quantity()).append(' ').append(item.commodityId());
        }
        if (!offer.shipMemberIds().isEmpty()) {
            sb.append(", ").append(offer.shipMemberIds().size()).append(" ship(s)");
        }
        if (offer.confirmed()) {
            sb.append(" [CONFIRMED]");
        } else if (offer.locked()) {
            sb.append(" [LOCKED]");
        }
        return sb.toString();
    }

    private void dismiss() {
        if (dialog != null) {
            dialog.dismiss();
        }
    }

    @Override
    public void optionMousedOver(String optionText, Object optionData) {
    }

    @Override
    public void advance(float amount) {
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
