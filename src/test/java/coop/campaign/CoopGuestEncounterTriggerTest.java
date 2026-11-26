package coop.campaign;

import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.enc.EPEncounterCreator;
import com.fs.starfarer.api.impl.campaign.enc.EncounterManager;
import com.fs.starfarer.api.impl.campaign.enc.EncounterPoint;
import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceAbyssPluginImpl;
import com.fs.starfarer.api.util.TimeoutTracker;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for Phase 26 guest-proximity encounter triggering and multi-guest despawn tracking. */
class CoopGuestEncounterTriggerTest {

    @Test
    void triggersAbyssalEncounterAroundRemoteGuestAndPopulatesAbyssalData() {
        LocationAPI hyper = dummyLocation("hyperspace");
        SectorEntityToken guest = dummyGuest("guest_1", hyper, new Vector2f(35_000f, -20_000f), true);

        List<EncounterPoint> created = new ArrayList<>();
        EPEncounterCreator abyssalCreator = stubCreator("abyssal_test",
                HyperspaceAbyssPluginImpl.EP_TYPE_ABYSSAL, 10f, 30f, 5f, created);

        FakeHostWorld world = new FakeHostWorld(hyper, new Vector2f(0f, 0f), List.of(guest), List.of(abyssalCreator));
        world.abyssalDepth = 0.85f;

        CoopGuestEncounterTrigger trigger = new CoopGuestEncounterTrigger(world, new Random(42L));
        trigger.evaluateGuestsNow();

        assertEquals(1, created.size(), "expected one abyssal encounter created around remote guest");
        assertEquals(1, trigger.triggeredCount());
        EncounterPoint ep = created.get(0);
        assertEquals(HyperspaceAbyssPluginImpl.EP_TYPE_ABYSSAL, ep.type);
        assertTrue(ep.custom instanceof HyperspaceAbyssPluginImpl.AbyssalEPData,
                "abyssal EP must carry native AbyssalEPData");
        HyperspaceAbyssPluginImpl.AbyssalEPData data = (HyperspaceAbyssPluginImpl.AbyssalEPData) ep.custom;
        assertEquals(0.85f, data.depth, 0.001f);
        assertNotNull(data.random);
        assertTrue(world.pointTimeout.contains(ep.id), "triggered point must enter pointTimeout");
        assertTrue(world.creatorTimeout.contains("abyssal_test"), "creator must enter creatorTimeout");

        // Second sweep while creator is still on timeout must not fire again.
        trigger.evaluateGuestsNow();
        assertEquals(1, created.size());
    }

    @Test
    void skipsGuestInsideHostEncounterRingToAvoidDoubleSpawning() {
        LocationAPI hyper = dummyLocation("hyperspace");
        // Host at (0, 0), guest at (1500, 0) — well within maxDist (2000 + 4000 = 6000)
        SectorEntityToken nearGuest = dummyGuest("guest_near", hyper, new Vector2f(1500f, 0f), true);

        List<EncounterPoint> created = new ArrayList<>();
        EPEncounterCreator abyssalCreator = stubCreator("abyssal_test",
                HyperspaceAbyssPluginImpl.EP_TYPE_ABYSSAL, 10f, 30f, 5f, created);

        FakeHostWorld world = new FakeHostWorld(hyper, new Vector2f(0f, 0f), List.of(nearGuest), List.of(abyssalCreator));
        world.abyssalDepth = 1.0f;

        CoopGuestEncounterTrigger trigger = new CoopGuestEncounterTrigger(world, new Random(7L));
        trigger.evaluateGuestsNow();

        assertTrue(created.isEmpty(), "guest near host must not trigger duplicate encounters");
    }

    @Test
    void multiGuestSkipsNearGuestAndTriggersForFarGuestInAbyss() {
        LocationAPI hyper = dummyLocation("hyperspace");
        LocationAPI starSystem = dummyLocation("corvus");
        SectorEntityToken nearGuest = dummyGuest("guest_1", hyper, new Vector2f(500f, 500f), true);
        SectorEntityToken inSystemGuest = dummyGuest("guest_2", starSystem, new Vector2f(50_000f, 50_000f), true);
        SectorEntityToken farAbyssGuest = dummyGuest("guest_3", hyper, new Vector2f(-40_000f, -35_000f), true);

        List<EncounterPoint> created = new ArrayList<>();
        EPEncounterCreator abyssalCreator = stubCreator("abyssal_test",
                HyperspaceAbyssPluginImpl.EP_TYPE_ABYSSAL, 10f, 15f, 3f, created);

        FakeHostWorld world = new FakeHostWorld(hyper, new Vector2f(0f, 0f),
                List.of(nearGuest, inSystemGuest, farAbyssGuest), List.of(abyssalCreator));
        world.abyssalDepth = 0.9f;

        CoopGuestEncounterTrigger trigger = new CoopGuestEncounterTrigger(world, new Random(99L));
        trigger.evaluateGuestsNow();

        assertEquals(1, created.size());
        assertTrue(created.get(0).loc.x < -30_000f, "encounter must spawn around guest_3, got " + created.get(0).loc);
    }

