package coop.combat.live;

import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import coop.combat.live.CoopCombatNetCodec.ArmorCell;
import coop.combat.live.CoopCombatNetCodec.ArmorDelta;
import coop.combat.live.CoopCombatNetCodec.BeamSample;
import coop.combat.live.CoopCombatNetCodec.CombatEvent;
import coop.combat.live.CoopCombatNetCodec.CombatInit;
import coop.combat.live.CoopCombatNetCodec.MissileSample;
import coop.combat.live.CoopCombatNetCodec.ObjectiveInit;
import coop.combat.live.CoopCombatNetCodec.ProjectileHit;
import coop.combat.live.CoopCombatNetCodec.ProjectileHitBatch;
import coop.combat.live.CoopCombatNetCodec.ProjectileSpawn;
import coop.combat.live.CoopCombatNetCodec.ProjectileSpawnBatch;
import coop.combat.live.CoopCombatNetCodec.ShipSpawn;
import coop.combat.live.CoopCombatNetCodec.ShipStateSample;
import coop.combat.live.CoopCombatNetCodec.SlotWeapon;
import coop.net.CoopMessages;
import coop.util.CoopLog;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Authority-side real-time combat streamer. Runs inside the engaging player's native
 * {@link CombatEngineAPI}, attaches {@link CoopRemotePilotShipAI} to the joining partner's ship,
 * and streams full live world state (ships, fighters, shields, delta-compressed armor grid, flux,
 * ship systems, turrets, ballistic/energy projectiles, guided missiles, beams, explosions,
 * objectives, and pause state) to the partner's puppet combat client.
 */
public final class CoopLiveCombatAuthority {

    private static final long STATE_INTERVAL_MILLIS = 38L;     // ~26 Hz ship/shield/flux/turret UDP
    private static final long ARMOR_INTERVAL_MILLIS = 110L;    // ~9 Hz sparse armor deltas TCP
    private static final long MISSILE_INTERVAL_MILLIS = 55L;   // ~18 Hz guided missile UDP
    private static final long BEAM_INTERVAL_MILLIS = 50L;      // ~20 Hz beam rays UDP

    /** Transport seam decoupled from {@code CoopNetPump} for clean unit testing. */
    public interface Transport {
        void sendTcp(CoopMessages.Type type, String body);

        void sendUdp(CoopMessages.Type type, String body, long nowMillis);
    }

    private static final class TrackedProj {
        final int projId;
        final boolean missile;
        float lastX;
        float lastY;
        float damage;
        int damageTypeOrdinal;
        boolean shieldHit;
        int targetShipNetId;
        boolean seenThisFrame;

        TrackedProj(int projId, boolean missile, float x, float y, float damage, int dmgType) {
            this.projId = projId;
            this.missile = missile;
            this.lastX = x;
            this.lastY = y;
            this.damage = damage;
            this.damageTypeOrdinal = dmgType;
        }
    }

    private final CoopLiveCombatSession session;
    private final Transport transport;
    private final LongSupplier clockMillis;
    private final String authorityName;

    private boolean initialized;
    private int nextShipNetId = 1;
    private int nextProjId = 1;
    private int stateTickSeq;
    private int missileTickSeq;
    private int beamTickSeq;

    private long nextStateAtMillis;
    private long nextArmorAtMillis;
    private long nextMissileAtMillis;
    private long nextBeamAtMillis;
    private boolean lastSentBeamsNonEmpty;
    private boolean lastPausedState;
    private boolean applyingRemotePause;

    private final IdentityHashMap<ShipAPI, Integer> shipToNetId = new IdentityHashMap<>();
    private final Map<Integer, ShipAPI> netIdToShip = new HashMap<>();
    private final Map<Integer, Boolean> shipAliveLatch = new HashMap<>();
    private final Map<Integer, byte[][]> armorCache = new HashMap<>();
    private final Set<Integer> partnerOwnedShipNetIds = new LinkedHashSet<>();
    private final IdentityHashMap<DamagingProjectileAPI, TrackedProj> trackedProjs = new IdentityHashMap<>();
    private final Map<String, Integer> objectiveOwnerCache = new HashMap<>();

    private ShipAPI currentPilotedShip;
    private int currentPilotedNetId;
    private CoopRemotePilotShipAI activeRemoteAI;

