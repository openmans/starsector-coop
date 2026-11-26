package coop.save;

import coop.colony.CoopColonyManagement;
import coop.net.CoopReconnectCoordinator;
import coop.wingman.CoopWingmanApi;
import coop.wingman.CoopWingmanIntegration;
import coop.net.CoopConnectionRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for items 1.1 (Wingman auto-join/teardown), 1.2 (colony admin codec & conflict guard),
 * 2.2 (per-peer reconnect grace), and 2.3 (guest snapshot restoration).
 */
class CoopRecoveryAndHardeningTest {

    @Test
    void wingmanAutoJoinsWhenExtendedApiIsPresentAndDisconnectsOnReset() {
        AtomicBoolean joined = new AtomicBoolean(false);
        AtomicBoolean disconnected = new AtomicBoolean(false);
        List<String> feeds = new ArrayList<>();
        CoopWingmanApi extendedApi = new CoopWingmanApi() {
            @Override
            public boolean wingmanEnabled() { return true; }
            @Override
            public boolean isJoined() { return joined.get(); }
            @Override
            public String localName() { return "GuestPilot"; }
            @Override
            public boolean openHost() { return true; }
            @Override
            public String pumpHost() { return ""; }
            @Override
            public List<String> playerNames() { return List.of(); }
            @Override
            public void reserveShip(String name, String fleetMemberId) { }
            @Override
            public boolean supportsAutoJoin() { return true; }
            @Override
            public boolean join(String host, int port) {
                assertEquals("203.0.113.10", host);
                assertEquals(24816, port);
                joined.set(true);
                return true;
            }
            @Override
            public boolean disconnect() {
                disconnected.set(true);
                joined.set(false);
                return true;
            }
        };

        CoopWingmanIntegration integration = new CoopWingmanIntegration(
                () -> CoopConnectionRole.GUEST,
                () -> 1L,
                () -> "sess-wm",
                () -> 1000L,
                extendedApi,
                () -> true,
                () -> "",
                msg -> { },
                feeds::add,
                () -> "203.0.113.10");

        integration.tick();
        assertTrue(joined.get());
        assertTrue(feeds.stream().anyMatch(f -> f.contains("auto-connected")));

        integration.reset();
        assertTrue(disconnected.get());
    }

    @Test
    void colonyAdminAndBaseContentHashRoundTripAndDetectConcurrentConflicts() {
        CoopColonyManagement.AdminState admin = new CoopColonyManagement.AdminState(
                CoopColonyManagement.AdminKind.AI_CORE, "admin_1", "alpha_core",
                "Alpha Core", "graphics/portraits/portrait_ai2b.png", "hypercognition=1");
        CoopColonyManagement.State base = new CoopColonyManagement.State(
                "r1", "jangala", "p1", true, false, false, true, List.of(), List.of());
        CoopColonyManagement.State edited = new CoopColonyManagement.State(
                "r2", "jangala", "p2", true, false, true, true, List.of(), List.of(),
                admin, base.contentHash());

        CoopColonyManagement.State decoded = CoopColonyManagement.decode(edited.encode());
        assertEquals(admin, decoded.admin());
        assertEquals(base.contentHash(), decoded.baseContentHash());
        assertFalse(decoded.conflictsWith(base.contentHash()));
        assertTrue(decoded.conflictsWith("different_canonical_hash"));
    }

    @Test
    void perPeerReconnectCoordinatorExpiresAndResumesGuestsIndependently() {
        CoopReconnectCoordinator coord = new CoopReconnectCoordinator(10_000L,
                new CoopReconnectCoordinator.Listener() {
                    @Override public void onGraceStarted(CoopReconnectCoordinator.State state, long graceMillis) { }
                    @Override public void onResumed(CoopReconnectCoordinator.State previous) { }
                    @Override public void onEnded(CoopReconnectCoordinator.State previous, String reason) { }
                });

        assertTrue(coord.beginHostWaitForPeer("s1", "g1", 1000L));
        assertTrue(coord.beginHostWaitForPeer("s1", "g2", 5000L));
        assertEquals(List.of("g1", "g2"), coord.waitingPeerIds());

        // g2 returns on time
        assertEquals(CoopReconnectCoordinator.ResumeDecision.ACCEPT,
                coord.evaluatePeerResumeRequest("s1", "g2"));
        assertTrue(coord.resumePeer("g2"));
        assertFalse(coord.isPeerWaiting("g2"));
        assertTrue(coord.isPeerWaiting("g1"));

        // g1 expires at t=11000 without affecting any other peer
        assertEquals(List.of("g1"), coord.tickExpiredPeers(11_000L));
        assertTrue(coord.waitingPeerIds().isEmpty());
    }

    @Test
    void guestSnapshotRestorerRestoresCreditsCargoShipsOfficersAndLocation() {
        CoopGuestSnapshot snapshot = new CoopGuestSnapshot();
        snapshot.setPlayerId("g1");
        snapshot.setCredits(123_456.0);
        snapshot.setLocationId("corvus");
        snapshot.setCargo(List.of(new CoopGuestSnapshot.CargoStack("RESOURCES", "supplies", 250)));
        snapshot.setShips(List.of(new CoopGuestSnapshot.Ship(
                "m1", "wolf", "wolf_Assault", "ISS Star", "Jane Doe", 0.7f, 0.9f, "", "", "")));
        snapshot.setOfficers(List.of(new CoopGuestSnapshot.Officer("Jane Doe", "aggressive", 5, "helmsmanship:1")));

        List<String> ops = new ArrayList<>();
        CoopGuestSnapshotRestorer.RestoreReport report = CoopGuestSnapshotRestorer.restore(
                snapshot, new CoopGuestSnapshotRestorer.Target() {
                    @Override public boolean isReady() { return true; }
                    @Override public void setCredits(double credits) { ops.add("cr:" + (long) credits); }
                    @Override public void clearCargo() { ops.add("clearCargo"); }
                    @Override public boolean addCargoStack(CoopGuestSnapshot.CargoStack s) { ops.add("cargo:" + s.getId()); return true; }
                    @Override public void clearFleetAndOfficers() { ops.add("clearFleet"); }
                    @Override public boolean addShip(CoopGuestSnapshot.Ship s) { ops.add("ship:" + s.getShipName()); return true; }
                    @Override public boolean addOfficer(CoopGuestSnapshot.Officer o) { ops.add("off:" + o.getName()); return true; }
                    @Override public void moveToLocation(String loc) { ops.add("loc:" + loc); }
                });

        assertTrue(report.applied());
        assertEquals(1, report.shipsRestored());
        assertEquals(1, report.cargoStacksRestored());
        assertEquals(1, report.officersRestored());
        assertEquals(123_456.0, report.creditsRestored());
        assertEquals("corvus", report.locationId());
        assertTrue(ops.contains("ship:ISS Star"));
        assertTrue(ops.contains("off:Jane Doe"));
    }
}
