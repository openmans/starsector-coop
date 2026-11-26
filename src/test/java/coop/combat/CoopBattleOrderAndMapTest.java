package coop.combat;

import com.fs.starfarer.api.combat.AssignmentTargetAPI;
import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CombatAssignmentType;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import coop.net.CoopMessages;
import coop.ui.CoopTacticalMapPanel;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopBattleOrderAndMapTest {

    @Test
    void extendedBattleStatusRoundTripsPositionsObjectivesAndBounds() {
        CoopBattleStatus status = new CoopBattleStatus(
                "battle-22", 7L, 12_500L,
                List.of(
                        new CoopBattleStatus.ShipRecord("ally-1", "onslaught", "ISS Vanguard", false,
                                0.85f, 0.25f, CoopBattleStatus.ShipState.ALIVE,
                                1200f, -850f, 90f, 0.72f, true, "ENGAGE", false),
                        new CoopBattleStatus.ShipRecord("enemy-1", "dominator", "Pirate Boss", true,
                                0.50f, 0.60f, CoopBattleStatus.ShipState.ALIVE,
                                -400f, 1600f, 270f, 0.65f, false, "", true)),
                List.of("Enemy Lasher destroyed"),
                List.of(new CoopBattleStatus.ObjectiveRecord("nav_buoy#0", "nav_buoy", "Nav Buoy", 0, 0f, 500f)),
                new CoopBattleStatus.MapBounds(18_000f, 16_000f, true));

        CoopBattleStatus decoded = CoopBattleStatus.decode(
                status.battleId(), status.statusSeq(), status.elapsedMillis(), status.encodeBody());

        assertEquals(status, decoded);
        assertEquals(1, decoded.alliedShips().size());
        assertEquals("ally-1", decoded.alliedShips().get(0).shipId());
        assertEquals("ENGAGE", decoded.alliedShips().get(0).assignment());
        assertTrue(decoded.bounds().paused());
        assertEquals(18_000f, decoded.bounds().width(), 0.01f);
        assertEquals(1, decoded.objectives().size());
        assertEquals("nav_buoy#0", decoded.objectives().get(0).id());
    }

    @Test
    void legacyEightFieldBattleStatusLineStillDecodesWithSafeDefaults() {
        String legacyBody = "S|s1|wolf|Wolf|0|0.9|0.1|ALIVE\nK|Enemy Kite destroyed";
        CoopBattleStatus decoded = CoopBattleStatus.decode("b-old", 1L, 500L, legacyBody);

        assertEquals(1, decoded.ships().size());
        CoopBattleStatus.ShipRecord ship = decoded.ships().get(0);
        assertEquals("s1", ship.shipId());
        assertEquals(0f, ship.x());
        assertEquals(1f, ship.cr());
        assertFalse(ship.ally());
        assertEquals("", ship.assignment());
        assertEquals(List.of("Enemy Kite destroyed"), decoded.killFeed());
    }

    @Test
    void battleOrderAndAckMessagesRoundTrip() {
        CoopMessages.Message order = CoopMessages.battleOrder("sess-1", 10L, 1000L,
                "ord-1", "battle-1", "ally-1", "ENGAGE", "enemy-1", 100f, -200f, false);
        CoopMessages.Message decodedOrder = CoopMessages.decode(CoopMessages.encode(order));
        assertEquals(CoopMessages.Type.BATTLE_ORDER, decodedOrder.type());
        assertEquals("ally-1", CoopMessages.requiredPayloadString(decodedOrder, "shipId"));
        assertEquals("ENGAGE", CoopMessages.requiredPayloadString(decodedOrder, "kind"));

        CoopMessages.Message ack = CoopMessages.battleOrderAck("sess-1", 11L, 1005L,
                "ord-1", "battle-1", "ally-1", true, "ENGAGE", "");
        CoopMessages.Message decodedAck = CoopMessages.decode(CoopMessages.encode(ack));
        assertEquals(CoopMessages.Type.BATTLE_ORDER_ACK, decodedAck.type());
        assertEquals("true", CoopMessages.requiredPayloadString(decodedAck, "accepted"));
        assertEquals("ENGAGE", CoopMessages.requiredPayloadString(decodedAck, "activeAssignment"));
    }

    @Test
    void applyOrderValidatesOwnershipStationFlagAndExecutesOnAllyManager() {
        List<String> calls = new ArrayList<>();
        DeployedFleetMemberAPI allyShip = deployedMember("ally-1", true, false, true);
        DeployedFleetMemberAPI station = deployedMember("station-1", true, true, false);
        DeployedFleetMemberAPI enemyShip = deployedMember("enemy-1", false, false, true);
        CombatEngineAPI engine = mockCombatEngine(List.of(allyShip, station), List.of(enemyShip), calls);

        // Reject unowned ship
        CoopBattleOrder.Ack unowned = CoopBattleOrder.apply(engine, "b1",
                new CoopBattleOrder.Order("o1", "b1", "ally-1", CoopBattleOrder.OrderKind.ENGAGE,
                        "enemy-1", 0, 0, false),
                Set.of("other-ship"));
        assertFalse(unowned.accepted());
        assertTrue(unowned.reason().contains("not owned"));

        // Reject station
        CoopBattleOrder.Ack stationAck = CoopBattleOrder.apply(engine, "b1",
                new CoopBattleOrder.Order("o2", "b1", "station-1", CoopBattleOrder.OrderKind.ENGAGE,
                        "enemy-1", 0, 0, false),
                Set.of("station-1"));
        assertFalse(stationAck.accepted());

        // Accept ENGAGE, WAYPOINT, RETREAT, CANCEL on owned ally
        CoopBattleOrder.Ack engageAck = CoopBattleOrder.apply(engine, "b1",
                new CoopBattleOrder.Order("o3", "b1", "ally-1", CoopBattleOrder.OrderKind.ENGAGE,
                        "enemy-1", 0, 0, false),
                Set.of("ally-1"));
        assertTrue(engageAck.accepted());
        assertEquals("ENGAGE", engageAck.activeAssignment());

        CoopBattleOrder.Ack wpAck = CoopBattleOrder.apply(engine, "b1",
                new CoopBattleOrder.Order("o4", "b1", "ally-1", CoopBattleOrder.OrderKind.WAYPOINT,
                        "", 500f, -300f, false),
                Set.of("ally-1"));
        assertTrue(wpAck.accepted());

        CoopBattleOrder.Ack retreatAck = CoopBattleOrder.apply(engine, "b1",
                new CoopBattleOrder.Order("o5", "b1", "ally-1", CoopBattleOrder.OrderKind.RETREAT,
                        "", 0, 0, true),
                Set.of("ally-1"));
        assertTrue(retreatAck.accepted());

        CoopBattleOrder.Ack cancelAck = CoopBattleOrder.apply(engine, "b1",
                new CoopBattleOrder.Order("o6", "b1", "ally-1", CoopBattleOrder.OrderKind.CANCEL,
                        "", 0, 0, false),
                Set.of("ally-1"));
        assertTrue(cancelAck.accepted());
        assertTrue(calls.contains("createAssignment:ENGAGE:false"), "orders must bypass command points");
        assertTrue(calls.contains("orderRetreat:false:true"));
        assertTrue(calls.contains("orderSearchAndDestroy:false"));
    }

    @Test
    void tacticalMapViewportProjectsPansZoomsAndHitTestsAccurately() {
        CoopTacticalMapPanel.Viewport vp = new CoopTacticalMapPanel.Viewport();
        vp.setBounds(100f, 50f, 800f, 600f);
        vp.fitMap(16_000f, 12_000f);

        // Origin maps to panel center (500, 350)
        assertEquals(500f, vp.worldToScreenX(0f), 0.01f);
        assertEquals(350f, vp.worldToScreenY(0f), 0.01f);
        assertEquals(0f, vp.screenToWorldX(500f), 0.01f);
        assertEquals(0f, vp.screenToWorldY(350f), 0.01f);

        // Round-trip projection after pan and zoom
        float zoomBefore = vp.zoom();
        vp.zoomByWheel(120);
        assertTrue(vp.zoom() > zoomBefore);
        vp.panByScreenDelta(40f, -20f);
        float sx = vp.worldToScreenX(1234f);
        float sy = vp.worldToScreenY(-567f);
        assertEquals(1234f, vp.screenToWorldX(sx), 0.5f);
        assertEquals(-567f, vp.screenToWorldY(sy), 0.5f);

        // Hit-testing picks nearest ship within radius and ignores far ships
        CoopBattleStatus.ShipRecord ship = new CoopBattleStatus.ShipRecord("s1", "wolf", "Wolf",
                false, 1f, 0f, CoopBattleStatus.ShipState.ALIVE, 1234f, -567f, 0f, 1f, true, "", false);
        assertNotNull(vp.pickShip(List.of(ship), sx + 5f, sy - 5f));
        assertNull(vp.pickShip(List.of(ship), sx + 100f, sy + 100f));
    }

    private static DeployedFleetMemberAPI deployedMember(String id, boolean ally, boolean station,
                                                         boolean canOrder) {
        ShipAPI ship = (ShipAPI) Proxy.newProxyInstance(ShipAPI.class.getClassLoader(),
                new Class<?>[]{ShipAPI.class}, (p, m, a) -> switch (m.getName()) {
                    case "getId" -> id;
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    default -> null;
                });
        return (DeployedFleetMemberAPI) Proxy.newProxyInstance(DeployedFleetMemberAPI.class.getClassLoader(),
                new Class<?>[]{DeployedFleetMemberAPI.class}, (p, m, a) -> switch (m.getName()) {
                    case "getShip" -> ship;
                    case "getMember" -> null;
                    case "isAlly" -> ally;
                    case "isStation", "isStationModule" -> station;
                    case "canBeGivenOrders", "canBeGivenRetreatOrders" -> canOrder;
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    default -> null;
                });
    }

    private static CombatEngineAPI mockCombatEngine(List<DeployedFleetMemberAPI> own,
                                                    List<DeployedFleetMemberAPI> enemy,
                                                    List<String> calls) {
        CombatFleetManagerAPI.AssignmentInfo[] lastInfo = new CombatFleetManagerAPI.AssignmentInfo[1];
        CombatTaskManagerAPI allyTasks = (CombatTaskManagerAPI) Proxy.newProxyInstance(
                CombatTaskManagerAPI.class.getClassLoader(),
                new Class<?>[]{CombatTaskManagerAPI.class}, (p, m, a) -> switch (m.getName()) {
                    case "createAssignment" -> {
                        CombatAssignmentType type = (CombatAssignmentType) a[0];
                        calls.add("createAssignment:" + type.name() + ":" + a[2]);
                        CombatFleetManagerAPI.AssignmentInfo info =
                                (CombatFleetManagerAPI.AssignmentInfo) Proxy.newProxyInstance(
                                        CombatFleetManagerAPI.AssignmentInfo.class.getClassLoader(),
                                        new Class<?>[]{CombatFleetManagerAPI.AssignmentInfo.class},
                                        (ip, im, ia) -> "getType".equals(im.getName()) ? type : null);
                        lastInfo[0] = info;
                        yield info;
                    }
                    case "giveAssignment" -> { calls.add("giveAssignment"); yield null; }
                    case "orderRetreat" -> {
                        calls.add("orderRetreat:" + a[1] + ":" + a[2]);
                        lastInfo[0] = null;
                        yield null;
                    }
                    case "orderSearchAndDestroy" -> {
                        calls.add("orderSearchAndDestroy:" + a[1]);
                        lastInfo[0] = null;
                        yield null;
                    }
                    case "cancelDirectOrdersForMember2" -> { calls.add("cancelDirect"); yield null; }
                    case "createWaypoint2" -> (AssignmentTargetAPI) Proxy.newProxyInstance(
                            AssignmentTargetAPI.class.getClassLoader(),
                            new Class<?>[]{AssignmentTargetAPI.class},
                            (wp, wm, wa) -> "getLocation".equals(wm.getName()) ? a[0] : null);
                    case "getAssignmentFor" -> lastInfo[0];
                    default -> null;
                });
        CombatFleetManagerAPI ownMgr = (CombatFleetManagerAPI) Proxy.newProxyInstance(
                CombatFleetManagerAPI.class.getClassLoader(),
                new Class<?>[]{CombatFleetManagerAPI.class}, (p, m, a) -> switch (m.getName()) {
                    case "getTaskManager" -> Boolean.TRUE.equals(a[0]) ? allyTasks : null;
                    case "getDeployedCopyDFM" -> own;
                    default -> null;
                });
        CombatFleetManagerAPI enemyMgr = (CombatFleetManagerAPI) Proxy.newProxyInstance(
                CombatFleetManagerAPI.class.getClassLoader(),
                new Class<?>[]{CombatFleetManagerAPI.class}, (p, m, a) -> switch (m.getName()) {
                    case "getDeployedCopyDFM" -> enemy;
                    default -> null;
                });
        return (CombatEngineAPI) Proxy.newProxyInstance(CombatEngineAPI.class.getClassLoader(),
                new Class<?>[]{CombatEngineAPI.class}, (p, m, a) -> switch (m.getName()) {
                    case "getFleetManager" -> Integer.valueOf(0).equals(a[0]) ? ownMgr : enemyMgr;
                    case "getObjectives" -> List.<BattleObjectiveAPI>of();
                    default -> null;
                });
    }
}
