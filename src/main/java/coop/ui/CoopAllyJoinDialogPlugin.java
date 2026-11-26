package coop.ui;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.util.Misc;
import coop.combat.CoopAllyReinforceCoordinator.JoinChoice;
import coop.combat.CoopAllyReinforceCoordinator.JoinOffer;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Interactive partner reinforcement prompt opened when a partner engages a hostile fleet within
 * {@code coop.allyJoinRange} (up to 3,000+ su). Lets the player choose between:
 * <ul>
 *   <li>Tactical Map command mode (joins with fleet + auto-opens the live tactical map)</li>
 *   <li>Direct ship piloting via Wingman (when Wingman is available/enabled)</li>
 *   <li>AI ally only (joins with fleet, stays on campaign view)</li>
 *   <li>Decline (stay out of this battle)</li>
 * </ul>
 */
public final class CoopAllyJoinDialogPlugin implements InteractionDialogPlugin {

    private final JoinOffer offer;
    private final Consumer<JoinChoice> onDecision;
    private InteractionDialogAPI dialog;
    private boolean decided;

    public CoopAllyJoinDialogPlugin(JoinOffer offer, Consumer<JoinChoice> onDecision) {
        this.offer = Objects.requireNonNull(offer, "offer");
        this.onDecision = Objects.requireNonNull(onDecision, "onDecision");
    }

    @Override
    public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        TextPanelAPI text = dialog.getTextPanel();
        OptionPanelAPI opts = dialog.getOptionPanel();
        text.clear();
        opts.clearOptions();

        text.addPara("Partner Battle Reinforcement", Misc.getHighlightColor());
        text.addPara(offer.fighterName() + " is engaging " + offer.enemySummary()
                + " (" + offer.distanceSu() + " su away).");
        text.addPara("Choose how your fleet should participate in this battle:", Misc.getGrayColor());

        opts.addOption("Join battle + Tactical Map (command your ships)", JoinChoice.JOIN_TACTICAL);
        if (offer.wingmanAvailable()) {
            opts.addOption("Join battle + Direct Piloting (Wingman)", JoinChoice.JOIN_WINGMAN);
        }
        opts.addOption("Join battle (AI officers only)", JoinChoice.JOIN_AI);
        opts.addOption("Decline (stay out of this battle)", JoinChoice.DECLINE);
    }

    @Override
    public void optionSelected(String optionText, Object optionData) {
        if (decided) {
            return;
        }
        decided = true;
        JoinChoice choice = (optionData instanceof JoinChoice c) ? c : JoinChoice.DECLINE;
        if (dialog != null) {
            dialog.dismiss();
        }
        onDecision.accept(choice);
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
        return offer;
    }

    @Override
    public Map<String, MemoryAPI> getMemoryMap() {
        return Map.of();
    }
}
