package coop.combat.live;

import coop.combat.live.CoopCombatNetCodec.ArmorDelta;
import coop.combat.live.CoopCombatNetCodec.BeamStateBatch;
import coop.combat.live.CoopCombatNetCodec.CombatEvent;
import coop.combat.live.CoopCombatNetCodec.CombatInit;
import coop.combat.live.CoopCombatNetCodec.MissileStateBatch;
import coop.combat.live.CoopCombatNetCodec.PilotInput;
import coop.combat.live.CoopCombatNetCodec.ProjectileHitBatch;
import coop.combat.live.CoopCombatNetCodec.ProjectileSpawnBatch;
import coop.combat.live.CoopCombatNetCodec.ShipSpawn;
import coop.combat.live.CoopCombatNetCodec.ShipStateBatch;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Thread-safe session state and bounded inbound buffers shared between campaign networking
 * ({@code CoopNetPump}) and live combat plugins ({@code CoopLiveCombatAuthority} /
 * {@code CoopLiveCombatClient}).
 *
 * <p>Because {@code COMBAT_INIT} and early {@code COMBAT_SPAWN} frames arrive on the campaign thread
 * right before {@code CampaignUIAPI.startBattle} switches to {@code GameState.COMBAT}, buffering
 * them here guarantees the puppet combat engine receives every initial ship and objective on its
 * very first frame with zero race conditions.
 */
public final class CoopLiveCombatSession {

    public enum Role {
        NONE,
        AUTHORITY,
        CLIENT
    }

    private static final int MAX_BUFFERED_TCP = 512;
    private static final int MAX_BUFFERED_UDP = 64;

    private Role role = Role.NONE;
    private String battleId = "";
    private String partnerPlayerId = "";
    private String partnerDisplayName = "";
    private boolean liveCombatRequestedForNextBattle;

    // Client launch latch: set when COMBAT_INIT arrives on campaign thread; consumed by startBattle
    private CombatInit pendingClientInit;
    private CombatInit activeClientInit;
    private boolean clientArenaLaunchRequested;
    private boolean clientArenaActive;

    // Pilot control state
    private int pilotedShipNetId;
    private PilotInput latestPilotInput;
    private long latestPilotInputAtMillis;
    private int lastAcceptedInputSeq = -1;

    // Pause synchronization
    private boolean remotePaused;
    private String pausedByName = "";

    // Client inbound queues (written by pump or combat drain, consumed by CoopLiveCombatClient)
    private final Deque<ShipSpawn> inboundSpawns = new ArrayDeque<>();
    private final Deque<ShipStateBatch> inboundStates = new ArrayDeque<>();
    private final Deque<ArmorDelta> inboundArmorDeltas = new ArrayDeque<>();
    private final Deque<ProjectileSpawnBatch> inboundProjSpawns = new ArrayDeque<>();
    private final Deque<ProjectileHitBatch> inboundProjHits = new ArrayDeque<>();
    private final Deque<MissileStateBatch> inboundMissiles = new ArrayDeque<>();
    private final Deque<BeamStateBatch> inboundBeams = new ArrayDeque<>();
    private final Deque<CombatEvent> inboundEvents = new ArrayDeque<>();

    // Authority inbound events from client (PILOT_SWITCH, PAUSE, etc.)
    private final Deque<CombatEvent> authorityInboundEvents = new ArrayDeque<>();

    // Per-ship desired pilot assignment map (partnerPlayerId -> netId)
    private final Map<String, Integer> pilotShipByPlayer = new HashMap<>();

    public synchronized Role role() {
        return role;
    }

    public synchronized String battleId() {
        return battleId;
    }

    public synchronized String partnerPlayerId() {
        return partnerPlayerId;
    }

    public synchronized String partnerDisplayName() {
        return partnerDisplayName;
    }

    /** Authority-side flag: partner accepted {@code JOIN_LIVE_COMBAT} for the upcoming engagement. */
    public synchronized void armAuthorityForPartner(String partnerId, String partnerName) {
        this.partnerPlayerId = partnerId == null ? "" : partnerId.trim();
        this.partnerDisplayName = partnerName == null || partnerName.isBlank() ? "Partner" : partnerName.trim();
        this.liveCombatRequestedForNextBattle = true;
    }

