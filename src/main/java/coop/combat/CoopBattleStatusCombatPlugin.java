package coop.combat;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import coop.ui.CoopTitleScreenOverlay;
import coop.util.CoopLog;

import java.util.List;

/**
 * The only mod code that runs while a client is inside a battle (Phase 14).
 *
 * <p>Registered through the mod's {@code data/config/settings.json} {@code "plugins"} map, which is
 * vanilla's documented surface for it ("add EveryFrameCombatPlugins here (with unique keys)",
 * core {@code settings.json}) and the same merge the mod already relies on for
 * {@code newGameSectorProcGen}. The engine instantiates it and adds it to <em>every</em> combat
 * engine, including the refit simulator, missions, and the title-screen sim — so this class carries
 * no gate of its own beyond "is there a bridge"; {@link CoopBattleBridge#onCombatFrame} does the
 * campaign-battle and session checks.
 *
 * <p>It is deliberately a dumb forwarder. All state lives in {@link CoopBattleBridge}, which the
 * campaign pump owns, because this object's lifetime is one battle while the battle's coop identity
 * has to outlive it.
 *
 * <p>Never throws: an exception escaping a combat-plugin frame would take the player's battle down.
 */
public class CoopBattleStatusCombatPlugin extends BaseEveryFrameCombatPlugin {

    private CombatEngineAPI engine;

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        // The live-combat puppet arena starts from two synthetic fleets whose only member is a marker
        // frigate. Vanilla would open the deployment dialog for them - one small ship, zero command
        // points, nothing to deploy - and the streamed battle never gets a chance to appear until the
        // player confirms a screen that has no meaning here. CombatState skips the dialog when the
        // engine says so, and this is the only hook that runs before that decision.
        if (shouldSuppressDeploymentDialog(engine, bridgeArenaActive())) {
            suppressDeploymentDialog(engine);
        }
    }

    /** The bridge's answer, isolated so a null bridge (solo battle, no pump) reads as "no". */
    private static boolean bridgeArenaActive() {
        try {
            CoopBattleBridge bridge = CoopBattleBridge.active();
            return bridge != null && bridge.clientArenaActive();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /** True only for the puppet arena; a normal battle keeps its deployment screen. */
    static boolean shouldSuppressDeploymentDialog(CombatEngineAPI engine, boolean arenaActive) {
        return engine != null && arenaActive;
    }

    /** Reflection because the setter lives on the engine implementation, not on the API interface. */
    private static void suppressDeploymentDialog(CombatEngineAPI engine) {
        try {
            engine.getClass().getMethod("setShowDeploymentDialog", boolean.class).invoke(engine, false);
            CoopLog.info(CoopBattleStatusCombatPlugin.class,
                    "Coop puppet arena: deployment dialog suppressed; the streamed battle is the fleet");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBattleStatusCombatPlugin.class,
                    "Coop could not suppress the puppet arena's deployment dialog", ex);
        }
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        try {
            if (Global.getCurrentState() == GameState.TITLE) {
                CoopTitleScreenOverlay.onTitleFrame();
            }
            CoopBattleBridge bridge = CoopBattleBridge.active();
            if (bridge == null) {
                return;
            }
            CombatEngineAPI target = engine != null ? engine : Global.getCombatEngine();
            if (target == null) {
                return;
            }
            bridge.onCombatFrame(target, amount, events, System.currentTimeMillis());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBattleStatusCombatPlugin.class, "Coop combat status frame failed", ex);
        }
    }
}
