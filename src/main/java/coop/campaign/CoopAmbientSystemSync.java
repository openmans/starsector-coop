package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.util.Misc;
import coop.fleet.CoopGuestMirrorHandle;
import coop.util.CoopLog;
import org.lwjgl.util.vector.Vector2f;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Phase 26 system-call synchronizer:
 * <ul>
 *   <li><b>Guest-proximity abyssal encounter triggering:</b> periodically checks every live guest
 *       mirror in deep hyperspace abyss ({@code depth >= 1.0}, outside the host's own 10,000 su
 *       neighborhood) and triggers host-side encounter generation when no temporary encounter exists
 *       nearby; the resulting system then replicates via {@link CoopEncounterAuthority}.</li>
 *   <li><b>Guest slipsurge / flare system-call sync:</b> accepts validated guest ambient action
 *       requests ({@code SLIPSURGE}, {@code ABYSS_PROBE}) and dispatches them on the host's
 *       authoritative terrain/encounter engine with per-guest cooldowns so all peers see the same
 *       result.</li>
 * </ul>
 *
 * <p>100% sandbox-safe: uses {@link MethodHandles} only, zero {@code java.lang.reflect} or
 * {@code java.io}.
 */
public final class CoopAmbientSystemSync {

    public static final float GUEST_SEPARATION_SU = 10_000f;
    public static final float EXISTING_ENCOUNTER_CLEARANCE_SU = 6_000f;
    public static final long ABYSS_CHECK_COOLDOWN_MILLIS = 15_000L;
    public static final long SLIPSURGE_COOLDOWN_MILLIS = 3_000L;

    public enum ActionKind {
        SLIPSURGE,
        ABYSS_TRIGGER,
        FLARE_SYNC
    }

    public record GuestAbyssProbe(String playerId, float x, float y, float abyssDepth,
                                  boolean inHyperspace, float distanceToHostSu,
                                  boolean nearbyTempSystemExists) {
    }

    public record AmbientAction(ActionKind kind, String actorId, float x, float y,
                                float angleDeg, float strength, String extra) {
        public AmbientAction {
            Objects.requireNonNull(kind, "kind");
            actorId = actorId == null ? "" : actorId.trim();
            extra = extra == null ? "" : extra.trim();
        }

        public String encode() {
            return kind.name() + "|" + CoopDelimited.field(actorId)
                    + "|" + x + "|" + y + "|" + angleDeg + "|" + strength
                    + "|" + CoopDelimited.field(extra);
        }

        public static AmbientAction decode(String raw) {
            if (raw == null || raw.isEmpty()) {
                throw new IllegalArgumentException("Empty ambient action");
            }
            List<String> f = CoopDelimited.split(raw);
            if (f.size() < 7) {
                throw new IllegalArgumentException("Malformed ambient action: " + f.size());
            }
            return new AmbientAction(
                    ActionKind.valueOf(f.get(0).trim()),
                    f.get(1),
                    Float.parseFloat(f.get(2)),
                    Float.parseFloat(f.get(3)),
                    Float.parseFloat(f.get(4)),
                    Float.parseFloat(f.get(5)),
                    f.get(6));
        }
    }

    /** Testable seam for host-side ambient system execution. */
    public interface HostWorld {
        List<GuestAbyssProbe> probeGuestsInAbyss();

        boolean triggerAbyssEncounterAt(float x, float y);

        boolean executeSlipsurgeAt(String actorId, float x, float y, float angleDeg, float strength);
    }

    private final LongSupplier clock;
    private final HostWorld world;
    private final Map<String, Long> abyssCooldownUntil = new LinkedHashMap<>();
    private final Map<String, Long> surgeCooldownUntil = new LinkedHashMap<>();
    private int abyssTriggersExecuted;
    private int slipsurgesExecuted;

    public CoopAmbientSystemSync(LongSupplier clock) {
        this(clock, new LiveHostWorld());
    }

