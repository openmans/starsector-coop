package coop.combat.live;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatUIAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import coop.combat.live.CoopCombatNetCodec.PilotInput;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.List;

/**
 * Captures real-time local piloting inputs on the joining partner's combat client (LWJGL keyboard,
 * mouse buttons, world-space mouse aim via {@link ViewportAPI}, weapon group selection, autofire
 * toggles, ship-switch key {@code F8}/{@code ]}/{@code [}, and synchronized combat pause
 * {@code Space}).
 */
public final class CoopCombatInputCapture {

    public record CaptureResult(PilotInput input, int shipSwitchDelta, boolean pauseTogglePressed) {
    }

    private int inputSeq;
    private int selectedGroup;
    private int autofireMask;
    private boolean autofireInitialized;
    private boolean desiredShieldOn;
    private long lastLocalShieldClickAtMillis;
    private boolean prevRmbDown;
    private boolean prevF8Down;
    private boolean prevBracketNextDown;
    private boolean prevBracketPrevDown;
    private boolean prevSpaceDown;

    public void reset() {
        inputSeq = 0;
        selectedGroup = 0;
        autofireMask = 0;
        autofireInitialized = false;
        desiredShieldOn = false;
        lastLocalShieldClickAtMillis = 0L;
        prevRmbDown = false;
        prevF8Down = false;
        prevBracketNextDown = false;
        prevBracketPrevDown = false;
        prevSpaceDown = false;
    }

    public void resetForShipSwitch(ShipAPI newShip) {
        selectedGroup = 0;
        autofireInitialized = false;
        desiredShieldOn = false;
        lastLocalShieldClickAtMillis = 0L;
        syncDefaultsFromShip(newShip);
    }

    private void syncDefaultsFromShip(ShipAPI ship) {
        if (ship == null || autofireInitialized) {
            return;
        }
        autofireInitialized = true;
        int mask = 0;
        List<WeaponGroupAPI> groups = ship.getWeaponGroupsCopy();
        if (groups != null) {
            for (int i = 0; i < Math.min(groups.size(), 7); i++) {
                WeaponGroupAPI g = groups.get(i);
                if (g != null && g.isAutofiring()) {
                    mask |= (1 << i);
                }
            }
        }
        autofireMask = mask;
        ShieldAPI sh = ship.getShield();
        if (sh != null && sh.getType() != ShieldAPI.ShieldType.NONE) {
            desiredShieldOn = sh.isOn();
        }
    }

    public CaptureResult capture(CombatEngineAPI engine, String battleId,
                                 ShipAPI pilotedShip, int shipNetId,
                                 List<InputEventAPI> events, long nowMillis) {
        syncDefaultsFromShip(pilotedShip);

        CombatUIAPI ui = engine == null ? null : engine.getCombatUI();
        boolean inCommandUi = ui != null && (ui.isShowingCommandUI() || ui.isShowingDeploymentDialog());

        // Keep desiredShieldOn aligned with authoritative ship state if user hasn't clicked RMB in 350ms
        if (pilotedShip != null && nowMillis - lastLocalShieldClickAtMillis > 350L) {
            ShieldAPI sh = pilotedShip.getShield();
            if (sh != null && sh.getType() != ShieldAPI.ShieldType.NONE) {
                desiredShieldOn = sh.isOn();
            } else {
                ShipSystemAPI phase = pilotedShip.getPhaseCloak();
                if (phase != null) {
                    desiredShieldOn = phase.isActive() || phase.isOn();
                }
            }
        }

        int switchDelta = 0;
        boolean pausePressed = false;
        boolean rmbClicked = false;

        if (events != null) {
            for (InputEventAPI ev : events) {
                if (ev == null || ev.isConsumed()) {
                    continue;
                }
                if (ev.isKeyDownEvent()) {
                    int key = ev.getEventValue();
                    if (key == Keyboard.KEY_F8 || key == Keyboard.KEY_RBRACKET) {
                        switchDelta = 1;
                    } else if (key == Keyboard.KEY_LBRACKET) {
                        switchDelta = -1;
                    } else if (key == Keyboard.KEY_SPACE) {
                        pausePressed = true;
                    } else if (!inCommandUi) {
                        int grp = numberKeyToGroup(key);
                        if (grp >= 0) {
                            boolean ctrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)
                                    || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
                            if (ctrl) {
                                autofireMask ^= (1 << grp);
                            } else {
                                selectedGroup = grp;
                            }
                        }
                    }
                } else if (ev.isMouseDownEvent() && ev.getEventValue() == 1 && !inCommandUi) {
                    rmbClicked = true;
                }
            }
        }

        // Edge fallbacks via LWJGL polling in case Starsector consumed the event before our plugin
        boolean rmbNow = !inCommandUi && safeMouseDown(1);
        if (rmbNow && !prevRmbDown) {
            rmbClicked = true;
        }
        prevRmbDown = rmbNow;

        boolean f8Now = safeKeyDown(Keyboard.KEY_F8);
        if (f8Now && !prevF8Down && switchDelta == 0) {
            switchDelta = 1;
        }
        prevF8Down = f8Now;

        boolean brNext = safeKeyDown(Keyboard.KEY_RBRACKET);
        if (brNext && !prevBracketNextDown && switchDelta == 0) {
            switchDelta = 1;
        }
        prevBracketNextDown = brNext;

