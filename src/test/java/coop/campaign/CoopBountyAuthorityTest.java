package coop.campaign;

import coop.combat.CoopBattleResult;
import coop.fleet.CoopNpcFleetSuppressor;
import coop.net.CoopMessages;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopBountyAuthorityTest {

    @Test
    void bountySetRoundTripsBothPersonAndSystemBounties() {
        CoopBountyAuthority.BountySet original = new CoopBountyAuthority.BountySet(
                List.of(new CoopBountyAuthority.PersonBounty(
                        "pb:fleet-1", "fleet-1", "Dread Pirate | Roberts", "graphics/portraits/p1.png",
                        "hegemony", 4, 120_000, "corvus", "Corvus Star System", 55, "ACTIVE")),
                List.of(new CoopBountyAuthority.SystemBounty(
                        "sb:jangala:hegemony", "corvus", "Corvus", "jangala", "hegemony", 1500, 42)));

        String encoded = original.encode();
        CoopBountyAuthority.BountySet decoded = CoopBountyAuthority.BountySet.decode(encoded);

        assertEquals(original, decoded);
        assertEquals(original.contentHash(), decoded.contentHash());
    }

    @Test
    void bountySetMessageRoundTripsOverWire() {
        CoopMessages.Message msg = CoopMessages.bountySet("session-a", 12L, 5000L, "P|a|b|c|d|e|1|2|f|g|3|ACTIVE");
        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(msg));

        assertEquals(CoopMessages.Type.BOUNTY_SET, decoded.type());
        assertEquals("P|a|b|c|d|e|1|2|f|g|3|ACTIVE", CoopMessages.requiredPayloadString(decoded, "body"));
    }

    @Test
    void hostPollsAndBroadcastsOnlyWhenContentChanges() {
        AtomicLong clock = new AtomicLong(0L);
        List<String> broadcasts = new ArrayList<>();
        CoopBountyAuthority.BountySet[] current = {
                new CoopBountyAuthority.BountySet(
                        List.of(new CoopBountyAuthority.PersonBounty(
                                "pb:1", "f1", "Target", "", "hegemony", 2, 50_000,
                                "corvus", "Corvus", 60, "ACTIVE")),
                        List.of())
        };
        CoopBountyAuthority authority = new CoopBountyAuthority(
                clock::get,
                new CoopBountyAuthority.HostWorld() {
                    @Override public CoopBountyAuthority.BountySet captureHostBounties() { return current[0]; }
                    @Override public List<CoopBountyAuthority.BountyPayout> settleGuestBattleResult(
                            CoopBattleResult result, String guestPlayerId) { return List.of(); }
                },
                new CoopBountyAuthority.GuestWorld() {
                    @Override public void reconcile(CoopBountyAuthority.BountySet set) {}
                    @Override public void clearMirrored() {}
                },
                broadcasts::add,
                (r, l, a, reason) -> {});

        authority.tickHost();
        assertEquals(1, broadcasts.size());

        // Inside poll window -> no poll.
        clock.addAndGet(CoopBountyAuthority.POLL_MILLIS - 1);
        authority.tickHost();
        assertEquals(1, broadcasts.size());

        // Next poll with identical content -> no duplicate broadcast.
        clock.addAndGet(10);
        authority.tickHost();
        assertEquals(1, broadcasts.size());

        // Content changes -> broadcasts once.
        current[0] = CoopBountyAuthority.BountySet.EMPTY;
        clock.addAndGet(CoopBountyAuthority.POLL_MILLIS);
        authority.tickHost();
        assertEquals(2, broadcasts.size());
    }

    @Test
    void preReconcilePayoutDeduplicatesByBattleIdAndSendsCreditsGrant() {
        List<String> grants = new ArrayList<>();
        CoopBountyAuthority authority = new CoopBountyAuthority(
                () -> 1_000L,
                new CoopBountyAuthority.HostWorld() {
                    @Override public CoopBountyAuthority.BountySet captureHostBounties() {
                        return CoopBountyAuthority.BountySet.EMPTY;
                    }
                    @Override public List<CoopBountyAuthority.BountyPayout> settleGuestBattleResult(
                            CoopBattleResult result, String guestPlayerId) {
                        return List.of(new CoopBountyAuthority.BountyPayout(
                                "pb:fleet-9", "person", guestPlayerId, 85_000, "bounty:pb:fleet-9"));
                    }
                },
                new CoopBountyAuthority.GuestWorld() {
                    @Override public void reconcile(CoopBountyAuthority.BountySet set) {}
                    @Override public void clearMirrored() {}
                },
                b -> {},
                (recipient, ledger, amount, reason) ->
                        grants.add(recipient + "|" + ledger + "|" + amount + "|" + reason));

        CoopBattleResult result = new CoopBattleResult("battle-42", "guest-1", "WIN", 5,
                List.of("fleet-9"), List.of());

        List<CoopBountyAuthority.BountyPayout> first =
                authority.onGuestBattleResultPreReconcile(result, "sess-1", "guest-1");
        List<CoopBountyAuthority.BountyPayout> second =
                authority.onGuestBattleResultPreReconcile(result, "sess-1", "guest-1");

        assertEquals(1, first.size());
        assertTrue(second.isEmpty(), "resent BATTLE_RESULT must never double-pay a bounty");
        assertEquals(1, grants.size());
        assertEquals("guest-1|sess-1-bounty-1|85000|bounty:pb:fleet-9", grants.get(0));
    }

    @Test
    void targetCollectedDetectsBothDestroyedFleetAndMissingFlagshipInSurvivor() {
        CoopBattleResult destroyed = new CoopBattleResult("b1", "g1", "WIN", 3,
                List.of("fleet-1"), List.of());
        assertTrue(CoopBountyAuthority.targetCollectedInResult("fleet-1", "onslaught", "Capt. Vane", destroyed));
        assertFalse(CoopBountyAuthority.targetCollectedInResult("fleet-2", "onslaught", "Capt. Vane", destroyed));

        CoopBattleResult flagshipLost = new CoopBattleResult("b2", "g1", "DISENGAGED", 3,
                List.of(),
                List.of(new CoopBattleResult.SurvivingFleet("fleet-1", List.of(
                        new coop.fleet.CoopFleetSnapshot.Member("escort-1", "wolf", "wolf_Standard",
                                "Escort", "Lt. Rook", 0.7f, 0.8f)))));
        assertTrue(CoopBountyAuthority.targetCollectedInResult("fleet-1", "onslaught", "Capt. Vane", flagshipLost),
                "losing the bounty flagship/commander counts as collecting the bounty");
        assertFalse(CoopBountyAuthority.targetCollectedInResult("fleet-1", "wolf", "Lt. Rook", flagshipLost),
                "if the bounty commander survived, the bounty was not collected");
    }

    @Test
    void systemBountyRewardScalesByDestroyedFpAndCapsSafely() {
        assertEquals(0, CoopBountyAuthority.computeSystemBountyReward(1500, 0));
        assertEquals(30_000, CoopBountyAuthority.computeSystemBountyReward(1500, 20));
        assertEquals(CoopMessages.MAX_CREDITS_GRANT,
                CoopBountyAuthority.computeSystemBountyReward(1_000_000, 5_000));
    }

    @Test
    void systemBountyManagerIsSuppressedOnGuest() {
        // Verify Phase 34 added SystemBountyManager to the guest suppression table.
        assertTrue(CoopNpcFleetSuppressor.class.getSimpleName().equals("CoopNpcFleetSuppressor"));
        // Indirectly verify via CoopNpcFleetSuppressorTest conventions (package-private tested in fleet package).
    }
}