    public CoopLiveCombatAuthority(CoopLiveCombatSession session, Transport transport,
                                   LongSupplier clockMillis, String authorityName) {
        this.session = Objects.requireNonNull(session, "session");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clockMillis = clockMillis == null ? System::currentTimeMillis : clockMillis;
        this.authorityName = authorityName == null || authorityName.isBlank() ? "Host" : authorityName.trim();
    }

    public void onFrame(CombatEngineAPI engine, Set<String> partnerFleetMemberIds, long nowMillis) {
        if (engine == null || session.role() != CoopLiveCombatSession.Role.AUTHORITY) {
            return;
        }
        String battleId = session.battleId();
        try {
            // 1. Discover any new ships/fighters/modules & assign netIds
            List<ShipAPI> allShips = engine.getShips();
            List<ShipSpawn> newSpawns = discoverNewShips(battleId, allShips, partnerFleetMemberIds);

            // 2. First frame: send COMBAT_INIT before COMBAT_SPAWN
            if (!initialized) {
                initialized = true;
                lastPausedState = engine.isPaused();
                pickInitialPilotShip(nowMillis);
                List<ObjectiveInit> objs = captureObjectives(engine);
                CombatInit init = new CombatInit(
                        battleId,
                        engine.getMapWidth(),
                        engine.getMapHeight(),
                        authorityName,
                        session.partnerPlayerId(),
                        currentPilotedNetId,
                        objs);
                transport.sendTcp(CoopMessages.Type.COMBAT_INIT, CoopCombatNetCodec.encodeInit(init));
            }

            // 3. Emit newly spawned ships reliably over TCP
            for (ShipSpawn sp : newSpawns) {
                transport.sendTcp(CoopMessages.Type.COMBAT_SPAWN, CoopCombatNetCodec.encodeSpawn(sp));
            }

            // 4. Handle inbound events from partner (pilot switch, pause)
            processInboundEvents(engine, battleId, nowMillis);

            // 5. Re-attach remote AI if partner's piloted ship died or input resumed after timeout
            maintainRemotePilotShip(battleId, nowMillis);

            // 6. Pause synchronization (local Space press -> partner)
            boolean pausedNow = engine.isPaused();
            if (pausedNow != lastPausedState) {
                lastPausedState = pausedNow;
                if (!applyingRemotePause) {
                    transport.sendTcp(CoopMessages.Type.COMBAT_EVENT,
                            CoopCombatNetCodec.encodeEvent(new CombatEvent(
                                    battleId, CoopCombatNetCodec.EVT_PAUSE,
                                    pausedNow ? 1 : 0, 0, 0f, 0f, 0f, 0f, 0f, authorityName)));
                }
            }
            applyingRemotePause = false;

            // 7. Detect deaths / hulks / removals and objective owner flips
            detectShipLifecycleEvents(engine, battleId, allShips);
            detectObjectiveFlips(engine, battleId);

            // 8. Track projectile & missile spawns and hits (every frame so zero shots are missed)
            trackProjectilesAndMissiles(engine, battleId);

            // 9. Throttled high-frequency UDP streams
            if (nowMillis >= nextStateAtMillis) {
                nextStateAtMillis = nowMillis + STATE_INTERVAL_MILLIS;
                streamShipStates(battleId, allShips, nowMillis);
            }
            if (nowMillis >= nextArmorAtMillis) {
                nextArmorAtMillis = nowMillis + ARMOR_INTERVAL_MILLIS;
                streamArmorDeltas(battleId, allShips);
            }
            if (nowMillis >= nextMissileAtMillis) {
                nextMissileAtMillis = nowMillis + MISSILE_INTERVAL_MILLIS;
                streamMissileStates(engine, battleId, nowMillis);
            }
            if (nowMillis >= nextBeamAtMillis) {
                nextBeamAtMillis = nowMillis + BEAM_INTERVAL_MILLIS;
                streamBeams(engine, battleId, nowMillis);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopLiveCombatAuthority.class, "Coop live combat authority frame error", ex);
        }
    }