        boolean brPrev = safeKeyDown(Keyboard.KEY_LBRACKET);
        if (brPrev && !prevBracketPrevDown && switchDelta == 0) {
            switchDelta = -1;
        }
        prevBracketPrevDown = brPrev;

        boolean spaceNow = safeKeyDown(Keyboard.KEY_SPACE);
        if (spaceNow && !prevSpaceDown) {
            pausePressed = true;
        }
        prevSpaceDown = spaceNow;

        int mask = 0;
        if (!inCommandUi && pilotedShip != null && pilotedShip.isAlive() && !pilotedShip.isHulk()) {
            boolean strafeHeld = safeKeyDown(Keyboard.KEY_LSHIFT) || safeKeyDown(Keyboard.KEY_RSHIFT)
                    || (ui != null && ui.isStrafeToggledOn());

            if (safeKeyDown(Keyboard.KEY_W) || safeKeyDown(Keyboard.KEY_UP)) {
                mask |= CoopCombatNetCodec.CMD_ACCEL;
            }
            if (safeKeyDown(Keyboard.KEY_S) || safeKeyDown(Keyboard.KEY_DOWN)) {
                mask |= CoopCombatNetCodec.CMD_BACK;
            }
            if (safeKeyDown(Keyboard.KEY_X)) {
                mask |= CoopCombatNetCodec.CMD_DECEL;
            }
            if (safeKeyDown(Keyboard.KEY_A) || safeKeyDown(Keyboard.KEY_LEFT)) {
                mask |= strafeHeld ? CoopCombatNetCodec.CMD_STRAFE_LEFT : CoopCombatNetCodec.CMD_TURN_LEFT;
            }
            if (safeKeyDown(Keyboard.KEY_D) || safeKeyDown(Keyboard.KEY_RIGHT)) {
                mask |= strafeHeld ? CoopCombatNetCodec.CMD_STRAFE_RIGHT : CoopCombatNetCodec.CMD_TURN_RIGHT;
            }
            if (safeKeyDown(Keyboard.KEY_Q)) {
                mask |= CoopCombatNetCodec.CMD_STRAFE_LEFT;
            }
            if (safeKeyDown(Keyboard.KEY_E)) {
                mask |= CoopCombatNetCodec.CMD_STRAFE_RIGHT;
            }
            if (safeMouseDown(0)) {
                mask |= CoopCombatNetCodec.CMD_FIRE | CoopCombatNetCodec.CMD_USE_SELECTED;
            }
            if (safeKeyDown(Keyboard.KEY_F)) {
                mask |= CoopCombatNetCodec.CMD_USE_SYSTEM;
            }
            if (safeKeyDown(Keyboard.KEY_V)) {
                mask |= CoopCombatNetCodec.CMD_VENT;
            }
            if (safeKeyDown(Keyboard.KEY_Z)) {
                mask |= CoopCombatNetCodec.CMD_PULL_FIGHTERS;
            }

            // Shield / Phase Cloak handling
            boolean isPhaseShip = pilotedShip.getPhaseCloak() != null
                    && (pilotedShip.getShield() == null
                    || pilotedShip.getShield().getType() == ShieldAPI.ShieldType.NONE);
            if (isPhaseShip) {
                desiredShieldOn = rmbNow;
                mask |= CoopCombatNetCodec.CMD_SHIELD_HAS_DESIRED;
                if (desiredShieldOn) {
                    mask |= CoopCombatNetCodec.CMD_SHIELD_DESIRED_ON;
                }
            } else {
                if (rmbClicked) {
                    desiredShieldOn = !desiredShieldOn;
                    lastLocalShieldClickAtMillis = nowMillis;
                }
                mask |= CoopCombatNetCodec.CMD_SHIELD_HAS_DESIRED;
                if (desiredShieldOn) {
                    mask |= CoopCombatNetCodec.CMD_SHIELD_DESIRED_ON;
                }
            }
        }

        // Convert mouse screen coordinates to world coordinates via ViewportAPI
        float worldX = pilotedShip != null ? pilotedShip.getLocation().x : 0f;
        float worldY = pilotedShip != null ? pilotedShip.getLocation().y : 0f;
        if (engine != null && engine.getViewport() != null) {
            try {
                ViewportAPI vp = engine.getViewport();
                worldX = vp.convertScreenXToWorldX(Mouse.getX());
                worldY = vp.convertScreenYToWorldY(Mouse.getY());
            } catch (Throwable ignored) {
            }
        }

        inputSeq++;
        PilotInput input = new PilotInput(battleId, inputSeq, shipNetId, mask,
                worldX, worldY, selectedGroup, autofireMask);
        return new CaptureResult(input, switchDelta, pausePressed);
    }

    private static int numberKeyToGroup(int key) {
        return switch (key) {
            case Keyboard.KEY_1 -> 0;
            case Keyboard.KEY_2 -> 1;
            case Keyboard.KEY_3 -> 2;
            case Keyboard.KEY_4 -> 3;
            case Keyboard.KEY_5 -> 4;
            case Keyboard.KEY_6 -> 5;
            case Keyboard.KEY_7 -> 6;
            default -> -1;
        };
    }

    private static boolean safeKeyDown(int key) {
        try {
            return Keyboard.isCreated() && Keyboard.isKeyDown(key);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean safeMouseDown(int button) {
        try {
            return Mouse.isCreated() && Mouse.isButtonDown(button);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
