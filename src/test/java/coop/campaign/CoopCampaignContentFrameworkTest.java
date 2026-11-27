package coop.campaign;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Functional test framework for campaign content synchronization (Item 4):
 *
 * <ul>
 *   <li>Faction commission monthly salary sharing and hostile-kill combat bounties for guests.</li>
 *   <li>Galatia Academy ({@code $ga*}) and Sindrian Diktat ({@code $sd*}) storyline flag sync and
 *       divergence detection.</li>
 * </ul>
 */
class CoopCampaignContentFrameworkTest {

    /** Reusable in-memory campaign fixture modelling Host + N Guests without Starsector runtime. */
    static final class CampaignFixture {
        final Map<String, Object> hostMemoryMap = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> guestMemoryMaps = new LinkedHashMap<>();
        final CoopCommissionRewardSync commissionSync = new CoopCommissionRewardSync();
        final List<String> grants = new ArrayList<>();

        CampaignFixture(String... guestIds) {
            for (String gid : guestIds) {
                guestMemoryMaps.put(gid, new LinkedHashMap<>());
            }
        }

        CoopStorySyncFramework.FlagMemory hostMemory() {
            return mapMemory(hostMemoryMap);
        }

        CoopStorySyncFramework.FlagMemory guestMemory(String guestId) {
            return mapMemory(guestMemoryMaps.get(guestId));
        }

        void syncStoryToAllGuests() {
            String wire = CoopStorySyncFramework.encode(CoopStorySyncFramework.capture(hostMemory()));
            Map<String, CoopStorySyncFramework.StoryFlag> decoded = CoopStorySyncFramework.decode(wire);
            for (String gid : guestMemoryMaps.keySet()) {
                CoopStorySyncFramework.applyAuthoritative(decoded, guestMemory(gid));
            }
        }

        private static CoopStorySyncFramework.FlagMemory mapMemory(Map<String, Object> backing) {
            return new CoopStorySyncFramework.FlagMemory() {
                @Override
                public Map<String, Object> entries() {
                    return backing;
                }

                @Override
                public void set(String key, Object value) {
                    backing.put(key, value);
                }

                @Override
                public void unset(String key) {
                    backing.remove(key);
                }
            };
        }
    }

    @Test
    void commissionSalaryAndHostileKillBountiesSyncToAllGuestsOnce() {
        CampaignFixture fixture = new CampaignFixture("guest-1", "guest-2");
        CoopCommissionRewardSync.CommissionWorld world = new CoopCommissionRewardSync.CommissionWorld() {
            @Override
            public String commissionFactionId() {
                return "hegemony";
            }

            @Override
            public long monthlySalaryCredits() {
                return 60_000L;
            }

            @Override
            public boolean isHostileToCommission(String commissionFactionId, String targetFactionId) {
                return "hegemony".equals(commissionFactionId) && "tritachyon".equals(targetFactionId);
            }
        };

        CoopCommissionRewardSync.GrantSink sink = (gid, credits, reason) ->
                fixture.grants.add(gid + ":" + credits + ":" + reason);

        long share = fixture.commissionSync.onMonthEnd(
                "c207-m4", List.of("guest-1", "guest-2"), world, sink);
        assertEquals(20_000L, share);
        assertEquals(0L, fixture.commissionSync.onMonthEnd(
                "c207-m4", List.of("guest-1", "guest-2"), world, sink), "same month deduped");
        assertEquals(List.of(
                "guest-1:20000:commission:salary:hegemony:c207-m4",
                "guest-2:20000:commission:salary:hegemony:c207-m4"), fixture.grants);

        long neutralPayout = fixture.commissionSync.onGuestBattleVictory(
                "battle-1", "guest-1", "independent", 15_000L, world, sink);
        assertEquals(0L, neutralPayout, "neutral faction kills earn no commission bounty");

        long hostilePayout = fixture.commissionSync.onGuestBattleVictory(
                "battle-2", "guest-1", "tritachyon", 18_500L, world, sink);
        assertEquals(18_500L, hostilePayout);
        assertEquals(0L, fixture.commissionSync.onGuestBattleVictory(
                "battle-2", "guest-1", "tritachyon", 18_500L, world, sink), "battle deduped");
        assertTrue(fixture.grants.contains("guest-1:18500:commission:bounty:hegemony:battle-2"));
    }

    @Test
    void galatiaAndSindrianDiktatStoryFlagsSyncAndDetectDivergence() {
        CampaignFixture fixture = new CampaignFixture("guest-1", "guest-2");
        // Galatia Academy + Sindrian Diktat flags + untracked noise key
        fixture.hostMemoryMap.put("$gaIntroCompleted", true);
        fixture.hostMemoryMap.put("$gaStage", 3);
        fixture.hostMemoryMap.put("$sdUsurpersStage", "macario_meeting");
        fixture.hostMemoryMap.put("$lionsGuardSuspicion", 0.5f);
        fixture.hostMemoryMap.put("$unrelatedLocalFlag", "stay-local");

        // Before sync, guests diverge on all 4 tracked keys
        CoopStorySyncFramework.DivergenceReport before = CoopStorySyncFramework.diff(
                CoopStorySyncFramework.capture(fixture.hostMemory()),
                CoopStorySyncFramework.capture(fixture.guestMemory("guest-1")));
        assertFalse(before.isSynced());
        assertEquals(4, before.missingOnGuest().size());

        // Sync across wire
        fixture.syncStoryToAllGuests();

        for (String gid : List.of("guest-1", "guest-2")) {
            CoopStorySyncFramework.DivergenceReport after = CoopStorySyncFramework.diff(
                    CoopStorySyncFramework.capture(fixture.hostMemory()),
                    CoopStorySyncFramework.capture(fixture.guestMemory(gid)));
            assertTrue(after.isSynced(), "guest " + gid + " should be synced: " + after);
            assertFalse(fixture.guestMemoryMaps.get(gid).containsKey("$unrelatedLocalFlag"),
                    "untracked local flags must never leak");
        }

        // Introduce a guest-side drift and verify detector catches it and next sync heals it
        fixture.guestMemoryMaps.get("guest-1").put("$gaStage", 99);
        fixture.guestMemoryMaps.get("guest-1").put("$sdRogueFlag", true);
        CoopStorySyncFramework.DivergenceReport drift = CoopStorySyncFramework.diff(
                CoopStorySyncFramework.capture(fixture.hostMemory()),
                CoopStorySyncFramework.capture(fixture.guestMemory("guest-1")));
        assertFalse(drift.isSynced());
        assertEquals(List.of("$gaStage"), drift.mismatchedKeys());
        assertEquals(List.of("$sdRogueFlag"), drift.extraOnGuest());

        fixture.syncStoryToAllGuests();
        assertTrue(CoopStorySyncFramework.diff(
                CoopStorySyncFramework.capture(fixture.hostMemory()),
                CoopStorySyncFramework.capture(fixture.guestMemory("guest-1"))).isSynced());
    }
}
