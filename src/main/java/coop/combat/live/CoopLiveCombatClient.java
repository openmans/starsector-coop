package coop.combat.live;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.MissileAIPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.loading.VariantSource;
import coop.combat.live.CoopCombatNetCodec.ArmorCell;
import coop.combat.live.CoopCombatNetCodec.ArmorDelta;
import coop.combat.live.CoopCombatNetCodec.BeamStateBatch;
import coop.combat.live.CoopCombatNetCodec.CombatEvent;
import coop.combat.live.CoopCombatNetCodec.MissileSample;
import coop.combat.live.CoopCombatNetCodec.MissileStateBatch;
import coop.combat.live.CoopCombatNetCodec.ProjectileHit;
import coop.combat.live.CoopCombatNetCodec.ProjectileHitBatch;
import coop.combat.live.CoopCombatNetCodec.ProjectileSpawn;
import coop.combat.live.CoopCombatNetCodec.ProjectileSpawnBatch;
import coop.combat.live.CoopCombatNetCodec.ShipSpawn;
import coop.combat.live.CoopCombatNetCodec.ShipStateBatch;
import coop.combat.live.CoopCombatNetCodec.ShipStateSample;
import coop.combat.live.CoopCombatNetCodec.SlotWeapon;
import coop.net.CoopMessages;
import coop.ui.CoopUiLang;
import coop.util.CoopLog;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Joining partner's real-time puppet combat client. Runs inside an isolated Starsector
 * {@link CombatEngineAPI}, captures live piloting inputs ({@link CoopCombatInputCapture}) at up to
 * 60 Hz over UDP, and mirrors every ship, fighter, shield, armor cell, flux bar, ship system,
 * turret, ballistic/energy projectile, guided missile, beam, explosion, and combat pause directly
 * from the authoritative combat host.
 */
public final class CoopLiveCombatClient {

    private static final long INPUT_INTERVAL_MILLIS = 16L; // ~60 Hz live input stream
    private static final String PUPPET_IMMUNITY_ID = "coop_live_puppet_immunity";
    private static final String HUD_STATUS_KEY = "coop_live_combat_status";

    private static final class ShipInterpTarget {
        float x, y, vx, vy, facing, angVel;
        long receivedAtMillis;
    }

    private static final ShipAIPlugin DUMMY_SHIP_AI = new ShipAIPlugin() {
        private final ShipwideAIFlags flags = new ShipwideAIFlags();
        private final ShipAIConfig cfg = new ShipAIConfig();
        @Override public void setDoNotFireDelay(float amount) {}
        @Override public void forceCircumstanceEvaluation() {}
        @Override public void advance(float amount) {}
        @Override public boolean needsRefit() { return false; }
        @Override public ShipwideAIFlags getAIFlags() { return flags; }
        @Override public void cancelCurrentManeuver() {}
        @Override public ShipAIConfig getConfig() { return cfg; }
    };

    private static final MissileAIPlugin DUMMY_MISSILE_AI = amount -> {};

    private final CoopLiveCombatSession session;
    private final CoopLiveCombatAuthority.Transport transport;
    private final String localDisplayName;
    private final CoopCombatInputCapture inputCapture = new CoopCombatInputCapture();
    private final CoopCombatBeamRenderer beamRenderer = new CoopCombatBeamRenderer();

    private boolean arenaInitialized;
    private long nextInputAtMillis;
    private int lastStateTickSeq = -1;
    private int lastMissileTickSeq = -1;
    private int lastBeamTickSeq = -1;

    private final Map<Integer, ShipAPI> shipsByNetId = new HashMap<>();
    private final Map<Integer, ShipInterpTarget> interpTargets = new HashMap<>();
    private final Map<Integer, DamagingProjectileAPI> projectilesById = new HashMap<>();

    public CoopLiveCombatClient(CoopLiveCombatSession session,
                                CoopLiveCombatAuthority.Transport transport,
                                String localDisplayName) {
        this.session = Objects.requireNonNull(session, "session");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.localDisplayName = localDisplayName == null || localDisplayName.isBlank()
                ? "Partner" : localDisplayName.trim();
    }

