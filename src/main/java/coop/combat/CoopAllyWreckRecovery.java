package coop.combat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.DModManager;
import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Post-battle allied wreck recovery: allows an allied fleet owner to recover eligible ships that
 * were destroyed while fighting beside a partner (with vanilla D-mods and reduced starting hull/CR),
 * closing the gap where vanilla's {@code FleetEncounterContext} offers recovery only for the pilot's
 * own fleet.
 */
public final class CoopAllyWreckRecovery {

    public static final float RECOVERED_HULL_FRACTION = 0.25f;
    public static final float RECOVERED_CR = 0.15f;
    public static final int DEFAULT_DMODS_ON_RECOVERY = 2;

    private CoopAllyWreckRecovery() {
    }

    /** A destroyed allied ship snapshot eligible for post-battle recovery by its owner. */
    public record RecoverableShip(String memberId, String hullId, String variantId,
                                  String shipName, int existingDMods, int supplyCost) {
        public RecoverableShip {
            memberId = memberId == null ? "" : memberId.trim();
            hullId = hullId == null ? "" : hullId.trim();
            variantId = variantId == null ? "" : variantId.trim();
            shipName = shipName == null ? "" : shipName.trim();
            existingDMods = Math.max(0, existingDMods);
            supplyCost = Math.max(0, supplyCost);
        }
    }

    /** Engine seam so recovery can be tested deterministically without a live SectorAPI. */
    public interface FleetTarget {
        float availableSupplies();

        void consumeSupplies(float amount);

        boolean restoreHullWithDMods(RecoverableShip ship, int addedDMods,
                                     float hullFraction, float cr);
    }

    /**
     * Recovers {@code ship} onto {@code target} if the owner has enough supplies (or {@code useStoryPoint}
     * is true, which waives supply cost and adds only 1 D-mod instead of 2).
     */
    public static boolean recover(RecoverableShip ship, boolean useStoryPoint, FleetTarget target) {
        Objects.requireNonNull(ship, "ship");
        Objects.requireNonNull(target, "target");
        if (!useStoryPoint && target.availableSupplies() < ship.supplyCost()) {
            return false;
        }
        int addedDMods = useStoryPoint ? 1 : DEFAULT_DMODS_ON_RECOVERY;
        boolean ok = target.restoreHullWithDMods(ship, addedDMods,
                RECOVERED_HULL_FRACTION, RECOVERED_CR);
        if (ok && !useStoryPoint && ship.supplyCost() > 0) {
            target.consumeSupplies(ship.supplyCost());
        }
        return ok;
    }

    /** Filters {@code preBattleRoster} to ships named in {@code outcome.recoverableDestroyedIds()}. */
    public static List<RecoverableShip> extractRecoverable(List<RecoverableShip> preBattleRoster,
                                                           CoopAllyBattleOutcome outcome) {
        if (preBattleRoster == null || outcome == null || outcome.recoverableDestroyedIds().isEmpty()) {
            return List.of();
        }
        List<RecoverableShip> result = new ArrayList<>();
        for (RecoverableShip candidate : preBattleRoster) {
            if (candidate != null && outcome.recoverableDestroyedIds().contains(candidate.memberId())) {
                result.add(candidate);
            }
        }
        return List.copyOf(result);
    }

    /** Production engine adapter restoring recovered allied ships onto the local player fleet. */
    public static FleetTarget liveTarget(CampaignFleetAPI playerFleet) {
        Objects.requireNonNull(playerFleet, "playerFleet");
        return new FleetTarget() {
            @Override
            public float availableSupplies() {
                return playerFleet.getCargo() == null ? 0f : playerFleet.getCargo().getSupplies();
            }

            @Override
            public void consumeSupplies(float amount) {
                if (playerFleet.getCargo() != null && amount > 0f) {
                    playerFleet.getCargo().removeSupplies(amount);
                }
            }

            @Override
            public boolean restoreHullWithDMods(RecoverableShip ship, int addedDMods,
                                                float hullFraction, float cr) {
                try {
                    String variant = ship.variantId().isEmpty()
                            ? ship.hullId() + "_Hull" : ship.variantId();
                    FleetMemberAPI restored = Global.getFactory().createFleetMember(
                            FleetMemberType.SHIP, variant);
                    if (restored == null) {
                        return false;
                    }
                    if (!ship.shipName().isEmpty()) {
                        restored.setShipName(ship.shipName());
                    }
                    if (restored.getStatus() != null) {
                        restored.getStatus().setHullFraction(hullFraction);
                    }
                    if (restored.getRepairTracker() != null) {
                        restored.getRepairTracker().setCR(cr);
                    }
                    if (addedDMods > 0 && restored.getVariant() != null) {
                        DModManager.addDMods(restored, false, addedDMods,
                                new Random(ship.memberId().hashCode()));
                    }
                    playerFleet.getFleetData().addFleetMember(restored);
                    CoopLog.info(CoopAllyWreckRecovery.class,
                            "Recovered allied wreck " + restored.getShipName() + " (+" + addedDMods + " D-mods)");
                    return true;
                } catch (RuntimeException | LinkageError ex) {
                    CoopLog.warn(CoopAllyWreckRecovery.class,
                            "Could not restore allied wreck " + ship.hullId(), ex);
                    return false;
                }
            }
        };
    }
}
