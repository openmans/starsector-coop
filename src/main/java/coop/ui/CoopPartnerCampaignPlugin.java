package coop.ui;

import com.fs.starfarer.api.PluginPick;
import com.fs.starfarer.api.campaign.BaseCampaignPlugin;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import coop.fleet.CoopMirrorTags;

/**
 * Transient campaign plugin that intercepts clicks / comm-link hail attempts on a partner's
 * mirrored fleet ({@link CoopMirrorTags#PLAYER_MIRROR_TAG}) and opens {@link CoopPartnerCommPlugin}
 * instead of vanilla's non-interactive same-faction fleet response.
 */
public final class CoopPartnerCampaignPlugin extends BaseCampaignPlugin {

    public static final String ID = "coop_partner_comm_plugin";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public boolean isTransient() {
        return true;
    }

    @Override
    public PluginPick<InteractionDialogPlugin> pickInteractionDialogPlugin(SectorEntityToken interactionTarget) {
        if (!(interactionTarget instanceof CampaignFleetAPI fleet)) {
            return null;
        }
        try {
            MemoryAPI mem = fleet.getMemoryWithoutUpdate();
            if (mem == null || !mem.getBoolean(CoopMirrorTags.PLAYER_MIRROR_TAG)) {
                return null;
            }
            String label = fleet.getName();
            if (label != null && label.startsWith("partner ")) {
                label = label.substring("partner ".length()).trim();
            }
            return new PluginPick<>(new CoopPartnerCommPlugin(label), PickPriority.MOD_GENERAL);
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }
}