    public synchronized boolean isAuthorityArmed() {
        return liveCombatRequestedForNextBattle && !partnerPlayerId.isEmpty();
    }

    public synchronized void beginAuthorityBattle(String battleId) {
        clearQueuesLocked();
        this.role = Role.AUTHORITY;
        this.battleId = battleId == null ? "" : battleId.trim();
        this.liveCombatRequestedForNextBattle = false;
        this.pilotedShipNetId = 0;
        this.latestPilotInput = null;
        this.latestPilotInputAtMillis = 0L;
        this.lastAcceptedInputSeq = -1;
        this.remotePaused = false;
        this.pausedByName = "";
        this.pilotShipByPlayer.clear();
    }

    /** Client-side: receives {@code COMBAT_INIT} and arms arena launch on the campaign thread. */
    public synchronized void acceptClientInit(CombatInit init, String authorityPlayerId) {
        if (init == null || init.battleId().isEmpty()) {
            return;
        }
        clearQueuesLocked();
        this.role = Role.CLIENT;
        this.battleId = init.battleId();
        this.partnerPlayerId = authorityPlayerId == null ? "" : authorityPlayerId.trim();
        this.partnerDisplayName = init.authorityName().isEmpty() ? "Partner" : init.authorityName();
        this.pendingClientInit = init;
        this.activeClientInit = init;
        this.pilotedShipNetId = init.initialPilotNetId();
        this.clientArenaLaunchRequested = true;
        this.clientArenaActive = false;
        this.remotePaused = false;
        this.pausedByName = "";
    }

    public synchronized CombatInit consumePendingClientLaunch() {
        if (!clientArenaLaunchRequested || pendingClientInit == null) {
            return null;
        }
        clientArenaLaunchRequested = false;
        clientArenaActive = true;
        return pendingClientInit;
    }

    public synchronized boolean isClientArenaActive() {
        return role == Role.CLIENT && (clientArenaActive || clientArenaLaunchRequested);
    }

    public synchronized CombatInit activeClientInit() {
        return activeClientInit;
    }

    public synchronized void setPilotedShipNetId(int netId) {
        this.pilotedShipNetId = netId;
        if (!partnerPlayerId.isEmpty() && netId > 0) {
            pilotShipByPlayer.put(partnerPlayerId, netId);
        }
    }

    public synchronized int pilotedShipNetId() {
        return pilotedShipNetId;
    }

    public synchronized void acceptPilotInput(PilotInput input, long nowMillis) {
        if (input == null || role != Role.AUTHORITY) {
            return;
        }
        if (!battleId.isEmpty() && !input.battleId().isEmpty() && !battleId.equals(input.battleId())) {
            return;
        }
        // Accept strictly newer sequence numbers (with wraparound tolerance)
        if (lastAcceptedInputSeq >= 0 && input.seq() <= lastAcceptedInputSeq
                && (lastAcceptedInputSeq - input.seq()) < 10000) {
            return;
        }
        lastAcceptedInputSeq = input.seq();
        latestPilotInput = input;
        latestPilotInputAtMillis = nowMillis;
    }

    public synchronized PilotInput latestPilotInput() {
        return latestPilotInput;
    }

    public synchronized long latestPilotInputAtMillis() {
        return latestPilotInputAtMillis;
    }

    public synchronized void setRemotePaused(boolean paused, String byName) {
        this.remotePaused = paused;
        this.pausedByName = byName == null ? "" : byName.trim();
    }

    public synchronized boolean isRemotePaused() {
        return remotePaused;
    }

    public synchronized String pausedByName() {
        return pausedByName;
    }

    // ---- Inbound buffering ----

    public synchronized void enqueueSpawn(ShipSpawn spawn) {
        if (spawn != null && matchesActiveBattle(spawn.battleId())) {
            offerBounded(inboundSpawns, spawn, MAX_BUFFERED_TCP);
        }
    }

    public synchronized ShipSpawn pollSpawn() {
        return inboundSpawns.pollFirst();
    }

    public synchronized void enqueueState(ShipStateBatch batch) {
        if (batch != null && matchesActiveBattle(batch.battleId())) {
            offerBounded(inboundStates, batch, MAX_BUFFERED_UDP);
        }
    }