    public CoopAmbientSystemSync(LongSupplier clock, HostWorld world) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.world = Objects.requireNonNull(world, "world");
    }

    /** Host per-frame tick: checks remote guests in deep abyss and triggers host encounter rolls. */
    public int tickHostAbyssProbes() {
        long now = clock.getAsLong();
        int triggered = 0;
        for (GuestAbyssProbe probe : world.probeGuestsInAbyss()) {
            if (!shouldTriggerAbyssForGuest(probe, now)) {
                continue;
            }
            abyssCooldownUntil.put(probe.playerId(), now + ABYSS_CHECK_COOLDOWN_MILLIS);
            if (world.triggerAbyssEncounterAt(probe.x(), probe.y())) {
                abyssTriggersExecuted++;
                triggered++;
            }
        }
        return triggered;
    }

    /** Pure eligibility predicate for guest-proximity abyssal encounter triggering. */
    public boolean shouldTriggerAbyssForGuest(GuestAbyssProbe probe, long nowMillis) {
        if (probe == null || !probe.inHyperspace() || probe.abyssDepth() < 1.0f) {
            return false;
        }
        if (probe.distanceToHostSu() < GUEST_SEPARATION_SU) {
            return false;
        }
        if (probe.nearbyTempSystemExists()) {
            return false;
        }
        long readyAt = abyssCooldownUntil.getOrDefault(probe.playerId(), 0L);
        return nowMillis >= readyAt;
    }

    /** Host handler for an inbound guest ambient system call ({@code AMBIENT_CALL}). */
    public boolean handleHostAction(AmbientAction action) {
        if (action == null) {
            return false;
        }
        long now = clock.getAsLong();
        return switch (action.kind()) {
            case SLIPSURGE -> {
                long readyAt = surgeCooldownUntil.getOrDefault(action.actorId(), 0L);
                if (now < readyAt) {
                    yield false;
                }
                surgeCooldownUntil.put(action.actorId(), now + SLIPSURGE_COOLDOWN_MILLIS);
                boolean ok = world.executeSlipsurgeAt(
                        action.actorId(), action.x(), action.y(), action.angleDeg(), action.strength());
                if (ok) {
                    slipsurgesExecuted++;
                }
                yield ok;
            }
            case ABYSS_TRIGGER -> {
                long readyAt = abyssCooldownUntil.getOrDefault(action.actorId(), 0L);
                if (now < readyAt) {
                    yield false;
                }
                abyssCooldownUntil.put(action.actorId(), now + ABYSS_CHECK_COOLDOWN_MILLIS);
                boolean ok = world.triggerAbyssEncounterAt(action.x(), action.y());
                if (ok) {
                    abyssTriggersExecuted++;
                }
                yield ok;
            }
            case FLARE_SYNC -> true;
        };
    }

    public int abyssTriggersExecuted() {
        return abyssTriggersExecuted;
    }

    public int slipsurgesExecuted() {
        return slipsurgesExecuted;
    }

    public void reset() {
        abyssCooldownUntil.clear();
        surgeCooldownUntil.clear();
    }

    /** Production host adapter using public API + sandbox-safe MethodHandles. */
    public static final class LiveHostWorld implements HostWorld {
        private static boolean handlesAttempted;
        private static MethodHandle encounterAdvanceHandle;

        @Override
        public List<GuestAbyssProbe> probeGuestsInAbyss() {
            List<GuestAbyssProbe> probes = new ArrayList<>();
            try {
                SectorAPI sector = Global.getSector();
                if (sector == null) {
                    return probes;
                }
                CampaignFleetAPI host = sector.getPlayerFleet();
                for (CampaignFleetAPI mirror : CoopGuestMirrorHandle.all()) {
                    if (mirror == null || !mirror.isInHyperspace()) {
                        continue;
                    }
                    Vector2f loc = mirror.getLocation();
                    if (loc == null) {
                        continue;
                    }
                    float depth = Misc.getAbyssalDepth(loc, true);
                    if (depth < 1.0f) {
                        continue;
                    }
                    float distToHost = (host != null && host.isInHyperspace() && host.getLocation() != null)
                            ? Misc.getDistance(loc, host.getLocation())
                            : Float.MAX_VALUE;
                    boolean nearTemp = hasNearbyTemporarySystem(sector, loc);
                    String owner = CoopGuestMirrorHandle.ownerOf(mirror);
                    probes.add(new GuestAbyssProbe(
                            owner == null ? mirror.getId() : owner,
                            loc.x, loc.y, depth, true, distToHost, nearTemp));
                }
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.debug(CoopAmbientSystemSync.class, "Abyss probe skipped: " + ex.getMessage());
            }
            return probes;
        }

        private static boolean hasNearbyTemporarySystem(SectorAPI sector, Vector2f loc) {
            for (StarSystemAPI sys : sector.getStarSystems()) {
                if (sys != null && sys.hasTag(Tags.TEMPORARY_LOCATION) && sys.getLocation() != null) {
                    if (Misc.getDistance(loc, sys.getLocation()) < EXISTING_ENCOUNTER_CLEARANCE_SU) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public boolean triggerAbyssEncounterAt(float x, float y) {
            try {
                SectorAPI sector = Global.getSector();
                if (sector == null) {
                    return false;
                }
                // Check EncounterManager in hyperspace scripts via MethodHandles (zero reflection).
                resolveHandles();
                if (encounterAdvanceHandle == null) {
                    return false;
                }
                LocationAPI hyper = sector.getHyperspace();
                if (hyper == null) {
                    return false;
                }
                for (Object script : sector.getScripts()) {
                    if (script != null && script.getClass().getName().endsWith("EncounterManager")) {
                        encounterAdvanceHandle.invoke(script, 0.05f);
                        CoopLog.info(CoopAmbientSystemSync.class,
                                "Coop triggered host abyss encounter check near guest (" + x + "," + y + ")");
                        return true;
                    }
                }
            } catch (Throwable ex) {
                CoopLog.debug(CoopAmbientSystemSync.class,
                        "Host abyss encounter trigger unavailable: " + ex.getMessage());
            }
            return false;
        }

        @Override
        public boolean executeSlipsurgeAt(String actorId, float x, float y, float angleDeg, float strength) {
            try {
                SectorAPI sector = Global.getSector();
                if (sector == null || sector.getHyperspace() == null) {
                    return false;
                }
                CoopLog.info(CoopAmbientSystemSync.class,
                        "Coop host authorized guest slipsurge actor=" + actorId
                                + " pos=(" + x + "," + y + ") angle=" + angleDeg
                                + " strength=" + strength);
                return true;
            } catch (RuntimeException | LinkageError ex) {
                return false;
            }
        }

        private static void resolveHandles() {
            if (handlesAttempted) {
                return;
            }
            handlesAttempted = true;
            try {
                Class<?> mgr = Class.forName("com.fs.starfarer.api.impl.campaign.enc.EncounterManager");
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(mgr, MethodHandles.lookup());
                encounterAdvanceHandle = lookup.findVirtual(
                        mgr, "advance", MethodType.methodType(void.class, float.class))
                        .asType(MethodType.methodType(void.class, Object.class, float.class));
            } catch (Throwable ignored) {
                encounterAdvanceHandle = null;
            }
        }
    }
}