    public void onFrame(CombatEngineAPI engine, float dt, List<InputEventAPI> events, long nowMillis) {
        if (engine == null || session.role() != CoopLiveCombatSession.Role.CLIENT) {
            return;
        }
        try {
            if (!arenaInitialized) {
                initPuppetArena(engine);
            }

            // 1. Spawn any newly arrived ships/fighters
            ShipSpawn sp;
            while ((sp = session.pollSpawn()) != null) {
                spawnShipFromNet(engine, sp);
            }

            // 2. Apply latest authoritative ship states
            ShipStateBatch batch;
            while ((batch = session.pollState()) != null) {
                if (batch.tickSeq() >= lastStateTickSeq || (lastStateTickSeq - batch.tickSeq()) > 5000) {
                    lastStateTickSeq = batch.tickSeq();
                    applyShipStates(batch, nowMillis);
                }
            }

            // 3. Smoothly interpolate ship kinematics every render frame
            advanceInterpolation(dt, nowMillis);

            // 4. Apply delta-compressed armor grid damage
            ArmorDelta delta;
            while ((delta = session.pollArmorDelta()) != null) {
                applyArmorDelta(delta);
            }

            // 5. Spawn/remove projectiles & update guided missiles
            ProjectileSpawnBatch pSpawns;
            while ((pSpawns = session.pollProjSpawns()) != null) {
                applyProjSpawns(engine, pSpawns);
            }
            ProjectileHitBatch pHits;
            while ((pHits = session.pollProjHits()) != null) {
                applyProjHits(engine, pHits);
            }
            MissileStateBatch mBatch;
            while ((mBatch = session.pollMissiles()) != null) {
                if (mBatch.tickSeq() >= lastMissileTickSeq || (lastMissileTickSeq - mBatch.tickSeq()) > 5000) {
                    lastMissileTickSeq = mBatch.tickSeq();
                    applyMissiles(mBatch);
                }
            }

            // 6. Update synchronized beams
            BeamStateBatch bBatch;
            while ((bBatch = session.pollBeams()) != null) {
                if (bBatch.tickSeq() >= lastBeamTickSeq || (lastBeamTickSeq - bBatch.tickSeq()) > 5000) {
                    lastBeamTickSeq = bBatch.tickSeq();
                    beamRenderer.setBeams(bBatch.beams(), nowMillis);
                }
            }

            // 7. Apply reliable combat events (deaths, EMP arcs, explosions, pilot switch, pause, end)
            CombatEvent ev;
            while ((ev = session.pollClientEvent()) != null) {
                if (applyCombatEvent(engine, ev)) {
                    return; // COMBAT_END triggered exit
                }
            }

            // 8. Ensure camera/HUD stays attached to our piloted ship and suppress local physics fire
            ShipAPI piloted = shipsByNetId.get(session.pilotedShipNetId());
            if (piloted != null && engine.getPlayerShip() != piloted) {
                try {
                    engine.setPlayerShipExternal(piloted);
                } catch (RuntimeException | LinkageError ignored) {
                }
            }
            suppressLocalAutonomy();

            // 9. Capture live piloting input and send over UDP (~60 Hz)
            CoopCombatInputCapture.CaptureResult cap = inputCapture.capture(
                    engine, session.battleId(), piloted, session.pilotedShipNetId(), events, nowMillis);
            if (cap.shipSwitchDelta() != 0) {
                transport.sendTcp(CoopMessages.Type.COMBAT_EVENT,
                        CoopCombatNetCodec.encodeEvent(new CombatEvent(
                                session.battleId(), CoopCombatNetCodec.EVT_PILOT_SWITCH,
                                cap.shipSwitchDelta(), 0, 0f, 0f, 0f, 0f, 0f, "")));
            }
            if (cap.pauseTogglePressed()) {
                boolean wantPause = !engine.isPaused();
                transport.sendTcp(CoopMessages.Type.COMBAT_EVENT,
                        CoopCombatNetCodec.encodeEvent(new CombatEvent(
                                session.battleId(), CoopCombatNetCodec.EVT_PAUSE,
                                wantPause ? 1 : 0, 0, 0f, 0f, 0f, 0f, 0f, localDisplayName)));
            }
            if (nowMillis >= nextInputAtMillis) {
                nextInputAtMillis = nowMillis + INPUT_INTERVAL_MILLIS;
                transport.sendUdp(CoopMessages.Type.COMBAT_INPUT,
                        CoopCombatNetCodec.encodeInput(cap.input()), nowMillis);
            }

            // 10. Crisp native status banner at bottom-left of Combat HUD
            renderHudStatus(engine, piloted);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopLiveCombatClient.class, "Coop live combat client frame error", ex);
        }
    }

    public void forceExit(CombatEngineAPI engine) {
        if (engine != null) {
            try {
                engine.setDoNotEndCombat(false);
                engine.endCombat(0f);
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
        resetState();
    }

    public void resetState() {
        arenaInitialized = false;
        nextInputAtMillis = 0L;
        lastStateTickSeq = -1;
        lastMissileTickSeq = -1;
        lastBeamTickSeq = -1;
        shipsByNetId.clear();
        interpTargets.clear();
        projectilesById.clear();
        inputCapture.reset();
        beamRenderer.clear();
    }

    private void initPuppetArena(CombatEngineAPI engine) {
        arenaInitialized = true;
        engine.setDoNotEndCombat(true);
        try {
            CombatFleetManagerAPI fm0 = engine.getFleetManager(0);
            CombatFleetManagerAPI fm1 = engine.getFleetManager(1);
            if (fm0 != null) fm0.setSuppressDeploymentMessages(true);
            if (fm1 != null) fm1.setSuppressDeploymentMessages(true);
        } catch (RuntimeException | LinkageError ignored) {
        }
        // Remove the temporary starter dummy ships created only to enter CombatEngineAPI
        List<ShipAPI> initialShips = engine.getShips();
        if (initialShips != null) {
            for (ShipAPI dummy : List.copyOf(initialShips)) {
                if (dummy != null && !shipsByNetId.containsValue(dummy)) {
                    try {
                        engine.removeEntity(dummy);
                    } catch (RuntimeException | LinkageError ignored) {
                    }
                }
            }
        }
        engine.addLayeredRenderingPlugin(beamRenderer);
    }

    private void spawnShipFromNet(CombatEngineAPI engine, ShipSpawn sp) {
        if (shipsByNetId.containsKey(sp.netId())) {
            return;
        }
        try {
            CombatFleetManagerAPI fm = engine.getFleetManager(sp.owner() == 1 ? 1 : 0);
            if (fm == null) {
                return;
            }
            ShipAPI spawned;
            Vector2f loc = new Vector2f(sp.x(), sp.y());
            if (sp.fighter()) {
                String vId = !sp.variantId().isEmpty() ? sp.variantId() : sp.hullId() + "_wing";
                spawned = fm.spawnShipOrWing(vId, loc, sp.facing(), 0f);
            } else {
                FleetMemberAPI member = buildSyncedMember(sp);
                if (member == null) {
                    return;
                }
                spawned = fm.spawnFleetMember(member, loc, sp.facing(), 0f);
            }
            if (spawned == null) {
                return;
            }
            configurePuppetShip(spawned, sp);
            shipsByNetId.put(sp.netId(), spawned);

            ShipInterpTarget t = new ShipInterpTarget();
            t.x = sp.x();
            t.y = sp.y();
            t.vx = sp.vx();
            t.vy = sp.vy();
            t.facing = sp.facing();
            t.receivedAtMillis = System.currentTimeMillis();
            interpTargets.put(sp.netId(), t);

            if (sp.netId() == session.pilotedShipNetId()) {
                engine.setPlayerShipExternal(spawned);
                inputCapture.resetForShipSwitch(spawned);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopLiveCombatClient.class,
                    "Could not spawn synced ship netId=" + sp.netId() + " hull=" + sp.hullId(), ex);
        }
    }

    private static FleetMemberAPI buildSyncedMember(ShipSpawn sp) {
        ShipVariantAPI variant = null;
        try {
            if (!sp.variantId().isEmpty() && Global.getSettings().doesVariantExist(sp.variantId())) {
                variant = Global.getSettings().getVariant(sp.variantId()).clone();
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
        if (variant == null && !sp.hullId().isEmpty()) {
            try {
                ShipHullSpecAPI hull = Global.getSettings().getHullSpec(sp.hullId());
                if (hull != null) {
                    String vid = sp.variantId().isEmpty() ? (sp.hullId() + "_coop_live") : sp.variantId();
                    variant = Global.getSettings().createEmptyVariant(vid, hull);
                }
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
        if (variant == null) {
            return null;
        }
        variant.setSource(VariantSource.REFIT);
        variant.setNumFluxVents(Math.max(0, sp.fluxVents()));
        variant.setNumFluxCapacitors(Math.max(0, sp.fluxCaps()));

        if (!sp.weapons().isEmpty()) {
            for (String slot : List.copyOf(variant.getFittedWeaponSlots())) {
                variant.clearSlot(slot);
            }
            for (SlotWeapon sw : sp.weapons()) {
                try {
                    variant.addWeapon(sw.slotId(), sw.weaponId());
                } catch (RuntimeException ignored) {
                }
            }
        }
        for (String hm : sp.hullMods()) {
            if (!variant.hasHullMod(hm)) {
                try {
                    variant.addMod(hm);
                } catch (RuntimeException ignored) {
                }
            }
        }
        for (String sm : sp.sMods()) {
            try {
                variant.addPermaMod(sm, true);
            } catch (RuntimeException ignored) {
            }
        }
        if (!sp.wings().isEmpty()) {
            variant.getWings().clear();
            variant.getWings().addAll(sp.wings());
        }

        FleetMemberAPI member = Global.getFactory().createFleetMember(FleetMemberType.SHIP, variant);
        if (!sp.shipName().isEmpty()) {
            member.setShipName(sp.shipName());
        }
        return member;
    }

    private static void configurePuppetShip(ShipAPI ship, ShipSpawn sp) {
        ship.getLocation().set(sp.x(), sp.y());
        ship.getVelocity().set(sp.vx(), sp.vy());
        ship.setFacing(sp.facing());
        ship.setHitpoints(Math.max(1f, sp.hp()));
        ship.setCRAtDeployment(Math.max(0f, Math.min(1f, sp.cr())));
        ship.setCurrentCR(Math.max(0f, Math.min(1f, sp.cr())));
        ship.setShipAI(DUMMY_SHIP_AI);

        MutableShipStatsAPI stats = ship.getMutableStats();
        if (stats != null) {
            stats.getHullDamageTakenMult().modifyMult(PUPPET_IMMUNITY_ID, 0f);
            stats.getArmorDamageTakenMult().modifyMult(PUPPET_IMMUNITY_ID, 0f);
            stats.getShieldDamageTakenMult().modifyMult(PUPPET_IMMUNITY_ID, 0f);
            stats.getEmpDamageTakenMult().modifyMult(PUPPET_IMMUNITY_ID, 0f);
        }
    }

    private void applyShipStates(ShipStateBatch batch, long nowMillis) {
        for (ShipStateSample s : batch.ships()) {
            ShipAPI ship = shipsByNetId.get(s.netId());
            if (ship == null) {
                continue;
            }
            ShipInterpTarget t = interpTargets.computeIfAbsent(s.netId(), k -> new ShipInterpTarget());
            t.x = s.x();
            t.y = s.y();
            t.vx = s.vx();
            t.vy = s.vy();
            t.facing = s.facing();
            t.angVel = s.angVel();
            t.receivedAtMillis = nowMillis;

            // Hull, CR, phase state
            if (ship.isAlive() && s.hp() > 0f) {
                ship.setHitpoints(Math.min(ship.getMaxHitpoints(), Math.max(1f, s.hp())));
            }
            ship.setCurrentCR(s.cr());
            ship.setPhased((s.flags() & CoopCombatNetCodec.FLAG_PHASED) != 0);

            // Shield state
            ShieldAPI shield = ship.getShield();
            if (shield != null && shield.getType() != ShieldAPI.ShieldType.NONE) {
                boolean on = (s.flags() & CoopCombatNetCodec.FLAG_SHIELD_ON) != 0;
                if (on) {
                    if (shield.isOff()) {
                        shield.toggleOn();
                    }
                    shield.forceFacing(s.shieldFacing());
                    shield.setActiveArc(s.shieldArc());
                } else if (shield.isOn()) {
                    shield.toggleOff();
                }
            }

            // Flux, Overload & Venting
            FluxTrackerAPI flux = ship.getFluxTracker();
            if (flux != null) {
                flux.setCurrFlux(Math.max(0f, s.currFlux()));
                flux.setHardFlux(Math.max(0f, Math.min(s.currFlux(), s.hardFlux())));
                boolean overloaded = (s.flags() & CoopCombatNetCodec.FLAG_OVERLOADED) != 0;
                if (overloaded && !flux.isOverloaded()) {
                    flux.forceOverload(Math.max(0.25f, s.overloadRem()));
                } else if (!overloaded && flux.isOverloaded()) {
                    flux.stopOverload();
                }
                boolean venting = (s.flags() & CoopCombatNetCodec.FLAG_VENTING) != 0;
                if (venting && !flux.isVenting()) {
                    flux.ventFlux();
                } else if (!venting && flux.isVenting()) {
                    flux.stopVenting();
                }
            }

            // Ship System / Phase Cloak visual state & HUD ammo/cooldown
            ShipSystemAPI sys = ship.getSystem() != null ? ship.getSystem() : ship.getPhaseCloak();
            if (sys != null) {
                ShipSystemAPI.SystemState[] states = ShipSystemAPI.SystemState.values();
                if (s.systemStateOrdinal() >= 0 && s.systemStateOrdinal() < states.length) {
                    try {
                        sys.forceState(states[s.systemStateOrdinal()], s.systemEffect());
                    } catch (RuntimeException ignored) {
                    }
                }
                if (sys.getMaxAmmo() > 0) {
                    sys.setAmmo(Math.max(0, Math.min(sys.getMaxAmmo(), s.systemAmmo())));
                }
                sys.setCooldownRemaining(s.systemCooldown());
            }

            // Turret facing, ammo, disabled state & autofire groups
            if (!ship.isFighter() && s.turretAnglesDeg().length > 0) {
                List<WeaponAPI> weapons = ship.getAllWeapons();
                if (weapons != null) {
                    int n = Math.min(weapons.size(), s.turretAnglesDeg().length);
                    for (int i = 0; i < n; i++) {
                        WeaponAPI w = weapons.get(i);
                        if (w == null) {
                            continue;
                        }
                        w.setCurrAngle(s.turretAnglesDeg()[i]);
                        if (w.usesAmmo() && i < s.weaponAmmo().length && s.weaponAmmo()[i] >= 0) {
                            w.setAmmo(s.weaponAmmo()[i]);
                        }
                        boolean disabled = (s.disabledWeaponMask() & (1 << i)) != 0;
                        if (disabled && !w.isDisabled()) {
                            w.disable();
                        } else if (!disabled && w.isDisabled()) {
                            w.repair();
                        }
                    }
                }
                List<WeaponGroupAPI> groups = ship.getWeaponGroupsCopy();
                if (groups != null) {
                    for (int i = 0; i < Math.min(7, groups.size()); i++) {
                        WeaponGroupAPI g = groups.get(i);
                        if (g == null) {
                            continue;
                        }
                        boolean autoOn = (s.autofireMask() & (1 << i)) != 0;
                        if (autoOn && !g.isAutofiring()) {
                            g.toggleOn();
                        } else if (!autoOn && g.isAutofiring()) {
                            g.toggleOff();
                        }
                    }
                }
            }
        }
    }

    private void advanceInterpolation(float dt, long nowMillis) {
        float clampedDt = Math.max(0.001f, Math.min(0.05f, dt));
        float blend = Math.min(1f, clampedDt * 18f);
        for (Map.Entry<Integer, ShipInterpTarget> e : interpTargets.entrySet()) {
            ShipAPI ship = shipsByNetId.get(e.getKey());
            if (ship == null) {
                continue;
            }
            ShipInterpTarget t = e.getValue();
            float ageSec = Math.max(0f, Math.min(0.15f, (nowMillis - t.receivedAtMillis) / 1000f));
            float targetX = t.x + t.vx * ageSec;
            float targetY = t.y + t.vy * ageSec;

            Vector2f loc = ship.getLocation();
            float dx = targetX - loc.x;
            float dy = targetY - loc.y;
            if (dx * dx + dy * dy > 400f * 400f) {
                // Hard snap on teleport/phase-skim/spawn jump
                loc.set(targetX, targetY);
            } else {
                loc.set(loc.x + dx * blend, loc.y + dy * blend);
            }
            ship.getVelocity().set(t.vx, t.vy);
            ship.setFacing(lerpAngle(ship.getFacing(), t.facing, blend));
            ship.setAngularVelocity(t.angVel);
        }
    }

    private void applyArmorDelta(ArmorDelta delta) {
        ShipAPI ship = shipsByNetId.get(delta.netId());
        if (ship == null) {
            return;
        }
        ArmorGridAPI armor = ship.getArmorGrid();
        if (armor == null) {
            return;
        }
        float[][] grid = armor.getGrid();
        float maxCell = armor.getMaxArmorInCell();
        if (grid == null || maxCell <= 0f) {
            return;
        }
        int gw = grid.length;
        int gh = gw > 0 ? grid[0].length : 0;
        for (ArmorCell c : delta.cells()) {
            if (c.cx() >= 0 && c.cx() < gw && c.cy() >= 0 && c.cy() < gh) {
                armor.setArmorValue(c.cx(), c.cy(), (c.valueByte() / 255f) * maxCell);
            }
        }
    }

    private void applyProjSpawns(CombatEngineAPI engine, ProjectileSpawnBatch batch) {
        for (ProjectileSpawn ps : batch.spawns()) {
            if (projectilesById.containsKey(ps.projId()) || ps.weaponId().isEmpty()) {
                continue;
            }
            try {
                ShipAPI src = shipsByNetId.get(ps.sourceShipNetId());
                Vector2f loc = new Vector2f(ps.x(), ps.y());
                Vector2f vel = new Vector2f(ps.vx(), ps.vy());
                CombatEntityAPI ent = engine.spawnProjectile(src, null, ps.weaponId(), loc, ps.facing(), vel);
                if (ent instanceof DamagingProjectileAPI dp) {
                    dp.setOwner(ps.owner());
                    dp.setDamageAmount(0f);
                    dp.setCollisionClass(CollisionClass.NONE);
                    dp.getVelocity().set(ps.vx(), ps.vy());
                    if (dp instanceof MissileAPI missile) {
                        missile.setMissileAI(DUMMY_MISSILE_AI);
                    }
                    projectilesById.put(ps.projId(), dp);
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Unknown or modded synthetic projectile id — skip gracefully
            }
        }
    }

    private void applyProjHits(CombatEngineAPI engine, ProjectileHitBatch batch) {
        for (ProjectileHit h : batch.hits()) {
            DamagingProjectileAPI dp = projectilesById.remove(h.projId());
            if (dp != null) {
                try {
                    engine.removeEntity(dp);
                } catch (RuntimeException | LinkageError ignored) {
                }
            }
            if (h.hit()) {
                Vector2f pos = new Vector2f(h.x(), h.y());
                Color col = h.shieldHit()
                        ? new Color(110, 215, 255, 220)
                        : damageTypeColor(h.damageTypeOrdinal());
                float size = Math.max(12f, Math.min(90f, 14f + h.damage() * 0.08f));
                try {
                    engine.addHitParticle(pos, new Vector2f(0f, 0f), size, 1.0f, 0.22f, col);
                    if (h.damage() >= 350f) {
                        engine.spawnExplosion(pos, new Vector2f(0f, 0f), col, size * 1.4f, 0.35f);
                    }
                    if (h.damage() >= 25f) {
                        ShipAPI target = shipsByNetId.get(h.targetShipNetId());
                        engine.addFloatingDamageText(pos, h.damage(), col, target, null);
                    }
                } catch (RuntimeException | LinkageError ignored) {
                }
            }
        }
    }

    private void applyMissiles(MissileStateBatch batch) {
        for (MissileSample ms : batch.missiles()) {
            DamagingProjectileAPI dp = projectilesById.get(ms.projId());
            if (dp == null) {
                continue;
            }
            dp.getLocation().set(ms.x(), ms.y());
            dp.getVelocity().set(ms.vx(), ms.vy());
            dp.setFacing(ms.facing());
            if (ms.fizzling() && dp instanceof MissileAPI m && !m.isFizzling()) {
                m.flameOut();
            }
        }
    }

    private boolean applyCombatEvent(CombatEngineAPI engine, CombatEvent ev) {
        switch (ev.kind()) {
            case CoopCombatNetCodec.EVT_SHIP_DEAD, CoopCombatNetCodec.EVT_SHIP_HULK -> {
                ShipAPI ship = shipsByNetId.get(ev.argInt1());
                if (ship != null && ship.isAlive()) {
                    Vector2f pos = new Vector2f(ev.f1(), ev.f2());
                    float radius = Math.max(60f, ev.f3() * 2.2f);
                    try {
                        engine.spawnExplosion(pos, ship.getVelocity(),
                                new Color(255, 165, 80, 240), radius, 0.9f);
                        // Lift immunity briefly and deal lethal damage so Starsector creates the native hulk
                        MutableShipStatsAPI st = ship.getMutableStats();
                        if (st != null) {
                            st.getHullDamageTakenMult().unmodify(PUPPET_IMMUNITY_ID);
                        }
                        engine.applyDamage(ship, pos, ship.getMaxHitpoints() * 10f,
                                DamageType.HIGH_EXPLOSIVE, 0f, true, false, null, false);
                    } catch (RuntimeException | LinkageError ignored) {
                    }
                }
            }
            case CoopCombatNetCodec.EVT_SHIP_RETREATED, CoopCombatNetCodec.EVT_SHIP_REMOVED -> {
                ShipAPI ship = shipsByNetId.remove(ev.argInt1());
                interpTargets.remove(ev.argInt1());
                if (ship != null) {
                    try {
                        engine.removeEntity(ship);
                    } catch (RuntimeException | LinkageError ignored) {
                    }
                }
            }
            case CoopCombatNetCodec.EVT_EMP_ARC -> {
                try {
                    engine.spawnEmpArcVisual(
                            new Vector2f(ev.f1(), ev.f2()), null,
                            new Vector2f(ev.f3(), ev.f4()), null,
                            Math.max(8f, ev.f5()),
                            new Color(100, 180, 255, 220), Color.WHITE);
                } catch (RuntimeException | LinkageError ignored) {
                }
            }
            case CoopCombatNetCodec.EVT_EXPLOSION -> {
                try {
                    engine.spawnExplosion(new Vector2f(ev.f1(), ev.f2()), new Vector2f(0f, 0f),
                            new Color(255, 175, 85, 230), Math.max(40f, ev.f3()), Math.max(0.25f, ev.f4()));
                } catch (RuntimeException | LinkageError ignored) {
                }
            }
            case CoopCombatNetCodec.EVT_PILOT_SWITCH -> {
                session.setPilotedShipNetId(ev.argInt1());
                ShipAPI next = shipsByNetId.get(ev.argInt1());
                if (next != null) {
                    try {
                        engine.setPlayerShipExternal(next);
                        inputCapture.resetForShipSwitch(next);
                    } catch (RuntimeException | LinkageError ignored) {
                    }
                }
            }
            case CoopCombatNetCodec.EVT_PAUSE -> {
                boolean paused = ev.argInt1() != 0;
                session.setRemotePaused(paused, ev.text());
                try {
                    engine.setPaused(paused);
                } catch (RuntimeException | LinkageError ignored) {
                }
            }
            case CoopCombatNetCodec.EVT_COMBAT_END -> {
                forceExit(engine);
                return true;
            }
            default -> {
            }
        }
        return false;
    }

    private void suppressLocalAutonomy() {
        for (ShipAPI ship : shipsByNetId.values()) {
            if (ship != null && ship.isAlive() && !ship.isHulk()) {
                // Prevent client's local weapons from firing duplicate un-synced local shots
                ship.giveCommand(ShipCommand.HOLD_FIRE, null, 0);
            }
        }
    }

    private void renderHudStatus(CombatEngineAPI engine, ShipAPI piloted) {
        try {
            String shipLabel = piloted != null && piloted.getName() != null && !piloted.getName().isBlank()
                    ? piloted.getName() : CoopUiLang.tr("Observer", "Наблюдатель");
            String status = CoopUiLang.tr(
                    "LIVE CO-OP | Ship: " + shipLabel + " | [F8] Switch ship | [Space] Pause",
                    "КООП БОЙ | Корабль: " + shipLabel + " | [F8] Сменить корабль | [Space] Пауза");
            if (engine.isPaused() && !session.pausedByName().isEmpty()) {
                status += CoopUiLang.tr(" (Paused by " + session.pausedByName() + ")",
                        " (Пауза: " + session.pausedByName() + ")");
            }
            engine.maintainStatusForPlayerShip(HUD_STATUS_KEY, null,
                    CoopUiLang.tr("Co-op Live Combat", "Кооперативный бой"),
                    status, false);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private static float lerpAngle(float from, float to, float t) {
        float diff = ((to - from + 540f) % 360f) - 180f;
        return ((from + diff * t) % 360f + 360f) % 360f;
    }

    private static Color damageTypeColor(int ord) {
        DamageType[] vals = DamageType.values();
        if (ord < 0 || ord >= vals.length) {
            return new Color(255, 195, 110, 220);
        }
        return switch (vals[ord]) {
            case KINETIC -> new Color(210, 235, 255, 220);
            case HIGH_EXPLOSIVE -> new Color(255, 155, 70, 230);
            case ENERGY -> new Color(150, 210, 255, 230);
            case FRAGMENTATION -> new Color(255, 210, 120, 210);
            default -> new Color(255, 220, 160, 220);
        };
    }
}