    public synchronized ShipStateBatch pollState() {
        return inboundStates.pollFirst();
    }

    public synchronized void enqueueArmorDelta(ArmorDelta delta) {
        if (delta != null && matchesActiveBattle(delta.battleId())) {
            offerBounded(inboundArmorDeltas, delta, MAX_BUFFERED_TCP);
        }
    }

    public synchronized ArmorDelta pollArmorDelta() {
        return inboundArmorDeltas.pollFirst();
    }

    public synchronized void enqueueProjSpawns(ProjectileSpawnBatch batch) {
        if (batch != null && matchesActiveBattle(batch.battleId())) {
            offerBounded(inboundProjSpawns, batch, MAX_BUFFERED_TCP);
        }
    }

    public synchronized ProjectileSpawnBatch pollProjSpawns() {
        return inboundProjSpawns.pollFirst();
    }

    public synchronized void enqueueProjHits(ProjectileHitBatch batch) {
        if (batch != null && matchesActiveBattle(batch.battleId())) {
            offerBounded(inboundProjHits, batch, MAX_BUFFERED_TCP);
        }
    }

    public synchronized ProjectileHitBatch pollProjHits() {
        return inboundProjHits.pollFirst();
    }

    public synchronized void enqueueMissiles(MissileStateBatch batch) {
        if (batch != null && matchesActiveBattle(batch.battleId())) {
            offerBounded(inboundMissiles, batch, MAX_BUFFERED_UDP);
        }
    }

    public synchronized MissileStateBatch pollMissiles() {
        return inboundMissiles.pollFirst();
    }

    public synchronized void enqueueBeams(BeamStateBatch batch) {
        if (batch != null && matchesActiveBattle(batch.battleId())) {
            offerBounded(inboundBeams, batch, MAX_BUFFERED_UDP);
        }
    }

    public synchronized BeamStateBatch pollBeams() {
        return inboundBeams.pollFirst();
    }

    public synchronized void enqueueEvent(CombatEvent event) {
        if (event == null || !matchesActiveBattle(event.battleId())) {
            return;
        }
        if (role == Role.AUTHORITY) {
            offerBounded(authorityInboundEvents, event, MAX_BUFFERED_TCP);
        } else if (role == Role.CLIENT) {
            offerBounded(inboundEvents, event, MAX_BUFFERED_TCP);
        }
    }

    public synchronized CombatEvent pollClientEvent() {
        return inboundEvents.pollFirst();
    }

    public synchronized CombatEvent pollAuthorityEvent() {
        return authorityInboundEvents.pollFirst();
    }

    public synchronized void endBattle() {
        role = Role.NONE;
        battleId = "";
        liveCombatRequestedForNextBattle = false;
        pendingClientInit = null;
        activeClientInit = null;
        clientArenaLaunchRequested = false;
        clientArenaActive = false;
        pilotedShipNetId = 0;
        latestPilotInput = null;
        latestPilotInputAtMillis = 0L;
        lastAcceptedInputSeq = -1;
        remotePaused = false;
        pausedByName = "";
        pilotShipByPlayer.clear();
        clearQueuesLocked();
    }

    public synchronized void reset() {
        endBattle();
        partnerPlayerId = "";
        partnerDisplayName = "";
    }

    private boolean matchesActiveBattle(String incomingBattleId) {
        if (role == Role.NONE) {
            return false;
        }
        if (battleId.isEmpty() || incomingBattleId == null || incomingBattleId.isEmpty()) {
            return true;
        }
        return battleId.equals(incomingBattleId);
    }

    private void clearQueuesLocked() {
        inboundSpawns.clear();
        inboundStates.clear();
        inboundArmorDeltas.clear();
        inboundProjSpawns.clear();
        inboundProjHits.clear();
        inboundMissiles.clear();
        inboundBeams.clear();
        inboundEvents.clear();
        authorityInboundEvents.clear();
    }

    private static <T> void offerBounded(Deque<T> q, T item, int cap) {
        while (q.size() >= cap) {
            q.pollFirst();
        }
        q.addLast(item);
    }
}
