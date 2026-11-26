package coop.session;

import coop.interaction.CoopInteractionGate;
import coop.net.CoopConnectionRole;
import coop.presence.CoopPresenceRegistry;
import coop.rewards.CoopRewardSplitter;
import coop.save.CoopGuestSnapshot;
import coop.save.CoopGuestSnapshotStore;
import coop.time.CoopSharedPauseCoordinator;
import coop.wingman.CoopWingmanApi;
import coop.wingman.CoopWingmanIntegration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 27 unit tests covering host + up to 3 guests across session state, pause/fast-forward,
 * claim isolation, N-way colony income split, per-guest snapshot storage, and Wingman reservations.
 */
class CoopMultiGuestTest {

    @AfterEach
    void tearDown() {
        CoopGuestSnapshotStore.clear();
        CoopPresenceRegistry.clear();
    }

    @Test
    void sessionStateSupportsThreeGuestsIndependentHandshakeAndMidSessionJoinPolicy() {
        AtomicInteger ids = new AtomicInteger(1);
        CoopSessionState state = new CoopSessionState(() -> "id-" + ids.getAndIncrement());
        state.startHost("Host");
        state.setMaxGuests(3);

        CoopPlayerInfo g1 = new CoopPlayerInfo("g1", "Alice");
        CoopPlayerInfo g2 = new CoopPlayerInfo("g2", "Bob");
        CoopPlayerInfo g3 = new CoopPlayerInfo("g3", "Carol");
        CoopPlayerInfo g4 = new CoopPlayerInfo("g4", "Dave");

        assertTrue(state.canAcceptGuest());
        state.hostAcceptGuest(g1);
        String sessionId = state.hostAcceptHandshake("g1");
        state.recordSeedLockForGuest("g1", 42L, "MN-42", "fp-42");
        assertEquals(2, state.totalPlayerCount());

        // Second guest joins without wiping session id or first guest's seed lock.
        assertTrue(state.canAcceptGuest());
        state.hostAcceptGuest(g2);
        assertEquals(sessionId, state.hostAcceptHandshake("g2"));
        state.recordSeedLockForGuest("g2", 42L, "MN-42", "fp-42");
        assertEquals(3, state.totalPlayerCount());
        assertEquals("g1", state.remotePlayerId());
        assertTrue(state.hasGuest("g1"));
        assertTrue(state.hasGuest("g2"));

        // Release lobby; mid-session join policy gates guest 3.
        state.releaseLobby();
        state.setAllowMidSessionJoin(false);
        assertFalse(state.canAcceptGuest());
        assertTrue(state.rejectReasonForGuest(g3).contains("mid-session"));

        state.setAllowMidSessionJoin(true);
        assertTrue(state.canAcceptGuest());
        state.hostAcceptGuest(g3);
        assertEquals(4, state.totalPlayerCount());
        assertFalse(state.canAcceptGuest());
        assertEquals("Lobby is full (3 guests)", state.rejectReasonForGuest(g4));

        // Removing primary guest keeps session alive and promotes g2 to primary.
        assertFalse(state.hostRemoveGuest("g1"));
        assertEquals(sessionId, state.sessionId());
        assertEquals("g2", state.remotePlayerId());
        assertEquals(3, state.totalPlayerCount());

        // Removing remaining guests eventually ends the session cleanly.
        assertFalse(state.hostRemoveGuest("g2"));
        assertTrue(state.hostRemoveGuest("g3"));
        assertNull(state.sessionId());
        assertEquals(CoopLobbyState.HOST_WAITING, state.connectionState());
    }

    @Test
    void sharedPauseCoordinatorIsolatesMultipleGuestsAndCleansUpDepartingGuest() {
        CoopSharedPauseCoordinator coord = new CoopSharedPauseCoordinator();
        coord.setAllowGuestPause(true);

        // Guest 1 opens a screen -> world pauses; Guest 2 also opens a screen.
        assertTrue(coord.applyGuestScreenPauseIntent("g1", true, 1L));
        assertTrue(coord.applyGuestScreenPauseIntent("g2", true, 1L));
        assertTrue(coord.effectivePaused());

        // Guest 1 closes screen -> still paused because Guest 2's screen is open.
        coord.applyGuestScreenPauseIntent("g1", false, 2L);
        assertTrue(coord.effectivePaused());

        // Guest 2 leaves the session -> their hold clears, world resumes.
        coord.removeGuest("g2");
        assertFalse(coord.effectivePaused());

        // Any player can toggle global fast-forward immediately.
        assertTrue(coord.applyGuestFastForwardIntent("g1", true, 3L));
        assertTrue(coord.effectiveFastForward());
        assertTrue(coord.applyGuestFastForwardIntent("g3", false, 1L));
        assertFalse(coord.effectiveFastForward());
    }