    public void finishBattle() {
        String battleId = session.battleId();
        if (initialized && !battleId.isEmpty()) {
            try {
                transport.sendTcp(CoopMessages.Type.COMBAT_EVENT,
                        CoopCombatNetCodec.encodeEvent(new CombatEvent(
                                battleId, CoopCombatNetCodec.EVT_COMBAT_END,
                                0, 0, 0f, 0f, 0f, 0f, 0f, "")));
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
        resetState();
    }

    public void resetState() {
        initialized = false;
        nextShipNetId = 1;
        nextProjId = 1;
        stateTickSeq = 0;
        missileTickSeq = 0;
        beamTickSeq = 0;
        nextStateAtMillis = 0L;
        nextArmorAtMillis = 0L;
        nextMissileAtMillis = 0L;
        nextBeamAtMillis = 0L;
        lastSentBeamsNonEmpty = false;
        shipToNetId.clear();
        netIdToShip.clear();
        shipAliveLatch.clear();
        armorCache.clear();
        partnerOwnedShipNetIds.clear();
        trackedProjs.clear();
        objectiveOwnerCache.clear();
        currentPilotedShip = null;
        currentPilotedNetId = 0;
        activeRemoteAI = null;
    }

    private List<ShipSpawn> discoverNewShips(String battleId, List<ShipAPI> allShips,
                                             Set<String> partnerFleetMemberIds) {
        if (allShips == null || allShips.isEmpty()) {
            return List.of();
        }
        List<ShipSpawn> spawns = new ArrayList<>();
        for (ShipAPI ship : allShips) {
            if (ship == null || shipToNetId.containsKey(ship)) {
                continue;
            }
            int netId = nextShipNetId++;
            shipToNetId.put(ship, netId);
            netIdToShip.put(netId, ship);
            shipAliveLatch.put(netId, ship.isAlive() && !ship.isHulk());

            FleetMemberAPI member = ship.getFleetMember();
            String memberId = member != null && member.getId() != null ? member.getId() : "";
            boolean isPartnerShip = !ship.isFighter() && ship.getOwner() == 0
                    && (ship.isAlly() || (partnerFleetMemberIds != null && partnerFleetMemberIds.contains(memberId)));
            if (isPartnerShip) {
                partnerOwnedShipNetIds.add(netId);
            }
            String ownerPlayerId = isPartnerShip ? session.partnerPlayerId() : "";

            int parentNetId = 0;
            boolean isModule = ship.isStationModule() && ship.getParentStation() != null;
            if (isModule) {
                Integer pId = shipToNetId.get(ship.getParentStation());
                parentNetId = pId == null ? 0 : pId;
            }

            ShipVariantAPI variant = ship.getVariant();
            String hullId = ship.getHullSpec() != null && ship.getHullSpec().getHullId() != null
                    ? ship.getHullSpec().getHullId() : "";
            String variantId = variant != null && variant.getHullVariantId() != null
                    ? variant.getHullVariantId() : "";
            String shipName = ship.getName() != null ? ship.getName() : "";

            int vents = variant == null ? 0 : variant.getNumFluxVents();
            int caps = variant == null ? 0 : variant.getNumFluxCapacitors();
            List<SlotWeapon> weapons = new ArrayList<>();
            List<String> hullMods = new ArrayList<>();
            List<String> sMods = new ArrayList<>();
            List<String> wings = new ArrayList<>();
            if (variant != null) {
                if (variant.getFittedWeaponSlots() != null) {
                    for (String slotId : variant.getFittedWeaponSlots()) {
                        String wid = variant.getWeaponId(slotId);
                        if (slotId != null && wid != null && !wid.isBlank()) {
                            weapons.add(new SlotWeapon(slotId, wid));
                        }
                    }
                }
                if (variant.getHullMods() != null) {
                    hullMods.addAll(variant.getHullMods());
                }
                if (variant.getSMods() != null) {
                    sMods.addAll(variant.getSMods());
                }
                if (variant.getFittedWings() != null) {
                    for (String w : variant.getFittedWings()) {
                        if (w != null && !w.isBlank()) {
                            wings.add(w);
                        }
                    }
                }
            }

            Vector2f loc = ship.getLocation();
            Vector2f vel = ship.getVelocity();
            spawns.add(new ShipSpawn(
                    battleId, netId, ship.getOwner(), ownerPlayerId, memberId,
                    hullId, variantId, shipName, vents, caps,
                    weapons, hullMods, sMods, wings,
                    loc != null ? loc.x : 0f, loc != null ? loc.y : 0f,
                    ship.getFacing(),
                    vel != null ? vel.x : 0f, vel != null ? vel.y : 0f,
                    ship.getHitpoints(), ship.getMaxHitpoints(),
                    ship.getCurrentCR(),
                    ship.isFighter(), isModule, parentNetId));
        }
        return spawns;
    }

    private void pickInitialPilotShip(long nowMillis) {
        ShipAPI best = null;
        int bestNetId = 0;
        float bestHp = -1f;
        for (int netId : partnerOwnedShipNetIds) {
            ShipAPI s = netIdToShip.get(netId);
            if (s != null && s.isAlive() && !s.isHulk() && !s.isFighter()) {
                if (s.getMaxHitpoints() > bestHp) {
                    bestHp = s.getMaxHitpoints();
                    best = s;
                    bestNetId = netId;
                }
            }
        }
        if (best != null) {
            attachRemotePilot(best, bestNetId);
        }
    }

    private void attachRemotePilot(ShipAPI ship, int netId) {
        if (currentPilotedShip != null && currentPilotedShip != ship && currentPilotedShip.isAlive()) {
            try {
                currentPilotedShip.resetDefaultAI();
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
        currentPilotedShip = ship;
        currentPilotedNetId = netId;
        session.setPilotedShipNetId(netId);
        activeRemoteAI = new CoopRemotePilotShipAI(ship, netId, session, clockMillis);
        try {
            ship.setShipAI(activeRemoteAI);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopLiveCombatAuthority.class, "Could not attach CoopRemotePilotShipAI", ex);
        }
    }

    private void maintainRemotePilotShip(String battleId, long nowMillis) {
        // Re-attach if remote input resumed after an earlier timeout fallback
        if (currentPilotedShip != null && currentPilotedShip.isAlive() && !currentPilotedShip.isHulk()) {
            if (activeRemoteAI != null && activeRemoteAI.isFallbackTriggered()
                    && session.latestPilotInputAtMillis() > 0L
                    && nowMillis - session.latestPilotInputAtMillis() < CoopRemotePilotShipAI.INPUT_TIMEOUT_MILLIS) {
                attachRemotePilot(currentPilotedShip, currentPilotedNetId);
            }
            return;
        }
        // Current piloted ship died or hasn't spawned yet — auto-assign next alive partner ship
        int prevId = currentPilotedNetId;
        pickInitialPilotShip(nowMillis);
        if (currentPilotedNetId > 0 && currentPilotedNetId != prevId) {
            transport.sendTcp(CoopMessages.Type.COMBAT_EVENT,
                    CoopCombatNetCodec.encodeEvent(new CombatEvent(
                            battleId, CoopCombatNetCodec.EVT_PILOT_SWITCH,
                            currentPilotedNetId, 0, 0f, 0f, 0f, 0f, 0f, "")));
        }
    }

    private void processInboundEvents(CombatEngineAPI engine, String battleId, long nowMillis) {
        CombatEvent ev;
        while ((ev = session.pollAuthorityEvent()) != null) {
            switch (ev.kind()) {
                case CoopCombatNetCodec.EVT_PILOT_SWITCH -> handlePilotSwitch(battleId, ev.argInt1());
                case CoopCombatNetCodec.EVT_PAUSE -> {
                    boolean wantPause = ev.argInt1() != 0;
                    applyingRemotePause = true;
                    session.setRemotePaused(wantPause, ev.text());
                    try {
                        engine.setPaused(wantPause);
                        lastPausedState = engine.isPaused();
                    } catch (RuntimeException | LinkageError ignored) {
                    }
                }
                default -> {
                }
            }
        }
    }

    private void handlePilotSwitch(String battleId, int deltaOrTarget) {
        List<Integer> alive = new ArrayList<>();
        for (int nid : partnerOwnedShipNetIds) {
            ShipAPI s = netIdToShip.get(nid);
            if (s != null && s.isAlive() && !s.isHulk() && !s.isFighter()) {
                alive.add(nid);
            }
        }
        if (alive.isEmpty()) {
            return;
        }
        int idx = alive.indexOf(currentPilotedNetId);
        int nextIdx;
        if (deltaOrTarget == 1 || deltaOrTarget == -1) {
            nextIdx = idx < 0 ? 0 : Math.floorMod(idx + deltaOrTarget, alive.size());
        } else if (alive.contains(deltaOrTarget)) {
            nextIdx = alive.indexOf(deltaOrTarget);
        } else {
            nextIdx = 0;
        }
        int nextNetId = alive.get(nextIdx);
        ShipAPI nextShip = netIdToShip.get(nextNetId);
        if (nextShip != null) {
            attachRemotePilot(nextShip, nextNetId);
            transport.sendTcp(CoopMessages.Type.COMBAT_EVENT,
                    CoopCombatNetCodec.encodeEvent(new CombatEvent(
                            battleId, CoopCombatNetCodec.EVT_PILOT_SWITCH,
                            nextNetId, 0, 0f, 0f, 0f, 0f, 0f, nextShip.getName())));
        }
    }

    private void detectShipLifecycleEvents(CombatEngineAPI engine, String battleId, List<ShipAPI> allShips) {
        if (allShips == null) {
            return;
        }
        for (ShipAPI ship : allShips) {
            Integer netId = shipToNetId.get(ship);
            if (netId == null) {
                continue;
            }
            boolean wasAlive = Boolean.TRUE.equals(shipAliveLatch.get(netId));
            boolean nowAlive = ship.isAlive() && !ship.isHulk();
            if (wasAlive && !nowAlive) {
                shipAliveLatch.put(netId, false);
                Vector2f loc = ship.getLocation();
                String kind = ship.isRetreating()
                        ? CoopCombatNetCodec.EVT_SHIP_RETREATED
                        : (ship.isHulk() ? CoopCombatNetCodec.EVT_SHIP_HULK : CoopCombatNetCodec.EVT_SHIP_DEAD);
                transport.sendTcp(CoopMessages.Type.COMBAT_EVENT,
                        CoopCombatNetCodec.encodeEvent(new CombatEvent(
                                battleId, kind, netId, ship.isFighter() ? 1 : 0,
                                loc != null ? loc.x : 0f, loc != null ? loc.y : 0f,
                                ship.getCollisionRadius(), 0f, 0f, ship.getName())));
            }
        }
    }

    private void detectObjectiveFlips(CombatEngineAPI engine, String battleId) {
        List<BattleObjectiveAPI> objs = engine.getObjectives();
        if (objs == null) {
            return;
        }
        for (int i = 0; i < objs.size(); i++) {
            BattleObjectiveAPI o = objs.get(i);
            if (o == null) {
                continue;
            }
            String key = "obj-" + i;
            Integer prev = objectiveOwnerCache.get(key);
            int cur = o.getOwner();
            if (prev == null || prev != cur) {
                objectiveOwnerCache.put(key, cur);
                if (prev != null) {
                    transport.sendTcp(CoopMessages.Type.COMBAT_EVENT,
                            CoopCombatNetCodec.encodeEvent(new CombatEvent(
                                    battleId, CoopCombatNetCodec.EVT_OBJ_OWNER,
                                    i, cur, o.getLocation().x, o.getLocation().y, 0f, 0f, 0f, key)));
                }
            }
        }
    }

    private void trackProjectilesAndMissiles(CombatEngineAPI engine, String battleId) {
        for (TrackedProj tp : trackedProjs.values()) {
            tp.seenThisFrame = false;
        }
        List<ProjectileSpawn> newSpawns = null;
        List<DamagingProjectileAPI> projs = engine.getProjectiles();
        if (projs != null) {
            for (DamagingProjectileAPI p : projs) {
                if (p == null) {
                    continue;
                }
                TrackedProj tp = trackedProjs.get(p);
                if (tp == null) {
                    int pid = nextProjId++;
                    boolean isMissile = p instanceof MissileAPI;
                    Vector2f loc = p.getLocation();
                    Vector2f vel = p.getVelocity();
                    int srcNetId = p.getSource() == null ? 0 : shipToNetId.getOrDefault(p.getSource(), 0);
                    String weaponId = p.getWeapon() != null && p.getWeapon().getId() != null
                            ? p.getWeapon().getId()
                            : (p.getProjectileSpecId() != null ? p.getProjectileSpecId() : "");
                    int dmgOrd = p.getDamageType() == null ? 0 : p.getDamageType().ordinal();
                    tp = new TrackedProj(pid, isMissile,
                            loc != null ? loc.x : 0f, loc != null ? loc.y : 0f,
                            p.getDamageAmount(), dmgOrd);
                    tp.seenThisFrame = true;
                    trackedProjs.put(p, tp);
                    if (!weaponId.isEmpty()) {
                        if (newSpawns == null) {
                            newSpawns = new ArrayList<>();
                        }
                        newSpawns.add(new ProjectileSpawn(
                                pid, srcNetId, p.getOwner(), weaponId, isMissile,
                                loc != null ? loc.x : 0f, loc != null ? loc.y : 0f,
                                vel != null ? vel.x : 0f, vel != null ? vel.y : 0f,
                                p.getFacing()));
                    }
                } else {
                    tp.seenThisFrame = true;
                    Vector2f loc = p.getLocation();
                    if (loc != null) {
                        tp.lastX = loc.x;
                        tp.lastY = loc.y;
                    }
                    if (p.didDamage()) {
                        tp.damage = p.getDamageAmount();
                        CombatEntityAPI target = p.getDamageTarget();
                        if (target instanceof ShipAPI ts) {
                            tp.targetShipNetId = shipToNetId.getOrDefault(ts, 0);
                            ShieldAPI sh = ts.getShield();
                            tp.shieldHit = sh != null && sh.isOn() && loc != null && sh.isWithinArc(loc);
                        }
                    }
                }
            }
        }

        List<ProjectileHit> hits = null;
        Iterator<Map.Entry<DamagingProjectileAPI, TrackedProj>> it = trackedProjs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<DamagingProjectileAPI, TrackedProj> entry = it.next();
            DamagingProjectileAPI proj = entry.getKey();
            TrackedProj tp = entry.getValue();
            if (!tp.seenThisFrame || proj.isExpired() || proj.wasRemoved()) {
                boolean didHit = proj.didDamage();
                if (hits == null) {
                    hits = new ArrayList<>();
                }
                hits.add(new ProjectileHit(tp.projId, didHit, tp.lastX, tp.lastY,
                        didHit ? tp.damage : 0f, tp.damageTypeOrdinal, tp.shieldHit, tp.targetShipNetId));
                it.remove();
            }
        }

        if (newSpawns != null && !newSpawns.isEmpty()) {
            transport.sendTcp(CoopMessages.Type.COMBAT_PROJ_SPAWN,
                    CoopCombatNetCodec.encodeProjSpawns(new ProjectileSpawnBatch(battleId, newSpawns)));
        }
        if (hits != null && !hits.isEmpty()) {
            transport.sendTcp(CoopMessages.Type.COMBAT_PROJ_HIT,
                    CoopCombatNetCodec.encodeProjHits(new ProjectileHitBatch(battleId, hits)));
        }
    }

    private void streamShipStates(String battleId, List<ShipAPI> allShips, long nowMillis) {
        if (allShips == null || allShips.isEmpty()) {
            return;
        }
        stateTickSeq++;
        List<ShipStateSample> samples = new ArrayList<>(allShips.size());
        for (ShipAPI ship : allShips) {
            Integer netId = shipToNetId.get(ship);
            if (netId == null) {
                continue;
            }
            // Skip fighters on odd ticks to keep UDP bandwidth lean during carrier swarms
            if (ship.isFighter() && (stateTickSeq & 1) == 1) {
                continue;
            }
            Vector2f loc = ship.getLocation();
            Vector2f vel = ship.getVelocity();
            FluxTrackerAPI flux = ship.getFluxTracker();
            ShieldAPI shield = ship.getShield();
            ShipSystemAPI sys = ship.getSystem() != null ? ship.getSystem() : ship.getPhaseCloak();

            int flags = 0;
            if (shield != null && shield.isOn()) flags |= CoopCombatNetCodec.FLAG_SHIELD_ON;
            if (flux != null && flux.isOverloaded()) flags |= CoopCombatNetCodec.FLAG_OVERLOADED;
            if (flux != null && flux.isVenting()) flags |= CoopCombatNetCodec.FLAG_VENTING;
            if (ship.isPhased()) flags |= CoopCombatNetCodec.FLAG_PHASED;
            if (ship.isHulk()) flags |= CoopCombatNetCodec.FLAG_HULK;
            if (ship.isFighter()) flags |= CoopCombatNetCodec.FLAG_FIGHTER;
            if (!ship.isAlive()) flags |= CoopCombatNetCodec.FLAG_DEAD;
            if (ship.isRetreating()) flags |= CoopCombatNetCodec.FLAG_RETREATED;

            int[] turretAngles = new int[0];
            int[] weaponAmmo = new int[0];
            int disabledMask = 0;
            int autoMask = 0;
            if (!ship.isFighter() && ship.isAlive()) {
                List<WeaponAPI> weapons = ship.getAllWeapons();
                if (weapons != null && !weapons.isEmpty()) {
                    int n = Math.min(32, weapons.size());
                    turretAngles = new int[n];
                    weaponAmmo = new int[n];
                    for (int i = 0; i < n; i++) {
                        WeaponAPI w = weapons.get(i);
                        turretAngles[i] = w == null ? 0 : Math.round(w.getCurrAngle());
                        weaponAmmo[i] = (w != null && w.usesAmmo()) ? w.getAmmo() : -1;
                        if (w != null && w.isDisabled()) {
                            disabledMask |= (1 << i);
                        }
                    }
                }
                List<WeaponGroupAPI> groups = ship.getWeaponGroupsCopy();
                if (groups != null) {
                    for (int i = 0; i < Math.min(7, groups.size()); i++) {
                        WeaponGroupAPI g = groups.get(i);
                        if (g != null && g.isAutofiring()) {
                            autoMask |= (1 << i);
                        }
                    }
                }
            }

            int sysStateOrd = sys != null && sys.getState() != null ? sys.getState().ordinal() : 0;
            float sysEff = sys != null ? sys.getEffectLevel() : 0f;
            int sysAmmo = sys != null ? sys.getAmmo() : 0;
            float sysCd = sys != null ? sys.getCooldownRemaining() : 0f;

            samples.add(new ShipStateSample(
                    netId,
                    loc != null ? loc.x : 0f, loc != null ? loc.y : 0f,
                    vel != null ? vel.x : 0f, vel != null ? vel.y : 0f,
                    ship.getFacing(), ship.getAngularVelocity(),
                    ship.getHitpoints(), ship.getMaxHitpoints(),
                    ship.getCurrentCR(),
                    flux != null ? flux.getCurrFlux() : 0f,
                    flux != null ? flux.getHardFlux() : 0f,
                    flux != null ? flux.getOverloadTimeRemaining() : 0f,
                    flags,
                    shield != null ? shield.getFacing() : ship.getFacing(),
                    shield != null ? shield.getActiveArc() : 0f,
                    sysStateOrd, sysEff, sysAmmo, sysCd,
                    turretAngles, weaponAmmo, disabledMask, autoMask));
        }

        List<String> chunks = CoopCombatNetCodec.encodeShipStateChunks(
                battleId, stateTickSeq, samples, CoopCombatNetCodec.DEFAULT_UDP_BODY_LIMIT);
        for (String chunk : chunks) {
            transport.sendUdp(CoopMessages.Type.COMBAT_STATE, chunk, nowMillis);
        }
    }

    private void streamArmorDeltas(String battleId, List<ShipAPI> allShips) {
        if (allShips == null) {
            return;
        }
        for (ShipAPI ship : allShips) {
            if (ship == null || ship.isFighter() || !ship.isAlive()) {
                continue;
            }
            Integer netId = shipToNetId.get(ship);
            if (netId == null) {
                continue;
            }
            ArmorGridAPI armor = ship.getArmorGrid();
            if (armor == null) {
                continue;
            }
            float[][] grid = armor.getGrid();
            float maxCell = armor.getMaxArmorInCell();
            if (grid == null || grid.length == 0 || maxCell <= 0f) {
                continue;
            }
            int gw = grid.length;
            int gh = grid[0].length;
            byte[][] prev = armorCache.get(netId);
            boolean first = (prev == null || prev.length != gw || prev[0].length != gh);
            if (first) {
                prev = new byte[gw][gh];
                for (int x = 0; x < gw; x++) {
                    for (int y = 0; y < gh; y++) {
                        prev[x][y] = (byte) 255;
                    }
                }
                armorCache.put(netId, prev);
            }
            List<ArmorCell> dirty = null;
            for (int x = 0; x < gw; x++) {
                float[] col = grid[x];
                for (int y = 0; y < gh; y++) {
                    int q = Math.max(0, Math.min(255, Math.round((col[y] / maxCell) * 255f)));
                    int oldQ = prev[x][y] & 0xFF;
                    if (Math.abs(q - oldQ) >= 2 || (q == 0 && oldQ != 0)) {
                        prev[x][y] = (byte) q;
                        if (dirty == null) {
                            dirty = new ArrayList<>();
                        }
                        dirty.add(new ArmorCell(x, y, q));
                    }
                }
            }
            if (dirty != null && !dirty.isEmpty()) {
                transport.sendTcp(CoopMessages.Type.COMBAT_ARMOR_DELTA,
                        CoopCombatNetCodec.encodeArmorDelta(new ArmorDelta(battleId, netId, gw, gh, dirty)));
            }
        }
    }

    private void streamMissileStates(CombatEngineAPI engine, String battleId, long nowMillis) {
        List<MissileAPI> missiles = engine.getMissiles();
        if (missiles == null || missiles.isEmpty()) {
            return;
        }
        missileTickSeq++;
        List<MissileSample> list = new ArrayList<>(missiles.size());
        for (MissileAPI m : missiles) {
            TrackedProj tp = trackedProjs.get(m);
            if (tp == null) {
                continue;
            }
            Vector2f loc = m.getLocation();
            Vector2f vel = m.getVelocity();
            list.add(new MissileSample(
                    tp.projId,
                    loc != null ? loc.x : 0f, loc != null ? loc.y : 0f,
                    vel != null ? vel.x : 0f, vel != null ? vel.y : 0f,
                    m.getFacing(), m.isFizzling()));
        }
        if (list.isEmpty()) {
            return;
        }
        List<String> chunks = CoopCombatNetCodec.encodeMissileStateChunks(
                battleId, missileTickSeq, list, CoopCombatNetCodec.DEFAULT_UDP_BODY_LIMIT);
        for (String chunk : chunks) {
            transport.sendUdp(CoopMessages.Type.COMBAT_MISSILE_STATE, chunk, nowMillis);
        }
    }

    private void streamBeams(CombatEngineAPI engine, String battleId, long nowMillis) {
        List<BeamAPI> beams = engine.getBeams();
        if (beams == null || beams.isEmpty()) {
            if (lastSentBeamsNonEmpty) {
                lastSentBeamsNonEmpty = false;
                beamTickSeq++;
                for (String chunk : CoopCombatNetCodec.encodeBeamChunks(
                        battleId, beamTickSeq, List.of(), CoopCombatNetCodec.DEFAULT_UDP_BODY_LIMIT)) {
                    transport.sendUdp(CoopMessages.Type.COMBAT_BEAMS, chunk, nowMillis);
                }
            }
            return;
        }
        lastSentBeamsNonEmpty = true;
        beamTickSeq++;
        List<BeamSample> list = new ArrayList<>(beams.size());
        for (BeamAPI b : beams) {
            if (b == null || b.getFrom() == null || b.getTo() == null) {
                continue;
            }
            Color core = b.getCoreColor() != null ? b.getCoreColor() : Color.WHITE;
            Color fringe = b.getFringeColor() != null ? b.getFringeColor() : Color.CYAN;
            list.add(new BeamSample(
                    b.getFrom().x, b.getFrom().y,
                    b.getTo().x, b.getTo().y,
                    Math.max(2f, b.getWidth()),
                    core.getRGB(), fringe.getRGB(),
                    b.didDamageThisFrame() || b.getDamageTarget() != null));
        }
        for (String chunk : CoopCombatNetCodec.encodeBeamChunks(
                battleId, beamTickSeq, list, CoopCombatNetCodec.DEFAULT_UDP_BODY_LIMIT)) {
            transport.sendUdp(CoopMessages.Type.COMBAT_BEAMS, chunk, nowMillis);
        }
    }

    private List<ObjectiveInit> captureObjectives(CombatEngineAPI engine) {
        List<ObjectiveInit> out = new ArrayList<>();
        List<BattleObjectiveAPI> objs = engine.getObjectives();
        if (objs == null) {
            return out;
        }
        for (int i = 0; i < objs.size(); i++) {
            BattleObjectiveAPI o = objs.get(i);
            if (o == null || o.getLocation() == null) {
                continue;
            }
            String key = "obj-" + i;
            objectiveOwnerCache.put(key, o.getOwner());
            out.add(new ObjectiveInit(key, o.getType(), o.getLocation().x, o.getLocation().y, o.getOwner()));
        }
        return out;
    }
}
