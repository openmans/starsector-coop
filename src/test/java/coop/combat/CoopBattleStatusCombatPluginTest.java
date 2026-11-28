package coop.combat;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The puppet-arena deployment-dialog guard: vanilla shows that screen for the two synthetic marker
 * fleets (one small ship, zero command points) and the streamed battle never appears until it is
 * dismissed. Only the live-combat client arena may suppress it.
 */
class CoopBattleStatusCombatPluginTest {

    @Test
    void theArenaSuppressesTheDeploymentDialog() {
        CombatEngineAPI engine = engine();
        assertTrue(CoopBattleStatusCombatPlugin.shouldSuppressDeploymentDialog(engine, true));
    }

    @Test
    void anOrdinaryBattleKeepsItsDeploymentScreen() {
        CombatEngineAPI engine = engine();
        assertFalse(CoopBattleStatusCombatPlugin.shouldSuppressDeploymentDialog(engine, false));
    }

    @Test
    void noEngineMeansNoSuppression() {
        assertFalse(CoopBattleStatusCombatPlugin.shouldSuppressDeploymentDialog(null, true));
    }

    /** A bare proxy: the decision helper must not touch the engine. */
    private static CombatEngineAPI engine() {
        return (CombatEngineAPI) java.lang.reflect.Proxy.newProxyInstance(
                CombatEngineAPI.class.getClassLoader(),
                new Class<?>[] {CombatEngineAPI.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("toString")) {
                        return "engine";
                    }
                    return coop.testing.ProxyDefaults.defaultValue(method.getReturnType());
                });
    }
}
