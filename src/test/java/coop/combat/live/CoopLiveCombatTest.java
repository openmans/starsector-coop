package coop.combat.live;

import coop.combat.CoopAllyReinforceCoordinator;
import coop.combat.live.CoopCombatNetCodec.ArmorCell;
import coop.combat.live.CoopCombatNetCodec.ArmorDelta;
import coop.combat.live.CoopCombatNetCodec.BeamSample;
import coop.combat.live.CoopCombatNetCodec.BeamStateBatch;
import coop.combat.live.CoopCombatNetCodec.CombatEvent;
import coop.combat.live.CoopCombatNetCodec.CombatInit;
import coop.combat.live.CoopCombatNetCodec.MissileSample;
import coop.combat.live.CoopCombatNetCodec.MissileStateBatch;
import coop.combat.live.CoopCombatNetCodec.ObjectiveInit;
import coop.combat.live.CoopCombatNetCodec.PilotInput;
import coop.combat.live.CoopCombatNetCodec.ProjectileHit;
import coop.combat.live.CoopCombatNetCodec.ProjectileHitBatch;
import coop.combat.live.CoopCombatNetCodec.ProjectileSpawn;
import coop.combat.live.CoopCombatNetCodec.ProjectileSpawnBatch;
import coop.combat.live.CoopCombatNetCodec.ShipSpawn;
import coop.combat.live.CoopCombatNetCodec.ShipStateBatch;
import coop.combat.live.CoopCombatNetCodec.ShipStateSample;
import coop.combat.live.CoopCombatNetCodec.SlotWeapon;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for native real-time co-op physical combat codecs, UDP MTU chunking, sparse armor
 * grid deltas, session buffering, and reinforcement choice compatibility.
 */
class CoopLiveCombatTest {

    @Test
    void combatInitAndShipSpawnRoundTripCleanly() {
        CombatInit init = new CombatInit("battle-42", 18000f, 16000f, "Commander", "guest-1", 7,
                List.of(new ObjectiveInit("obj-0", "nav_buoy", -2500f, 1200f, 0),
                        new ObjectiveInit("obj-1", "sensor_array", 2500f, -800f, 1)));
        String wireInit = CoopCombatNetCodec.encodeInit(init);
        CombatInit decodedInit = CoopCombatNetCodec.decodeInit(wireInit);
        assertNotNull(decodedInit);
        assertEquals("battle-42", decodedInit.battleId());
        assertEquals(18000f, decodedInit.mapWidth(), 0.5f);
        assertEquals(7, decodedInit.initialPilotNetId());
        assertEquals(2, decodedInit.objectives().size());
        assertEquals("nav_buoy", decodedInit.objectives().get(0).type());

        ShipSpawn spawn = new ShipSpawn("battle-42", 7, 0, "guest-1", "mem-77",
                "onslaught", "onslaught_Standard", "ISS Bulwark",
                35, 20,
                List.of(new SlotWeapon("WS 001", "hephag"), new SlotWeapon("WS 002", "flak")),
                List.of("heavyarmor", "targetingunit"),
                List.of("fluxdistributor"),
                List.of("talon_wing"),
                -450.5f, -3200f, 90f, 12.5f, 45.0f, 18500f, 20000f, 0.72f,
                false, false, 0);
        String wireSpawn = CoopCombatNetCodec.encodeSpawn(spawn);
        ShipSpawn decodedSpawn = CoopCombatNetCodec.decodeSpawn(wireSpawn);
        assertNotNull(decodedSpawn);
        assertEquals(7, decodedSpawn.netId());
        assertEquals("ISS Bulwark", decodedSpawn.shipName());
        assertEquals(35, decodedSpawn.fluxVents());
        assertEquals(20, decodedSpawn.fluxCaps());
        assertEquals(2, decodedSpawn.weapons().size());
        assertEquals("hephag", decodedSpawn.weapons().get(0).weaponId());
        assertEquals(List.of("heavyarmor", "targetingunit"), decodedSpawn.hullMods());
        assertEquals(List.of("fluxdistributor"), decodedSpawn.sMods());
        assertEquals(List.of("talon_wing"), decodedSpawn.wings());
    }

