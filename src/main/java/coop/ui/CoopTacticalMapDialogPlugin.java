package coop.ui;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import coop.combat.CoopBattleOrder;
import coop.combat.CoopBattleStatus;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Phase 22: opt-in, dismissable interaction dialog hosting {@link CoopTacticalMapPanel}.
 */
public final class CoopTacticalMapDialogPlugin implements InteractionDialogPlugin, CoopDismissableDialog {

    private static final String OPTION_CLOSE = "coop_tactical_close";

    private final Supplier<CoopBattleStatus> statusSupplier;
    private final Consumer<CoopBattleOrder.Order> orderSender;
    private final Runnable onClosed;
    private InteractionDialogAPI dialog;
    private CoopTacticalMapPanel panel;
    private boolean dismissed;

    public CoopTacticalMapDialogPlugin(Supplier<CoopBattleStatus> statusSupplier,
                                       Consumer<CoopBattleOrder.Order> orderSender,
                                       Runnable onClosed) {
        this.statusSupplier = Objects.requireNonNull(statusSupplier, "statusSupplier");
        this.orderSender = Objects.requireNonNull(orderSender, "orderSender");
        this.onClosed = Objects.requireNonNull(onClosed, "onClosed");
    }

    public void onOrderAck(CoopBattleOrder.Ack ack) {
        if (panel != null) {
            panel.onOrderAck(ack);
        }
    }

    @Override
    public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        this.panel = new CoopTacticalMapPanel(statusSupplier, orderSender, this::dismiss);
        dialog.setPromptText("Co-op Tactical Map");
        dialog.getOptionPanel().clearOptions();
        dialog.getOptionPanel().addOption("Close tactical map", OPTION_CLOSE);
        CustomPanelAPI custom = dialog.getVisualPanel().showCustomPanel(900f, 620f, panel);
        if (custom != null) {
            custom.getPosition();
        }
    }

    @Override
    public void optionSelected(String optionText, Object optionData) {
        if (OPTION_CLOSE.equals(optionData)) {
            dismiss();
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
        return null;
    }

    @Override
    public Map<String, MemoryAPI> getMemoryMap() {
        return null;
    }

    @Override
    public void close() {
        if (dismissed) {
            return;
        }
        dismissed = true;
        if (dialog != null) {
            try {
                dialog.dismiss();
            } catch (RuntimeException | LinkageError ignored) {
                // best-effort close
            }
        }
        onClosed.run();
    }

    public void dismiss() {
        close();
    }

    @Override
    public String bridgeTitle() {
        return "Co-op Tactical Map";
    }
}