    @Test
    void interactionGateIsolatesClaimOwnersAndReleasesOnlyDepartingGuestClaims() {
        CoopInteractionGate gate = new CoopInteractionGate();

        assertTrue(gate.arbitrate("mkt_jangala", "g1", "Jangala").accepted());
        assertTrue(gate.arbitrate("mkt_chicomoztoc", "g2", "Chicomoztoc").accepted());

        // g2 cannot release g1's claim.
        assertFalse(gate.release("mkt_jangala", "g2"));
        assertEquals("g1", gate.holderOf("mkt_jangala"));

        // g1 leaves -> only g1's claims drop; g2's claim remains intact.
        assertEquals(1, gate.releaseAllForPlayer("g1"));
        assertNull(gate.holderOf("mkt_jangala"));
        assertEquals("g2", gate.holderOf("mkt_chicomoztoc"));
    }

    @Test
    void rewardSplitterSplitsEvenlyAcrossTwoThreeAndFourPlayers() {
        CoopRewardSplitter.Split two = CoopRewardSplitter.split(12_000L, 2);
        assertEquals(6_000L, two.localShare());
        assertEquals(6_000L, two.remoteShare());
        assertEquals(12_000L, two.total());

        CoopRewardSplitter.Split three = CoopRewardSplitter.split(12_000L, 3);
        assertEquals(4_000L, three.localShare());
        assertEquals(8_000L, three.remoteShare());
        assertEquals(12_000L, three.total());

        CoopRewardSplitter.Split four = CoopRewardSplitter.split(12_000L, 4);
        assertEquals(3_000L, four.localShare());
        assertEquals(9_000L, four.remoteShare());
        assertEquals(12_000L, four.total());

        // Negative loss splits symmetrically across 4 players.
        CoopRewardSplitter.Split loss4 = CoopRewardSplitter.split(-10_000L, 4);
        assertEquals(-2_500L, loss4.localShare());
        assertEquals(-7_500L, loss4.remoteShare());
        assertEquals(-10_000L, loss4.total());
    }

    @Test
    void guestSnapshotStoreTracksMultipleGuestsAndKeepsLegacyKey() {
        CoopGuestSnapshot s1 = new CoopGuestSnapshot();
        s1.setPlayerId("g1");
        s1.setPlayerName("Alice");
        s1.setCredits(50_000.0);
        CoopGuestSnapshot s2 = new CoopGuestSnapshot();
        s2.setPlayerId("g2");
        s2.setPlayerName("Bob");
        s2.setCredits(75_000.0);

        CoopGuestSnapshotStore.publish(s1);
        CoopGuestSnapshotStore.publish(s2);

        Map<String, Object> persistent = new LinkedHashMap<>();
        assertTrue(CoopGuestSnapshotStore.writeInto(persistent));
        assertNotNull(persistent.get(CoopGuestSnapshotStore.PERSISTENT_KEY));
        @SuppressWarnings("unchecked")
        Map<String, CoopGuestSnapshot> savedMap =
                (Map<String, CoopGuestSnapshot>) persistent.get(CoopGuestSnapshotStore.PERSISTENT_MAP_KEY);
        assertNotNull(savedMap);
        assertEquals(2, savedMap.size());
        assertEquals(50_000.0, savedMap.get("g1").getCredits());
        assertEquals(75_000.0, savedMap.get("g2").getCredits());
    }

    @Test
    void wingmanIntegrationReservesAndReleasesFlagshipsPerGuestIndependently() {
        Map<String, String> reserved = new LinkedHashMap<>();
        List<String> connectedWingmen = List.of("AliceWM", "BobWM");
        CoopWingmanApi fakeApi = new CoopWingmanApi() {
            @Override
            public boolean wingmanEnabled() {
                return true;
            }

            @Override
            public boolean openHost() {
                return true;
            }

            @Override
            public String pumpHost() {
                return "";
            }

            @Override
            public List<String> playerNames() {
                return connectedWingmen;
            }

            @Override
            public void reserveShip(String playerName, String fleetMemberId) {
                if (playerName == null) {
                    reserved.remove(fleetMemberId);
                } else {
                    reserved.put(fleetMemberId, playerName);
                }
            }

            @Override
            public String localName() {
                return "HostWM";
            }

            @Override
            public boolean isJoined() {
                return false;
            }
        };

        Map<String, String> flagships = Map.of("g1", "ship_alice", "g2", "ship_bob");
        List<String> feeds = new ArrayList<>();
        CoopWingmanIntegration integration = new CoopWingmanIntegration(
                () -> CoopConnectionRole.HOST,
                () -> 1L,
                () -> "sess-multi",
                () -> 1000L,
                fakeApi,
                () -> true,
                () -> "ship_alice",
                id -> flagships.getOrDefault(id, ""),
                msg -> { },
                feeds::add,
                () -> "");

        integration.onGuestWingmanName("g1", "AliceWM");
        integration.onGuestWingmanName("g2", "BobWM");
        integration.tick();

        assertEquals("AliceWM", reserved.get("ship_alice"));
        assertEquals("BobWM", reserved.get("ship_bob"));

        // Removing g1 clears ONLY g1's ship reservation; g2's stays intact.
        integration.removeGuest("g1");
        assertNull(reserved.get("ship_alice"));
        assertEquals("BobWM", reserved.get("ship_bob"));
    }
}