    @Test
    void pilotInputRoundTripsUnderTinyUdpBudget() {
        int mask = CoopCombatNetCodec.CMD_ACCEL | CoopCombatNetCodec.CMD_STRAFE_LEFT
                | CoopCombatNetCodec.CMD_FIRE | CoopCombatNetCodec.CMD_SHIELD_HAS_DESIRED
                | CoopCombatNetCodec.CMD_SHIELD_DESIRED_ON;
        PilotInput in = new PilotInput("b-9", 1234, 7, mask, 1425.25f, -890.5f, 2, 0b1011);
        String wire = CoopCombatNetCodec.encodeInput(in);
        assertTrue(wire.length() < 96, "Expected tiny input wire payload, got " + wire.length());

        PilotInput decoded = CoopCombatNetCodec.decodeInput(wire);
        assertNotNull(decoded);
        assertEquals("b-9", decoded.battleId());
        assertEquals(1234, decoded.seq());
        assertEquals(7, decoded.shipNetId());
        assertEquals(mask, decoded.commandMask());
        assertEquals(1425.25f, decoded.mouseX(), 0.01f);
        assertEquals(-890.5f, decoded.mouseY(), 0.01f);
        assertEquals(2, decoded.selectedGroup());
        assertEquals(0b1011, decoded.autofireMask());
    }

    @Test
    void shipStateChunksStayUnderUdpMtuAndPreserveAllFields() {
        List<ShipStateSample> samples = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            samples.add(new ShipStateSample(
                    i, i * 110f, -i * 75f, 42.5f, -18.25f, (i * 17f) % 360f, 12.5f,
                    4500f - i * 10f, 5000f, 0.78f,
                    1200f, 450f, 0f,
                    CoopCombatNetCodec.FLAG_SHIELD_ON, 135f, 210f,
                    2, 0.8f, 3, 4.25f,
                    new int[]{45, 90, 180, 270},
                    new int[]{12, -1, 4, 8},
                    0b0100, 0b0011));
        }

        List<String> chunks = CoopCombatNetCodec.encodeShipStateChunks(
                "b-big", 55, samples, CoopCombatNetCodec.DEFAULT_UDP_BODY_LIMIT);
        assertTrue(chunks.size() >= 2, "30 ships should split into multiple UDP chunks");

