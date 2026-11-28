package coop.save;

import com.fs.starfarer.api.Global;
import coop.ui.CoopFeed;
import coop.ui.CoopHudNotice;
import coop.ui.CoopUiLang;
import coop.util.CoopLog;

import java.util.List;

/**
 * Follows the host's save load (Item: "one player loads, everyone loads").
 *
 * <p><b>The mod cannot press Load for the player.</b> Starsector exposes no programmatic save load:
 * {@code CampaignUIAPI.cmdLoad()} only opens the picker, {@code LoadGameDialog} and the engine's
 * own loader are fully obfuscated, and the mod runs under a script classloader that refuses both
 * {@code java.io} and {@code java.lang.reflect}, so the engine entry point is not merely unsupported
 * but unreachable (this mod has recorded that engine fact since Phase 14). What <em>is</em> possible
 * is everything around the click: the host tells the partner which campaign it just loaded before
 * the transport goes down, this side resolves the matching local save by campaign id, and the load
 * screen is opened for the player with the reason on screen.
 *
 * <p>The save is resolved from the <b>local</b> index by campaign id, never by the host's folder
 * name - the two installs name their folders differently by design.
 */
public final class CoopSaveLoadSync {

    private CoopSaveLoadSync() {
    }

    /** The newest local save belonging to {@code campaignId}, or null when this install has none. */
    public static CoopSaveIndexSchema.Row findLocalSave(String campaignId) {
        if (campaignId == null || campaignId.isBlank()) {
            return null;
        }
        try {
            List<CoopSaveIndexSchema.Row> rows = CoopSaveIndex.readRows();
            CoopSaveIndexSchema.Row row = CoopSaveIndex.newestForCampaign(rows, campaignId.trim());
            if (row == null || row.saveDirName().isBlank()) {
                return null;
            }
            return row;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopSaveLoadSync.class,
                    "Coop could not read the save index for campaign " + campaignId, ex);
            return null;
        }
    }

    /** True when this install holds a save of {@code campaignId}. */
    public static boolean hasLocalSave(String campaignId) {
        return findLocalSave(campaignId) != null;
    }

    /**
     * Brings up the vanilla load screen for a campaign the partner has just loaded, with a notice
     * naming what to pick. Returns false when this install has no save of that campaign - there is
     * nothing to open, and the partner needs to know that instead of a picker full of wrong saves.
     */
    public static boolean openLoadFor(String campaignId, String characterName) {
        CoopSaveIndexSchema.Row row = findLocalSave(campaignId);
        if (row == null) {
            String missing = CoopUiLang.tr(
                    "Co-op: your partner loaded a campaign you have no save of. Ask them to send"
                            + " you the save, or start the campaign from their seed.",
                    "Кооп: напарник загрузил кампанию, сейва которой у вас нет. Попросите у него сейв"
                            + " или начните кампанию с его сида.");
            post(missing);
            CoopLog.warn(CoopSaveLoadSync.class, "Co-op load follow: no local save for campaign "
                    + campaignId);
            return false;
        }
        String who = row.characterName().isBlank() ? "your character" : row.characterName();
        String notice = CoopUiLang.tr(
                "Co-op: your partner loaded campaign " + campaignId.substring(0,
                        Math.min(8, campaignId.length())) + ". Load your save "
                        + (row.characterName().isBlank() ? "" : "\"" + who + "\" ")
                        + "from the list to follow them.",
                "Кооп: напарник загрузил кампанию " + campaignId.substring(0,
                        Math.min(8, campaignId.length())) + ". Загрузите свой сейв"
                        + (row.characterName().isBlank() ? "" : " («" + who + "») ")
                        + "из списка, чтобы продолжить вместе.");
        post(notice);
        CoopLog.info(CoopSaveLoadSync.class, "Co-op load follow: campaign=" + campaignId
                + " localSave=" + row.saveDirName() + " character=" + row.characterName());
        try {
            if (Global.getSector() != null && Global.getSector().getCampaignUI() != null) {
                Global.getSector().getCampaignUI().cmdLoad();
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopSaveLoadSync.class, "Coop could not open the load screen", ex);
            return false;
        }
        return true;
    }

    private static void post(String line) {
        try {
            CoopHudNotice.show(line);
        } catch (RuntimeException | LinkageError ignored) {
            // A missing HUD is not a reason to lose the load follow-up.
        }
        try {
            CoopFeed.post(line, null, CoopFeed.Severity.IMPORTANT);
        } catch (RuntimeException | LinkageError ignored) {
            // Same.
        }
    }
}
