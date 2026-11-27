package coop.combat.live;

import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import coop.combat.live.CoopCombatNetCodec.PilotInput;
import coop.util.CoopLog;

import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Authority-side {@link ShipAIPlugin} that drives a partner's ship directly from their live
 * real-time {@link PilotInput} stream.
 *
 * <p>Translates raw pilot commands into Starsector's native {@link ShipCommand} queue and world
 * {@code mouseTarget}, so turning, thrusters, strafing, turret tracking, shields, phase cloak,
 * ship systems, venting, and fighter recall all execute with 100% authentic Starsector physics.
 *
 * <p><b>Safety fallback:</b> if no input packet arrives for {@link #INPUT_TIMEOUT_MILLIS} (e.g.
 * network drop or client exit), this AI automatically hands control back to Starsector's default
 * combat AI so the partner's ship never drifts helplessly under fire.
 */
public final class CoopRemotePilotShipAI implements ShipAIPlugin {

    /** If input stops arriving for 3.5 s, fall back to vanilla AI until input resumes. */
    public static final long INPUT_TIMEOUT_MILLIS = 3_500L;

    private final ShipAPI ship;
    private final int shipNetId;
    private final CoopLiveCombatSession session;
    private final LongSupplier clockMillis;
    private final ShipwideAIFlags aiFlags = new ShipwideAIFlags();
    private final ShipAIConfig aiConfig = new ShipAIConfig();

    private int lastToggleShieldBit;
    private int lastSelectedGroup = -1;
    private int lastAppliedAutofireMask = -1;
    private long attachedAtMillis;
    private boolean fallbackTriggered;

    public CoopRemotePilotShipAI(ShipAPI ship, int shipNetId,
                                 CoopLiveCombatSession session, LongSupplier clockMillis) {
        this.ship = Objects.requireNonNull(ship, "ship");
        this.shipNetId = shipNetId;
        this.session = Objects.requireNonNull(session, "session");
        this.clockMillis = clockMillis == null ? System::currentTimeMillis : clockMillis;
        this.attachedAtMillis = this.clockMillis.getAsLong();
    }

    public int shipNetId() {
        return shipNetId;
    }

    public boolean isFallbackTriggered() {
        return fallbackTriggered;
    }

    @Override
    public void advance(float amount) {
        if (!ship.isAlive() || ship.isHulk()) {
            return;
        }
        long now = clockMillis.getAsLong();
        PilotInput input = session.latestPilotInput();
        long lastInputAt = session.latestPilotInputAtMillis();

        // Give the client up to 8 seconds on initial spawn to load the combat arena before timing out
        long graceAnchor = lastInputAt > 0L ? lastInputAt : attachedAtMillis;
        long timeout = lastInputAt > 0L ? INPUT_TIMEOUT_MILLIS : 8_000L;
        if (now - graceAnchor > timeout) {
            if (!fallbackTriggered) {
                fallbackTriggered = true;
                CoopLog.info(CoopRemotePilotShipAI.class,
                        "Coop remote pilot input timed out for ship netId=" + shipNetId
                                + "; returning ship to AI control");
                try {
                    ship.resetDefaultAI();
                } catch (RuntimeException | LinkageError ignored) {
                }
            }
            return;
        }
        if (input == null) {
            return;
        }
        // Only apply commands targeted at this ship (or wildcard 0)
        if (input.shipNetId() != 0 && input.shipNetId() != shipNetId) {
            return;
        }
        applyInput(input);
    }

