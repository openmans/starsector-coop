package coop.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.AbilityPlugin;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.util.Misc;
import coop.combat.CoopAllyReinforceCoordinator;

import java.util.Map;

/**
 * In-game Co-op Comm Link dialog opened when clicking a partner's fleet mirror on the campaign map.
 * Provides instant access to Direct Trade, Co-op Chat, Fight Alongside toggle, and ally join range info.
 */
public final class CoopPartnerCommPlugin implements InteractionDialogPlugin {

    private static final String ALLY_ABILITY_ID = "coop_ally";
    private static final String OPT_TRADE = "comm_trade";
    private static final String OPT_CHAT = "comm_chat";
    private static final String OPT_TOGGLE_ALLY = "comm_toggle_ally";
    private static final String OPT_CLOSE = "comm_close";

    private final String partnerName;
    private InteractionDialogAPI dialog;

    public CoopPartnerCommPlugin(String partnerName) {
        this.partnerName = partnerName == null || partnerName.isBlank() ? "Partner" : partnerName.trim();
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
            case OPT_TRADE -> {
                dismiss();
                CoopSessionIntelFeed.requestOpenTrade();
            }
            case OPT_CHAT -> {
                dismiss();
                CoopSessionIntelFeed.requestOpenChat();
            }
            case OPT_TOGGLE_ALLY -> {
                toggleAllyAbility();
                render();
            }
            case OPT_CLOSE -> dismiss();
            default -> {
            }
        }
    }

    private void render() {
        if (dialog == null) {
            return;
        }
        TextPanelAPI text = dialog.getTextPanel();
        OptionPanelAPI opts = dialog.getOptionPanel();
        text.clear();
        opts.clearOptions();

        boolean allyOn = isAllyAbilityActive();
        int rangeSu = CoopAllyReinforceCoordinator.configuredJoinRangeSu();

        text.addPara("Co-op Comm Link: " + partnerName, Misc.getHighlightColor());
        text.addPara("Fight Alongside status: " + (allyOn ? "ON" : "OFF")
                + "  |  Partner battle join range: " + rangeSu + " su");
        text.addPara("When either player starts a battle within " + rangeSu
                + " su, the partner receives an interactive prompt to join (Tactical Map, Wingman, or AI).",
                Misc.getGrayColor());

        opts.addOption("Trade with " + partnerName + " (credits / supplies / fuel)", OPT_TRADE);
        opts.addOption("Open Co-op Chat", OPT_CHAT);
        opts.addOption(allyOn ? "Turn Fight Alongside OFF" : "Turn Fight Alongside ON", OPT_TOGGLE_ALLY);
        opts.addOption("Close comm link", OPT_CLOSE);
    }

    private static boolean isAllyAbilityActive() {
        try {
            if (Global.getSector() == null || Global.getSector().getPlayerFleet() == null) {
                return false;
            }
            AbilityPlugin ability = Global.getSector().getPlayerFleet().getAbility(ALLY_ABILITY_ID);
            return ability != null && ability.isActive();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static void toggleAllyAbility() {
        try {
            if (Global.getSector() == null || Global.getSector().getPlayerFleet() == null) {
                return;
            }
            AbilityPlugin ability = Global.getSector().getPlayerFleet().getAbility(ALLY_ABILITY_ID);
            if (ability != null) {
                if (ability.isActive()) {
                    ability.deactivate();
                } else {
                    ability.activate();
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // best-effort toggle
        }
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
        return partnerName;
    }

    @Override
    public Map<String, MemoryAPI> getMemoryMap() {
        return Map.of();
    }
}
