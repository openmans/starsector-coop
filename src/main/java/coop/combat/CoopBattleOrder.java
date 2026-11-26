package coop.combat;

import com.fs.starfarer.api.combat.AssignmentTargetAPI;
import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CombatAssignmentType;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import coop.campaign.CoopDelimited;
import org.lwjgl.util.vector.Vector2f;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Phase 22: tactical order request/acknowledgement DTOs and fighting-engine applier for a partner's
 * joined allied ships.
 *
 * <p>Orders bypass command points ({@code useCommandPoint = false}), apply strictly through side 0's
 * allied task manager ({@code getFleetManager(0).getTaskManager(true)}), refuse non-owned / station
 * / module ships, and read back {@code getAssignmentFor(ship)} to confirm execution.
 */
public final class CoopBattleOrder {

    public enum OrderKind {
        ENGAGE,
        ESCORT,
        DEFEND,
        CAPTURE,
        WAYPOINT,
        RETREAT,
        CANCEL
    }

    public record Order(String orderId, String battleId, String shipId, OrderKind kind,
                        String targetId, float targetX, float targetY, boolean directRetreat) {
        public Order {
            orderId = CoopDelimited.normalize(orderId);
            battleId = CoopDelimited.normalize(battleId);
            shipId = CoopDelimited.normalize(shipId);
            kind = Objects.requireNonNull(kind, "kind");
            targetId = CoopDelimited.normalize(targetId);
        }
    }

    public record Ack(String orderId, String battleId, String shipId, boolean accepted,
                      String activeAssignment, String reason) {
        public Ack {
            orderId = CoopDelimited.normalize(orderId);
            battleId = CoopDelimited.normalize(battleId);
            shipId = CoopDelimited.normalize(shipId);
            activeAssignment = CoopDelimited.normalize(activeAssignment);
            reason = CoopDelimited.normalize(reason);
        }
    }

    private CoopBattleOrder() {
    }

    /**
     * Validates ownership and applies one partner order on the fighting combat engine.
     *
     * @param ownedShipIds set of ship/member ids owned by the ordering partner's joined fleet
     *                     (when non-empty, only listed ids may be ordered; when empty, any deployed
     *                     side-0 ally ship is rejected as unowned).
     */
    public static Ack apply(CombatEngineAPI engine, String activeBattleId, Order order,
                            Set<String> ownedShipIds) {
        if (order == null) {
            return new Ack("", "", "", false, "", "null order");
        }
        if (engine == null) {
            return reject(order, "no combat engine");
        }
        if (activeBattleId == null || !activeBattleId.equals(order.battleId())) {
            return reject(order, "battle mismatch");
        }
        if (ownedShipIds == null || !ownedShipIds.contains(order.shipId())) {
            return reject(order, "ship not owned by sender");
        }
        CombatFleetManagerAPI ownManager = engine.getFleetManager(0);
        if (ownManager == null) {
            return reject(order, "fleet manager unavailable");
        }
        CombatTaskManagerAPI allyTasks = ownManager.getTaskManager(true);
        if (allyTasks == null) {
            return reject(order, "allied task manager unavailable");
        }
        DeployedFleetMemberAPI member = findDeployed(ownManager, order.shipId());
        if (member == null) {
            return reject(order, "ship not deployed");
        }
        if (!member.isAlly()) {
            return reject(order, "ship is not an allied unit");
        }
        if (member.isStation() || member.isStationModule() || !member.canBeGivenOrders()) {
            return reject(order, "ship cannot be given orders");
        }
        try {
            switch (order.kind()) {
                case CANCEL -> {
                    allyTasks.cancelDirectOrdersForMember2(member);
                    allyTasks.orderSearchAndDestroy(member, false);
                }
                case RETREAT -> {
                    if (!member.canBeGivenRetreatOrders()) {
                        return reject(order, "ship cannot retreat");
                    }
                    allyTasks.orderRetreat(member, false, order.directRetreat());
                }
                case ENGAGE -> {
                    AssignmentTargetAPI enemy = findDeployed(engine.getFleetManager(1), order.targetId());
                    if (enemy == null) {
                        return reject(order, "enemy target not found");
                    }
                    CombatFleetManagerAPI.AssignmentInfo info =
                            allyTasks.createAssignment(CombatAssignmentType.ENGAGE, enemy, false);
                    allyTasks.giveAssignment(member, info, false);
                }
                case ESCORT -> {
                    AssignmentTargetAPI friend = findDeployed(ownManager, order.targetId());
                    if (friend == null) {
                        return reject(order, "friendly escort target not found");
                    }
                    CombatFleetManagerAPI.AssignmentInfo info =
                            allyTasks.createAssignment(CombatAssignmentType.MEDIUM_ESCORT, friend, false);
                    allyTasks.giveAssignment(member, info, false);
                }
                case DEFEND -> {
                    AssignmentTargetAPI target = resolveObjectiveOrWaypoint(engine, allyTasks, order);
                    CombatFleetManagerAPI.AssignmentInfo info =
                            allyTasks.createAssignment(CombatAssignmentType.DEFEND, target, false);
                    allyTasks.giveAssignment(member, info, false);
                }
                case CAPTURE -> {
                    BattleObjectiveAPI objective = findObjective(engine, order.targetId());
                    if (objective == null) {
                        return reject(order, "objective not found");
                    }
                    CombatFleetManagerAPI.AssignmentInfo info =
                            allyTasks.createAssignment(CombatAssignmentType.CAPTURE, objective, false);
                    allyTasks.giveAssignment(member, info, false);
                }
                case WAYPOINT -> {
                    AssignmentTargetAPI wp = allyTasks.createWaypoint2(
                            new Vector2f(order.targetX(), order.targetY()), false);
                    CombatFleetManagerAPI.AssignmentInfo info =
                            allyTasks.createAssignment(CombatAssignmentType.RALLY_TASK_FORCE, wp, false);
                    allyTasks.giveAssignment(member, info, false);
                }
            }
            String active = readActiveAssignment(allyTasks, member.getShip(), order.kind());
            return new Ack(order.orderId(), order.battleId(), order.shipId(), true, active, "");
        } catch (RuntimeException | LinkageError ex) {
            return reject(order, "engine error: " + ex.getClass().getSimpleName());
        }
    }

