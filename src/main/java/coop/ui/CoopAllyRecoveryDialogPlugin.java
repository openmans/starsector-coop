package coop.ui;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.util.Misc;
import coop.combat.CoopAllyWreckRecovery;
import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Post-battle allied wreck recovery, offered to the fleet's owner (Item 2.5).
 *
 * <p>Vanilla offers recovery only for the player's own fleet, so a partner's ships that died
 * fighting beside the player were simply gone. The piloting side reports which allied wrecks the
 * battle left ({@code recoverableDestroyedIds}); this dialog lets the owner spend supplies to take
 * them back, with the same shape vanilla uses: two fresh D-mods, a quarter hull, 15% CR.
 *
 * <p>Loot split (Item 2.4) is applied per wreck, not per dialog: with {@code coop.lootSplit=equal}
 * the recovering owner pays the partner half of the hull's base value through the ordinary
 * {@code CREDITS_GRANT} ledger, so the split survives a reconnect and cannot be double-paid.
 *
 * <p>Every engine call is wrapped: a wreck that cannot be restored is skipped with a log line and
 * the dialog stays usable for the rest.
 */
public final class CoopAllyRecoveryDialogPlugin implements InteractionDialogPlugin, CoopDismissableDialog {

    private static final String OPT_PREFIX = "recover_wreck_";
    private static final String OPT_ALL = "recover_all_wrecks";
    private static final String OPT_LEAVE = "leave_wrecks";

    private final List<CoopAllyWreckRecovery.RecoverableShip> wrecks = new ArrayList<>();
    private final CoopAllyWreckRecovery.FleetTarget target;
    /** Partner's share per recovered member id; empty amounts mean no split is owed. */
    private final Map<String, Long> partnerShareByMemberId;
    /** Called after a successful recovery with (memberId, creditsForPartner). */
    private final RecoverySink onRecovered;

    private InteractionDialogAPI dialog;

    /** What the pump does with a recovered wreck: usually one {@code CREDITS_GRANT}. */
    public interface RecoverySink {
        void recovered(String memberId, long creditsForPartner);
    }

    public CoopAllyRecoveryDialogPlugin(List<CoopAllyWreckRecovery.RecoverableShip> wrecks,
                                        CoopAllyWreckRecovery.FleetTarget target,
                                        Map<String, Long> partnerShareByMemberId,
                                        RecoverySink onRecovered) {
        if (wrecks != null) {
            for (CoopAllyWreckRecovery.RecoverableShip wreck : wrecks) {
                if (wreck != null) {
                    this.wrecks.add(wreck);
                }
            }
        }
        this.target = target;
        this.partnerShareByMemberId = partnerShareByMemberId == null ? Map.of() : partnerShareByMemberId;
        this.onRecovered = onRecovered;
    }