        int totalDecoded = 0;
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= CoopCombatNetCodec.DEFAULT_UDP_BODY_LIMIT + 32);
            // Verify wrapped datagram fits comfortably inside CoopNetService.MAX_DATAGRAM_BYTES
            String dg = CoopMessages.datagram("sess1234", "host", CoopMessages.Type.COMBAT_STATE,
                    List.of(new CoopMessages.DatagramSection(100L, 123456L, 0, chunk)));
            assertTrue(CoopMessages.utf8Length(dg) <= CoopNetService.MAX_DATAGRAM_BYTES,
                    "Datagram exceeded 1200 B MTU: " + CoopMessages.utf8Length(dg));

            ShipStateBatch batch = CoopCombatNetCodec.decodeShipState(chunk);
            assertNotNull(batch);
            assertEquals("b-big", batch.battleId());
            assertEquals(55, batch.tickSeq());
            totalDecoded += batch.ships().size();
            ShipStateSample first = batch.ships().get(0);
            assertEquals(CoopCombatNetCodec.FLAG_SHIELD_ON, first.flags());
            assertEquals(1200f, first.currFlux(), 0.1f);
            assertEquals(450f, first.hardFlux(), 0.1f);
            assertEquals(4, first.turretAnglesDeg().length);
            assertEquals(12, first.weaponAmmo()[0]);
            assertEquals(-1, first.weaponAmmo()[1]);
            assertEquals(0b0100, first.disabledWeaponMask());
            assertEquals(0b0011, first.autofireMask());
        }
        assertEquals(30, totalDecoded);
    }

    @Test
    void armorDeltaProjectilesMissilesBeamsAndEventsRoundTrip() {
        ArmorDelta armor = new ArmorDelta("b-1", 7, 15, 15,
                List.of(new ArmorCell(4, 10, 128), new ArmorCell(5, 10, 0)));
        ArmorDelta decArmor = CoopCombatNetCodec.decodeArmorDelta(CoopCombatNetCodec.encodeArmorDelta(armor));
        assertNotNull(decArmor);
        assertEquals(7, decArmor.netId());
        assertEquals(2, decArmor.cells().size());
        assertEquals(128, decArmor.cells().get(0).valueByte());
        assertEquals(0, decArmor.cells().get(1).valueByte());

        ProjectileSpawnBatch ps = new ProjectileSpawnBatch("b-1", List.of(
                new ProjectileSpawn(101, 7, 0, "hephag", false, 100f, 200f, 500f, 0f, 0f),
                new ProjectileSpawn(102, 7, 0, "harpoon", true, 120f, 210f, 300f, 50f, 15f)));
        ProjectileSpawnBatch decPs = CoopCombatNetCodec.decodeProjSpawns(CoopCombatNetCodec.encodeProjSpawns(ps));
        assertNotNull(decPs);
        assertEquals(2, decPs.spawns().size());
        assertTrue(decPs.spawns().get(1).missile());

        ProjectileHitBatch ph = new ProjectileHitBatch("b-1", List.of(
                new ProjectileHit(101, true, 600f, 200f, 350f, 1, true, 12)));
        ProjectileHitBatch decPh = CoopCombatNetCodec.decodeProjHits(CoopCombatNetCodec.encodeProjHits(ph));
        assertNotNull(decPh);
        assertEquals(1, decPh.hits().size());
        assertTrue(decPh.hits().get(0).shieldHit());
        assertEquals(12, decPh.hits().get(0).targetShipNetId());

        List<String> mChunks = CoopCombatNetCodec.encodeMissileStateChunks("b-1", 12,
                List.of(new MissileSample(102, 420f, 260f, 310f, 40f, 18f, true)),
                CoopCombatNetCodec.DEFAULT_UDP_BODY_LIMIT);
        assertEquals(1, mChunks.size());
        MissileStateBatch decM = CoopCombatNetCodec.decodeMissileState(mChunks.get(0));
        assertNotNull(decM);
        assertEquals(102, decM.missiles().get(0).projId());
        assertTrue(decM.missiles().get(0).fizzling());

        List<String> bChunks = CoopCombatNetCodec.encodeBeamChunks("b-1", 19,
                List.of(new BeamSample(10f, 20f, 800f, 400f, 16f, 0xFFFFFFFF, 0xFF44AAFF, true)),
                CoopCombatNetCodec.DEFAULT_UDP_BODY_LIMIT);
        BeamStateBatch decB = CoopCombatNetCodec.decodeBeams(bChunks.get(0));
        assertNotNull(decB);
        assertEquals(1, decB.beams().size());
        assertTrue(decB.beams().get(0).hitting());
        assertEquals(16f, decB.beams().get(0).width(), 0.1f);

        CombatEvent ev = new CombatEvent("b-1", CoopCombatNetCodec.EVT_EMP_ARC, 7, 12,
                10f, 20f, 300f, 400f, 14f, "emp");
        CombatEvent decEv = CoopCombatNetCodec.decodeEvent(CoopCombatNetCodec.encodeEvent(ev));
        assertNotNull(decEv);
        assertEquals(CoopCombatNetCodec.EVT_EMP_ARC, decEv.kind());
        assertEquals(300f, decEv.f3(), 0.1f);
    }

    @Test
    void sessionBuffersInitSpawnsRejectsStaleInputAndMapsLegacyWingmanChoice() {
        CoopLiveCombatSession session = new CoopLiveCombatSession();
        session.armAuthorityForPartner("guest-1", "Alice");
        assertTrue(session.isAuthorityArmed());

        session.beginAuthorityBattle("b-10");
        assertEquals(CoopLiveCombatSession.Role.AUTHORITY, session.role());
        session.acceptPilotInput(new PilotInput("b-10", 10, 7, CoopCombatNetCodec.CMD_ACCEL, 1f, 2f, 0, 0), 1000L);
        // Stale older seq must be ignored
        session.acceptPilotInput(new PilotInput("b-10", 8, 7, 0, 9f, 9f, 0, 0), 1010L);
        assertEquals(10, session.latestPilotInput().seq());

        // Client side launch latch
        session.acceptClientInit(new CombatInit("b-11", 14000f, 14000f, "Host", "guest-1", 4, List.of()), "host");
        assertTrue(session.isClientArenaActive());
        assertNotNull(session.consumePendingClientLaunch());
        assertNull(session.consumePendingClientLaunch());
        assertTrue(session.isClientArenaActive());
        session.endBattle();
        assertFalse(session.isClientArenaActive());

        // Legacy JOIN_WINGMAN wire string maps cleanly to JOIN_LIVE_COMBAT
        CoopAllyReinforceCoordinator.JoinReply legacy =
                CoopAllyReinforceCoordinator.decodeReply("offer-1|guest-1|JOIN_WINGMAN");
        assertNotNull(legacy);
        assertEquals(CoopAllyReinforceCoordinator.JoinChoice.JOIN_LIVE_COMBAT, legacy.choice());
    }
}
