package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignTerrainAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.enc.EPEncounterCreator;
import com.fs.starfarer.api.impl.campaign.enc.EncounterManager;
import com.fs.starfarer.api.impl.campaign.enc.EncounterPoint;
import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceAbyssPluginImpl;
import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin;
import com.fs.starfarer.api.impl.campaign.velfield.SlipstreamTerrainPlugin2;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.TimeoutTracker;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import coop.presence.CoopPresenceRegistry;
import coop.util.CoopLog;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Phase 26 stretch: host-side guest-proximity encounter triggering.
 *
 * <p>Vanilla {@link EncounterManager} only generates and evaluates {@link EncounterPoint}s around
 * {@code Global.getSector().getPlayerFleet()} (the host). When a guest travels away from the host
 * in hyperspace (especially into Abyssal Hyperspace), ambient abyssal systems, free-standing
 * abyssal lights, and slipstream encounters would otherwise never fire around them.
 *
 * <p>This transient host helper runs alongside {@link EncounterManager} without replacing it in the
 * save file. For every live guest mirror in hyperspace that is outside the host's own encounter
 * ring, it synthesizes abyssal and slipstream {@link EncounterPoint}s using the same ring geometry,
 * depth threshold, and {@link EncounterManager#CREATORS} weighted picker as vanilla, sharing
 * {@link EncounterManager}'s point and creator timeouts so encounters never double-fire. Everything
 * created on the host is automatically picked up by {@link CoopEncounterWorld},
 * {@link CoopLightWorld}, and {@link coop.fleet.CoopNpcFleetReplicator} and replicated to all peers.
 */
public final class CoopGuestEncounterTrigger {

    static final float ABYSSAL_RING_RADIUS = 3000f;
    static final float ABYSSAL_POINT_JITTER = 1000f;
    static final float ABYSSAL_ANGLE_STEP_DEG = 30f;
    static final float SLIPSTREAM_CONE_HALF_ANGLE_DEG = 90f;
    static final float DEFAULT_INTERVAL_MIN_DAYS = 0.2f;
    static final float DEFAULT_INTERVAL_MAX_DAYS = 0.4f;

    /** Test seam decoupling pure encounter selection/trigger logic from Starsector globals. */
    interface HostWorld {
        boolean isPaused();
        float convertToDays(float dtSeconds);
        LocationAPI hyperspace();
        Vector2f hostHyperspaceLocation();
        float maxSensorRange(LocationAPI location);
        List<SectorEntityToken> guestMirrors();
        EncounterManager encounterManager();
        List<EPEncounterCreator> creators();
        TimeoutTracker<String> pointTimeout();
        TimeoutTracker<String> creatorTimeout();
        float abyssalDepth(Vector2f loc);
        float abyssalDepthThreshold();
        float abyssalRegenDistance();
        List<StarSystemAPI> abyssalSystems();
        List<EncounterPoint> generateSlipstreamPoints(LocationAPI hyper, SectorEntityToken guest,
                                                      float minDist, float maxDist);
    }

    private static final class GuestCache {
        Vector2f generatedAt;
        List<EncounterPoint> abyssalPoints = List.of();
    }

    private final HostWorld world;
    private final Random random;
    private final IntervalUtil interval;
    private final Map<String, GuestCache> caches = new HashMap<>();
    private int triggeredCount;

    public CoopGuestEncounterTrigger() {
        this(new LiveHostWorld(), new Random());
    }

    CoopGuestEncounterTrigger(HostWorld world, Random random) {
        this.world = Objects.requireNonNull(world, "world");
        this.random = Objects.requireNonNull(random, "random");
        this.interval = new IntervalUtil(DEFAULT_INTERVAL_MIN_DAYS, DEFAULT_INTERVAL_MAX_DAYS);
        this.interval.setRandom(this.random);
    }

    /** Advances the host trigger on active campaign frames. Never touches guest or solo saves. */
    public void tickHost(float dtSeconds) {
        if (dtSeconds <= 0f || world.isPaused()) return;
        float days = world.convertToDays(dtSeconds);
        if (!Float.isFinite(days) || days <= 0f) return;

        interval.advance(days);
        if (!interval.intervalElapsed()) return;

        evaluateGuestsNow();
    }

    /** Runs one evaluation sweep across all live guest mirrors in hyperspace. */
    void evaluateGuestsNow() {
        LocationAPI hyper = world.hyperspace();
        if (hyper == null) return;
        List<SectorEntityToken> guests = world.guestMirrors();
        if (guests == null || guests.isEmpty()) return;

        float minDist = Math.max(0f, world.maxSensorRange(hyper));
        float maxDist = minDist + EncounterManager.MAX_EP_DIST_ADD;
        Vector2f hostLoc = world.hostHyperspaceLocation();
        TimeoutTracker<String> pointTimeout = world.pointTimeout();
        TimeoutTracker<String> creatorTimeout = world.creatorTimeout();
        if (pointTimeout == null || creatorTimeout == null) return;

        List<EPEncounterCreator> creators = world.creators();
        if (creators == null || creators.isEmpty()) return;

        EncounterManager manager = world.encounterManager();
        for (int idx = 0; idx < guests.size(); idx++) {
            SectorEntityToken guest = guests.get(idx);
            if (guest == null || !guest.isAlive()) continue;
            if (guest.getContainingLocation() != hyper) continue;
            Vector2f guestLoc = guest.getLocation();
            if (guestLoc == null || !Float.isFinite(guestLoc.x) || !Float.isFinite(guestLoc.y)) continue;

            // If the guest is already inside the host's own encounter radius, vanilla EncounterManager
            // handles this region — skip to avoid double encounter density when flying together.
            if (hostLoc != null && Misc.getDistance(hostLoc, guestLoc) <= maxDist) {
                continue;
            }

            String cacheKey = guest.getId() != null && !guest.getId().isEmpty()
                    ? guest.getId() : "guest_" + idx;
            List<EncounterPoint> candidates = collectPointsForGuest(hyper, guest, guestLoc, cacheKey, minDist, maxDist);
            EncounterPoint nearest = pickNearestEligible(candidates, guestLoc, minDist, maxDist, pointTimeout);
            if (nearest == null) continue;

            EPEncounterCreator picked = pickCreator(creators, creatorTimeout, manager, nearest);
            if (picked == null) continue;

            try {
                picked.createEncounter(manager, nearest);
                applyTimeouts(picked, nearest, pointTimeout, creatorTimeout);
                triggeredCount++;
            } catch (RuntimeException ex) {
                CoopLog.warn(CoopGuestEncounterTrigger.class,
                        "Guest-proximity encounter creator failed for " + picked.getId()
                                + " at " + nearest.id, ex);
            }
        }
    }

    private List<EncounterPoint> collectPointsForGuest(LocationAPI hyper, SectorEntityToken guest,
                                                       Vector2f guestLoc, String cacheKey,
                                                       float minDist, float maxDist) {
        List<EncounterPoint> all = new ArrayList<>();
        all.addAll(getOrGenerateAbyssalPoints(hyper, guestLoc, cacheKey));
        List<EncounterPoint> slipstream = world.generateSlipstreamPoints(hyper, guest, minDist, maxDist);
        if (slipstream != null && !slipstream.isEmpty()) {
            all.addAll(slipstream);
        }
        return all;
    }

    private List<EncounterPoint> getOrGenerateAbyssalPoints(LocationAPI hyper, Vector2f guestLoc, String cacheKey) {
        GuestCache cache = caches.computeIfAbsent(cacheKey, k -> new GuestCache());
        float regenDist = world.abyssalRegenDistance();
        boolean needsRegen = cache.generatedAt == null
                || Misc.getDistance(guestLoc, cache.generatedAt) > regenDist;
        if (!needsRegen) {
            return cache.abyssalPoints;
        }

        cache.generatedAt = new Vector2f(guestLoc);
        List<EncounterPoint> generated = new ArrayList<>();
        List<StarSystemAPI> abyssalSystems = world.abyssalSystems();
        float depthThreshold = world.abyssalDepthThreshold();
        float startAngle = random.nextFloat() * 360f;

        for (float angle = startAngle; angle < startAngle + 360f; angle += ABYSSAL_ANGLE_STEP_DEG) {
            Vector2f dir = Misc.getUnitVectorAtDegreeAngle(angle);
            dir.scale(ABYSSAL_RING_RADIUS);
            Vector2f point = Vector2f.add(guestLoc, dir, new Vector2f());
            point = Misc.getPointWithinRadius(point, ABYSSAL_POINT_JITTER, random);
            float depth = world.abyssalDepth(point);
            if (depth < depthThreshold) continue;

            String id = "abyssal_" + (int) (point.x / 1000f) + "_" + (int) (point.y / 1000f);
            EncounterPoint ep = new EncounterPoint(id, hyper, point, HyperspaceAbyssPluginImpl.EP_TYPE_ABYSSAL);
            HyperspaceAbyssPluginImpl.AbyssalEPData data = new HyperspaceAbyssPluginImpl.AbyssalEPData();
            data.depth = depth;
            data.random = Misc.getRandom(random.nextLong(), 7);
            float nearestDist = Float.MAX_VALUE;
            StarSystemAPI nearestSys = null;
            if (abyssalSystems != null) {
                for (StarSystemAPI sys : abyssalSystems) {
                    if (sys == null || sys.getLocation() == null) continue;
                    float dist = Misc.getDistance(point, sys.getLocation());
                    if (dist < nearestDist) {
                        nearestDist = dist;
                        nearestSys = sys;
                    }
                }
            }
            data.nearest = nearestSys;
            data.distToNearest = nearestDist;
            ep.custom = data;
            generated.add(ep);
        }
        cache.abyssalPoints = generated;
        return generated;
    }

    static EncounterPoint pickNearestEligible(List<EncounterPoint> candidates, Vector2f guestLoc,
                                              float minDist, float maxDist,
                                              TimeoutTracker<String> pointTimeout) {
        EncounterPoint best = null;
        float bestDist = Float.MAX_VALUE;
        for (EncounterPoint ep : candidates) {
            if (ep == null || ep.id == null || ep.loc == null) continue;
            if (pointTimeout.contains(ep.id)) continue;
            float dist = Misc.getDistance(guestLoc, ep.loc);
            if (dist < minDist || dist > maxDist) continue;
            if (dist < bestDist) {
                bestDist = dist;
                best = ep;
            }
        }
        return best;
    }

    private EPEncounterCreator pickCreator(List<EPEncounterCreator> creators,
                                           TimeoutTracker<String> creatorTimeout,
                                           EncounterManager manager,
                                           EncounterPoint ep) {
        WeightedRandomPicker<EPEncounterCreator> picker = new WeightedRandomPicker<>(random);
        for (EPEncounterCreator creator : creators) {
            if (creator == null) continue;
            if (creatorTimeout.contains(creator.getId())) continue;
            float freq = creator.getFrequencyForPoint(manager, ep);
            if (freq > 0f) {
                picker.add(creator, freq);
            }
        }
        return picker.pick();
    }

    private void applyTimeouts(EPEncounterCreator creator, EncounterPoint ep,
                               TimeoutTracker<String> pointTimeout,
                               TimeoutTracker<String> creatorTimeout) {
        float ptMin = creator.getPointTimeoutMin();
        float ptMax = creator.getPointTimeoutMax();
        float pt = ptMin + Math.max(0f, ptMax - ptMin) * random.nextFloat();
        if (pt > 0f) {
            pointTimeout.add(ep.id, pt);
        }
        float crMin = creator.getCreatorTimeoutMin();
        float crMax = creator.getCreatorTimeoutMax();
        float cr = crMin + Math.max(0f, crMax - crMin) * random.nextFloat();
        if (cr > 0f) {
            creatorTimeout.add(creator.getId(), cr);
        }
    }

    public void reset() {
        caches.clear();
        triggeredCount = 0;
    }

    int triggeredCount() {
        return triggeredCount;
    }

    private static final class LiveHostWorld implements HostWorld {
        @Override public boolean isPaused() {
            SectorAPI sector = Global.getSector();
            return sector == null || sector.isPaused();
        }

        @Override public float convertToDays(float dtSeconds) {
            SectorAPI sector = Global.getSector();
            CampaignClockAPI clock = sector == null ? null : sector.getClock();
            return clock == null ? 0f : clock.convertToDays(dtSeconds);
        }

        @Override public LocationAPI hyperspace() {
            SectorAPI sector = Global.getSector();
            return sector == null ? null : sector.getHyperspace();
        }

        @Override public Vector2f hostHyperspaceLocation() {
            SectorAPI sector = Global.getSector();
            CampaignFleetAPI player = sector == null ? null : sector.getPlayerFleet();
            return player == null ? null : player.getLocationInHyperspace();
        }

        @Override public float maxSensorRange(LocationAPI location) {
            return Global.getSettings().getMaxSensorRange(location);
        }

        @Override public List<SectorEntityToken> guestMirrors() {
            List<SectorEntityToken> all = CoopPresenceRegistry.getAll();
            if (!all.isEmpty()) return all;
            SectorEntityToken single = CoopPresenceRegistry.get();
            return single == null ? List.of() : List.of(single);
        }

        @Override public EncounterManager encounterManager() {
            return EncounterManager.getInstance();
        }

        @Override public List<EPEncounterCreator> creators() {
            return EncounterManager.CREATORS;
        }

        @Override public TimeoutTracker<String> pointTimeout() {
            EncounterManager mgr = EncounterManager.getInstance();
            return mgr == null ? null : mgr.getPointTimeout();
        }

        @Override public TimeoutTracker<String> creatorTimeout() {
            EncounterManager mgr = EncounterManager.getInstance();
            return mgr == null ? null : mgr.getCreatorTimeout();
        }

        private static HyperspaceAbyssPluginImpl abyssPlugin() {
            HyperspaceTerrainPlugin hyper = Misc.getHyperspaceTerrainPlugin();
            if (hyper != null && hyper.getAbyssPlugin() instanceof HyperspaceAbyssPluginImpl impl) {
                return impl;
            }
            return null;
        }

        @Override public float abyssalDepth(Vector2f loc) {
            HyperspaceAbyssPluginImpl plugin = abyssPlugin();
            if (plugin != null) return plugin.getAbyssalDepth(loc, true);
            return Misc.getAbyssalDepth(loc);
        }

        @Override public float abyssalDepthThreshold() {
            return HyperspaceAbyssPluginImpl.DEPTH_THRESHOLD_FOR_ENCOUNTER;
        }

        @Override public float abyssalRegenDistance() {
            return HyperspaceAbyssPluginImpl.PLAYER_DIST_TRAVELLED_TO_REGEN_EPS;
        }

        @Override public List<StarSystemAPI> abyssalSystems() {
            HyperspaceAbyssPluginImpl plugin = abyssPlugin();
            return plugin == null ? List.of() : plugin.getAbyssalSystems();
        }

        @Override public List<EncounterPoint> generateSlipstreamPoints(LocationAPI hyper, SectorEntityToken guest,
                                                                       float minDist, float maxDist) {
            List<EncounterPoint> out = new ArrayList<>();
            Vector2f guestLoc = guest.getLocation();
            Vector2f vel = guest.getVelocity();
            if (guestLoc == null || vel == null || vel.lengthSquared() < 1f) return out;
            float moveDir = Misc.getAngleInDegrees(vel);

            for (CampaignTerrainAPI terrain : hyper.getTerrainCopy()) {
                if (!(terrain.getPlugin() instanceof SlipstreamTerrainPlugin2 plugin)) continue;
                if (plugin.isDespawning()) continue;
                List<SlipstreamTerrainPlugin2.SlipstreamSegment> segs = plugin.getSegmentsNear(guestLoc, maxDist);
                if (segs == null) continue;
                for (SlipstreamTerrainPlugin2.SlipstreamSegment seg : segs) {
                    if (seg == null || seg.loc == null) continue;
                    float dist = Misc.getDistance(guestLoc, seg.loc);
                    if (dist < minDist || dist > maxDist) continue;
                    float toSeg = Misc.getAngleInDegrees(guestLoc, seg.loc);
                    if (Misc.getAngleDiff(moveDir, toSeg) < SLIPSTREAM_CONE_HALF_ANGLE_DEG) {
                        String id = terrain.getId() + "_" + Math.round(seg.loc.x) + "_" + Math.round(seg.loc.y);
                        out.add(new EncounterPoint(id, hyper, new Vector2f(seg.loc), EncounterManager.EP_TYPE_SLIPSTREAM));
                    }
                }
            }
            return out;
        }
    }
}