    @Override
    public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        render();
    }

    @Override
    public void optionSelected(String optionText, Object optionData) {
        String id = String.valueOf(optionData);
        if (OPT_LEAVE.equals(id)) {
            close();
            return;
        }
        if (OPT_ALL.equals(id)) {
            recoverAll();
            return;
        }
        if (id.startsWith(OPT_PREFIX)) {
            int index;
            try {
                index = Integer.parseInt(id.substring(OPT_PREFIX.length()));
            } catch (NumberFormatException ex) {
                return;
            }
            if (index >= 0 && index < wrecks.size()) {
                recoverOne(wrecks.get(index));
            }
            render();
        }
    }

    private void recoverAll() {
        // Copy first: recoverOne mutates the list.
        for (CoopAllyWreckRecovery.RecoverableShip wreck : new ArrayList<>(wrecks)) {
            if (!recoverOne(wreck)) {
                // Out of supplies; the rest stay listed for a later look, but the loop would only
                // burn attempts. Break rather than spam one failed log line per wreck.
                break;
            }
        }
        render();
    }

    private boolean recoverOne(CoopAllyWreckRecovery.RecoverableShip wreck) {
        boolean ok;
        try {
            ok = CoopAllyWreckRecovery.recover(wreck, false, target);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopAllyRecoveryDialogPlugin.class,
                    "Coop could not recover allied wreck " + wreck.hullId(), ex);
            return false;
        }
        if (!ok) {
            return false;
        }
        wrecks.remove(wreck);
        long share = partnerShareByMemberId.getOrDefault(wreck.memberId(), 0L);
        if (onRecovered != null) {
            try {
                onRecovered.recovered(wreck.memberId(), share);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopAllyRecoveryDialogPlugin.class,
                        "Coop recovered a wreck but could not settle the loot split", ex);
            }
        }
        return true;
    }

    private void render() {
        if (dialog == null) {
            return;
        }
        TextPanelAPI text = dialog.getTextPanel();
        OptionPanelAPI opts = dialog.getOptionPanel();
        text.clear();
        opts.clearOptions();

        text.addPara(CoopUiLang.tr("Allied Wrecks", "Обломки союзника"), Misc.getHighlightColor());
        if (wrecks.isEmpty()) {
            text.addPara(CoopUiLang.tr("Nothing left to recover.", "Восстанавливать больше нечего."),
                    Misc.getGrayColor());
            opts.addOption(CoopUiLang.tr("Close", "Закрыть"), OPT_LEAVE);
            return;
        }
        float supplies = safeSupplies();
        text.addPara(CoopUiLang.tr(
                "Ships your partner lost fighting beside you can be towed back: a quarter hull, "
                        + "15% CR and two fresh D-mods each. Supplies available: "
                        + Math.round(supplies) + ".",
                "Корабли напарника, погибшие в совместном бою, можно отбуксировать: четверть корпуса, "
                        + "15% CR и два новых D-мода каждому. Запасов: " + Math.round(supplies) + "."),
                Misc.getGrayColor());

        for (int i = 0; i < wrecks.size(); i++) {
            CoopAllyWreckRecovery.RecoverableShip wreck = wrecks.get(i);
            boolean affordable = supplies >= wreck.supplyCost();
            String name = wreck.shipName().isEmpty() ? wreck.hullId() : wreck.shipName();
            long share = partnerShareByMemberId.getOrDefault(wreck.memberId(), 0L);
            String split = share > 0
                    ? CoopUiLang.tr(" (" + share + " cr to your partner)", " (" + share + " кр. напарнику)")
                    : "";
            opts.addOption(CoopUiLang.tr(
                    "Recover " + name + " — " + wreck.supplyCost() + " supplies" + split,
                    "Восстановить " + name + " — " + wreck.supplyCost() + " запасов" + split),
                    OPT_PREFIX + i);
            opts.setEnabled(OPT_PREFIX + i, affordable);
            if (!affordable) {
                opts.setTooltip(OPT_PREFIX + i, CoopUiLang.tr(
                        "Not enough supplies aboard.", "Не хватает запасов."));
            }
        }
        if (wrecks.size() > 1) {
            opts.addOption(CoopUiLang.tr("Recover everything you can afford",
                    "Восстановить всё, на что хватает запасов"), OPT_ALL);
        }
        opts.addOption(CoopUiLang.tr("Leave them", "Оставить обломки"), OPT_LEAVE);
    }

    private float safeSupplies() {
        try {
            return target == null ? 0f : target.availableSupplies();
        } catch (RuntimeException | LinkageError ex) {
            return 0f;
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
        return wrecks;
    }

    @Override
    public Map<String, MemoryAPI> getMemoryMap() {
        return Map.of();
    }

    @Override
    public void close() {
        if (dialog != null) {
            try {
                dialog.dismiss();
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopAllyRecoveryDialogPlugin.class,
                        "Coop could not dismiss the wreck-recovery dialog", ex);
            }
        }
    }
}