    public static OrderKind parseKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return OrderKind.CANCEL;
        }
        try {
            return OrderKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return OrderKind.CANCEL;
        }
    }

    private static Ack reject(Order order, String reason) {
        return new Ack(order.orderId(), order.battleId(), order.shipId(), false, "", reason);
    }

    private static AssignmentTargetAPI resolveObjectiveOrWaypoint(CombatEngineAPI engine,
                                                                  CombatTaskManagerAPI tasks,
                                                                  Order order) {
        BattleObjectiveAPI obj = findObjective(engine, order.targetId());
        if (obj != null) {
            return obj;
        }
        return tasks.createWaypoint2(new Vector2f(order.targetX(), order.targetY()), false);
    }

    private static DeployedFleetMemberAPI findDeployed(CombatFleetManagerAPI manager, String shipOrMemberId) {
        if (manager == null || shipOrMemberId == null || shipOrMemberId.isEmpty()) {
            return null;
        }
        List<DeployedFleetMemberAPI> deployed = manager.getDeployedCopyDFM();
        if (deployed == null) {
            return null;
        }
        for (DeployedFleetMemberAPI dfm : deployed) {
            if (dfm == null) {
                continue;
            }
            ShipAPI ship = dfm.getShip();
            if (ship != null && shipOrMemberId.equals(ship.getId())) {
                return dfm;
            }
            if (dfm.getMember() != null && shipOrMemberId.equals(dfm.getMember().getId())) {
                return dfm;
            }
        }
        return null;
    }

    private static BattleObjectiveAPI findObjective(CombatEngineAPI engine, String objectiveId) {
        if (engine == null || objectiveId == null || objectiveId.isEmpty()) {
            return null;
        }
        List<BattleObjectiveAPI> objectives = engine.getObjectives();
        if (objectives == null) {
            return null;
        }
        int index = 0;
        for (BattleObjectiveAPI obj : objectives) {
            if (obj == null) {
                continue;
            }
            String id = objectiveKey(obj, index++);
            if (objectiveId.equals(id)) {
                return obj;
            }
        }
        return null;
    }

    /** Stable objective key shared between status capture and order targeting. */
    public static String objectiveKey(BattleObjectiveAPI obj, int index) {
        String type = obj.getType() == null ? "obj" : obj.getType();
        return type + "#" + index;
    }

    private static String readActiveAssignment(CombatTaskManagerAPI tasks, ShipAPI ship, OrderKind fallback) {
        if (ship != null) {
            try {
                CombatFleetManagerAPI.AssignmentInfo info = tasks.getAssignmentFor(ship);
                if (info != null && info.getType() != null) {
                    return info.getType().name();
                }
            } catch (RuntimeException | LinkageError ignored) {
                // fall through to requested kind
            }
        }
        return fallback.name();
    }
}