    @Test
    void triggersSlipstreamEncounterWhenGuestInNonAbyssalHyperspace() {
        LocationAPI hyper = dummyLocation("hyperspace");
        SectorEntityToken guest = dummyGuest("guest_stream", hyper, new Vector2f(20_000f, 10_000f), true);

        List<EncounterPoint> created = new ArrayList<>();
        EPEncounterCreator slipCreator = stubCreator("slip_pirate",
                EncounterManager.EP_TYPE_SLIPSTREAM, 10f, 20f, 4f, created);

        FakeHostWorld world = new FakeHostWorld(hyper, new Vector2f(0f, 0f), List.of(guest), List.of(slipCreator));
        world.abyssalDepth = 0f;
        world.slipstreamPoints = List.of(new EncounterPoint("ss_23000_10000", hyper,
                new Vector2f(23_000f, 10_000f), EncounterManager.EP_TYPE_SLIPSTREAM));

        CoopGuestEncounterTrigger trigger = new CoopGuestEncounterTrigger(world, new Random(123L));
        trigger.tickHost(100f); // advances enough game days to elapse the 0.2..0.4 day interval

        assertEquals(1, created.size());
        assertEquals("ss_23000_10000", created.get(0).id);
    }

    @Test
    void pausedWorldDoesNotAdvanceOrTrigger() {
        LocationAPI hyper = dummyLocation("hyperspace");
        SectorEntityToken guest = dummyGuest("guest_1", hyper, new Vector2f(35_000f, -20_000f), true);
        List<EncounterPoint> created = new ArrayList<>();
        EPEncounterCreator creator = stubCreator("abyssal_test",
                HyperspaceAbyssPluginImpl.EP_TYPE_ABYSSAL, 10f, 10f, 2f, created);

        FakeHostWorld world = new FakeHostWorld(hyper, new Vector2f(0f, 0f), List.of(guest), List.of(creator));
        world.abyssalDepth = 1.0f;
        world.paused = true;

        CoopGuestEncounterTrigger trigger = new CoopGuestEncounterTrigger(world, new Random(1L));
        trigger.tickHost(100f);

        assertTrue(created.isEmpty(), "paused host must never trigger encounters");
    }

    private static EPEncounterCreator stubCreator(String id, String expectedType, float freq,
                                                  float pointTimeout, float creatorTimeout,
                                                  List<EncounterPoint> sink) {
        return new EPEncounterCreator() {
            @Override public String getId() { return id; }
            @Override public float getPointTimeoutMin() { return pointTimeout; }
            @Override public float getPointTimeoutMax() { return pointTimeout; }
            @Override public float getCreatorTimeoutMin() { return creatorTimeout; }
            @Override public float getCreatorTimeoutMax() { return creatorTimeout; }
            @Override public float getFrequencyForPoint(EncounterManager m, EncounterPoint p) {
                return expectedType.equals(p.type) ? freq : 0f;
            }
            @Override public void createEncounter(EncounterManager m, EncounterPoint p) {
                sink.add(p);
            }
        };
    }

    private static LocationAPI dummyLocation(String id) {
        return (LocationAPI) Proxy.newProxyInstance(
                LocationAPI.class.getClassLoader(),
                new Class<?>[] { LocationAPI.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    case "isHyperspace" -> "hyperspace".equals(id);
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Location(" + id + ")";
                    default -> null;
                });
    }

    private static SectorEntityToken dummyGuest(String id, LocationAPI loc, Vector2f pos, boolean alive) {
        return (SectorEntityToken) Proxy.newProxyInstance(
                SectorEntityToken.class.getClassLoader(),
                new Class<?>[] { SectorEntityToken.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    case "isAlive" -> alive;
                    case "getContainingLocation" -> loc;
                    case "getLocation", "getLocationInHyperspace" -> pos;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Guest(" + id + ")";
                    default -> null;
                });
    }

    private static final class FakeHostWorld implements CoopGuestEncounterTrigger.HostWorld {
        final LocationAPI hyper;
        final Vector2f hostLoc;
        final List<SectorEntityToken> guests;
        final List<EPEncounterCreator> creators;
        final TimeoutTracker<String> pointTimeout = new TimeoutTracker<>();
        final TimeoutTracker<String> creatorTimeout = new TimeoutTracker<>();
        boolean paused;
        float abyssalDepth;
        List<EncounterPoint> slipstreamPoints = List.of();
        final AtomicInteger calls = new AtomicInteger();

        FakeHostWorld(LocationAPI hyper, Vector2f hostLoc, List<SectorEntityToken> guests,
                      List<EPEncounterCreator> creators) {
            this.hyper = hyper;
            this.hostLoc = hostLoc;
            this.guests = guests;
            this.creators = creators;
        }

        @Override public boolean isPaused() { return paused; }
        @Override public float convertToDays(float dtSeconds) { return dtSeconds / 10f; }
        @Override public LocationAPI hyperspace() { return hyper; }
        @Override public Vector2f hostHyperspaceLocation() { return hostLoc; }
        @Override public float maxSensorRange(LocationAPI location) { return 2000f; }
        @Override public List<SectorEntityToken> guestMirrors() { return guests; }
        @Override public EncounterManager encounterManager() { return null; }
        @Override public List<EPEncounterCreator> creators() { return creators; }
        @Override public TimeoutTracker<String> pointTimeout() { return pointTimeout; }
        @Override public TimeoutTracker<String> creatorTimeout() { return creatorTimeout; }
        @Override public float abyssalDepth(Vector2f loc) { calls.incrementAndGet(); return abyssalDepth; }
        @Override public float abyssalDepthThreshold() { return 0.1f; }
        @Override public float abyssalRegenDistance() { return 2000f; }
        @Override public List<StarSystemAPI> abyssalSystems() { return List.of(); }
        @Override public List<EncounterPoint> generateSlipstreamPoints(LocationAPI hyper, SectorEntityToken guest,
                                                                       float minDist, float maxDist) {
            return slipstreamPoints;
        }
    }
}
