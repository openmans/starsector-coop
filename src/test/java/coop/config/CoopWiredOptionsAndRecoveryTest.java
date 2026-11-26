package coop.config;

import coop.colony.CoopColonizationConsent;
import coop.combat.CoopAllyBattleOutcome;
import coop.combat.CoopAllyWreckRecovery;
import coop.fleet.CoopPresenceIndicator;
import coop.rewards.CoopRewardSplitter;
import coop.ui.CoopFeed;
import coop.ui.CoopOptionsView;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopWiredOptionsAndRecoveryTest {

    @Test
    void noOptionRemainsInert() {
        assertTrue(CoopOptionsView.INERT_KEYS.isEmpty(),
                "All five previously inert options are wired: " + CoopOptionsView.INERT_KEYS);
    }

    @Test
    void incomeSplitSupportsEqualAndHostBanks() {
        CoopRewardSplitter.Split equalHost = CoopRewardSplitter.splitColonyCredits(10_000f, 2, true, "equal");
        assertEquals(5_000L, equalHost.localShare());
        assertEquals(5_000L, equalHost.remoteShare());

        CoopRewardSplitter.Split hostBanksHost = CoopRewardSplitter.splitColonyCredits(10_000f, 2, true, "host-banks");
        assertEquals(10_000L, hostBanksHost.localShare());
        assertEquals(0L, hostBanksHost.remoteShare());

        CoopRewardSplitter.Split hostBanksGuest = CoopRewardSplitter.splitColonyCredits(10_000f, 2, false, "host-banks");
        assertEquals(0L, hostBanksGuest.localShare());
        assertEquals(10_000L, hostBanksGuest.remoteShare());
    }

    @Test
    void lootSplitSupportsEqualAndPilotKeeps() {
        CoopRewardSplitter.Split equal = CoopRewardSplitter.splitSalvageCredits(12_000L, 3, "equal");
        assertEquals(4_000L, equal.localShare());
        assertEquals(8_000L, equal.remoteShare());

        CoopRewardSplitter.Split pilotKeeps = CoopRewardSplitter.splitSalvageCredits(12_000L, 3, "pilot-keeps");
        assertEquals(12_000L, pilotKeeps.localShare());
        assertEquals(0L, pilotKeeps.remoteShare());
    }

    @Test
    void feedVerbosityFiltersMessagesBySeverity() {
        assertTrue(CoopFeed.shouldDisplay(CoopFeed.Severity.INFO, "all"));
        assertTrue(CoopFeed.shouldDisplay(CoopFeed.Severity.IMPORTANT, "all"));
        assertTrue(CoopFeed.shouldDisplay(CoopFeed.Severity.CRITICAL, "all"));

        assertFalse(CoopFeed.shouldDisplay(CoopFeed.Severity.INFO, "important"));
        assertTrue(CoopFeed.shouldDisplay(CoopFeed.Severity.IMPORTANT, "important"));
        assertTrue(CoopFeed.shouldDisplay(CoopFeed.Severity.CRITICAL, "important"));

        assertFalse(CoopFeed.shouldDisplay(CoopFeed.Severity.INFO, "minimal"));
        assertFalse(CoopFeed.shouldDisplay(CoopFeed.Severity.IMPORTANT, "minimal"));
        assertTrue(CoopFeed.shouldDisplay(CoopFeed.Severity.CRITICAL, "minimal"));
    }

    @Test
    void partnerColorParsesNamedPresetsAndHexCodes() {
        assertNull(CoopPresenceIndicator.parsePartnerColor(""));
        assertEquals(new Color(80, 235, 255), CoopPresenceIndicator.parsePartnerColor("cyan"));
        assertEquals(new Color(100, 240, 130), CoopPresenceIndicator.parsePartnerColor("GREEN"));
        assertEquals(new Color(0xFF, 0x88, 0x22), CoopPresenceIndicator.parsePartnerColor("#FF8822"));
        assertEquals(new Color(0x11, 0x22, 0x33), CoopPresenceIndicator.parsePartnerColor("112233"));
        assertNull(CoopPresenceIndicator.parsePartnerColor("not-a-color"));
    }

    @Test
    void colonizationConsentApprovesDeniesAndExpiresRequests() {
        CoopColonizationConsent consent = new CoopColonizationConsent();
        CoopColonizationConsent.Request req1 = new CoopColonizationConsent.Request(
                "r1", "g1", "Pilot A", "planet_1", "Jangala", 1_000L);
        assertTrue(consent.submit(req1));
        assertFalse(consent.submit(req1), "duplicate request id rejected");
        assertEquals(1, consent.pendingCount());
        assertEquals(CoopColonizationConsent.Decision.APPROVED, consent.resolve("r1", true));
        assertNull(consent.resolve("r1", false));

        CoopColonizationConsent.Request req2 = new CoopColonizationConsent.Request(
                "r2", "g2", "Pilot B", "planet_2", "Eochu Bres", 5_000L);
        assertTrue(consent.submit(req2));
        Map<String, CoopColonizationConsent.Request> expired =
                consent.expireOlderThan(5_000L + CoopColonizationConsent.REQUEST_TIMEOUT_MILLIS);
        assertEquals(1, expired.size());
        assertTrue(expired.containsKey("r2"));
        assertEquals(0, consent.pendingCount());
    }

    @Test
    void allyWreckRecoveryRestoresEligibleShipsWithSuppliesOrStoryPoint() {
        CoopAllyWreckRecovery.RecoverableShip ship = new CoopAllyWreckRecovery.RecoverableShip(
                "m-1", "hammerhead", "hammerhead_Balanced", "ISS Hammer", 0, 25);
        CoopAllyBattleOutcome outcome = new CoopAllyBattleOutcome(
                "guest-1", List.of("m-1"), List.of(), List.of("m-1"));
        List<CoopAllyWreckRecovery.RecoverableShip> eligible =
                CoopAllyWreckRecovery.extractRecoverable(List.of(ship), outcome);
        assertEquals(1, eligible.size());

        List<String> restored = new ArrayList<>();
        final float[] supplies = {10f};
        CoopAllyWreckRecovery.FleetTarget target = new CoopAllyWreckRecovery.FleetTarget() {
            @Override
            public float availableSupplies() {
                return supplies[0];
            }

            @Override
            public void consumeSupplies(float amount) {
                supplies[0] -= amount;
            }

            @Override
            public boolean restoreHullWithDMods(CoopAllyWreckRecovery.RecoverableShip s,
                                                int addedDMods, float hullFraction, float cr) {
                restored.add(s.memberId() + ":" + addedDMods + ":" + hullFraction + ":" + cr);
                return true;
            }
        };

        assertFalse(CoopAllyWreckRecovery.recover(ship, false, target),
                "not enough supplies without story point");
        assertTrue(CoopAllyWreckRecovery.recover(ship, true, target),
                "story point waives supplies and adds 1 D-mod");
        assertEquals("m-1:1:0.25:0.15", restored.get(0));
        assertEquals(10f, supplies[0]);

        supplies[0] = 40f;
        assertTrue(CoopAllyWreckRecovery.recover(ship, false, target));
        assertEquals("m-1:2:0.25:0.15", restored.get(1));
        assertEquals(15f, supplies[0]);
        assertNotNull(CoopOptionsRegistry.require(CoopOptionsRegistry.LAUNCHER_MOD_JAR_DIGESTS));
    }
}