    /** Pure input-to-ShipCommand translation (package-visible for deterministic unit tests). */
    void applyInput(PilotInput input) {
        if (ship.getMouseTarget() != null) {
            ship.getMouseTarget().set(input.mouseX(), input.mouseY());
        }
        int mask = input.commandMask();
        if ((mask & CoopCombatNetCodec.CMD_ACCEL) != 0) {
            ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
        }
        if ((mask & CoopCombatNetCodec.CMD_BACK) != 0) {
            ship.giveCommand(ShipCommand.ACCELERATE_BACKWARDS, null, 0);
        }
        if ((mask & CoopCombatNetCodec.CMD_DECEL) != 0) {
            ship.giveCommand(ShipCommand.DECELERATE, null, 0);
        }
        if ((mask & CoopCombatNetCodec.CMD_TURN_LEFT) != 0) {
            ship.giveCommand(ShipCommand.TURN_LEFT, null, 0);
        }
        if ((mask & CoopCombatNetCodec.CMD_TURN_RIGHT) != 0) {
            ship.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
        }
        if ((mask & CoopCombatNetCodec.CMD_STRAFE_LEFT) != 0) {
            ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0);
        }
        if ((mask & CoopCombatNetCodec.CMD_STRAFE_RIGHT) != 0) {
            ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
        }
        if ((mask & CoopCombatNetCodec.CMD_VENT) != 0) {
            ship.giveCommand(ShipCommand.VENT_FLUX, null, 0);
        }
        if ((mask & CoopCombatNetCodec.CMD_USE_SYSTEM) != 0) {
            ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0);
        }
        if ((mask & CoopCombatNetCodec.CMD_HOLD_FIRE) != 0) {
            ship.giveCommand(ShipCommand.HOLD_FIRE, null, 0);
        }
        if ((mask & CoopCombatNetCodec.CMD_PULL_FIGHTERS) != 0) {
            ship.giveCommand(ShipCommand.PULL_BACK_FIGHTERS, null, 0);
        }

        // Level-triggered shield / phase sync (immune to dropped UDP packets)
        if ((mask & CoopCombatNetCodec.CMD_SHIELD_HAS_DESIRED) != 0) {
            boolean wantOn = (mask & CoopCombatNetCodec.CMD_SHIELD_DESIRED_ON) != 0;
            boolean currentlyOn = isShieldOrPhaseActive(ship);
            if (wantOn != currentlyOn) {
                ship.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, null, 0);
            }
        } else {
            int toggleBit = mask & CoopCombatNetCodec.CMD_SHIELD_TOGGLE;
            if (toggleBit != 0 && lastToggleShieldBit == 0) {
                ship.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, null, 0);
            }
            lastToggleShieldBit = toggleBit;
        }

        // Weapon groups selection & autofire state
        List<WeaponGroupAPI> groups = ship.getWeaponGroupsCopy();
        int groupCount = groups == null ? 0 : groups.size();
        if (input.selectedGroup() >= 0 && input.selectedGroup() < groupCount
                && input.selectedGroup() != lastSelectedGroup) {
            lastSelectedGroup = input.selectedGroup();
            ship.giveCommand(ShipCommand.SELECT_GROUP, null, input.selectedGroup());
        }
        if (groupCount > 0 && input.autofireMask() != lastAppliedAutofireMask) {
            lastAppliedAutofireMask = input.autofireMask();
            for (int i = 0; i < Math.min(groupCount, 7); i++) {
                WeaponGroupAPI g = groups.get(i);
                if (g == null) {
                    continue;
                }
                boolean wantAuto = (input.autofireMask() & (1 << i)) != 0;
                if (wantAuto && !g.isAutofiring()) {
                    g.toggleOn();
                } else if (!wantAuto && g.isAutofiring()) {
                    g.toggleOff();
                }
            }
        }

        // Primary fire
        if ((mask & CoopCombatNetCodec.CMD_FIRE) != 0) {
            int grp = input.selectedGroup() >= 0 && input.selectedGroup() < groupCount
                    ? input.selectedGroup() : 0;
            ship.giveCommand(ShipCommand.FIRE, ship.getMouseTarget(), grp);
        }
        if ((mask & CoopCombatNetCodec.CMD_USE_SELECTED) != 0) {
            ship.giveCommand(ShipCommand.USE_SELECTED_GROUP, ship.getMouseTarget(), 0);
        }
    }

    private static boolean isShieldOrPhaseActive(ShipAPI ship) {
        ShieldAPI shield = ship.getShield();
        if (shield != null && shield.getType() != ShieldAPI.ShieldType.NONE) {
            return shield.isOn();
        }
        ShipSystemAPI phase = ship.getPhaseCloak();
        if (phase != null) {
            return phase.isActive() || phase.isOn();
        }
        return false;
    }

    @Override
    public void setDoNotFireDelay(float amount) {
    }

    @Override
    public void forceCircumstanceEvaluation() {
    }

    @Override
    public boolean needsRefit() {
        return false;
    }

    @Override
    public ShipwideAIFlags getAIFlags() {
        return aiFlags;
    }

    @Override
    public void cancelCurrentManeuver() {
    }

    @Override
    public ShipAIConfig getConfig() {
        return aiConfig;
    }
}
