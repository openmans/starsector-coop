package coop.campaign;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopAmbientSystemSyncTest {

    @Test
    void triggersHostAbyssRollOnlyForSeparatedGuestWithoutNearbyTempSystemAndEnforcesCooldown() {
        AtomicLong clock = new AtomicLong(100_000L);
        List<String> events = new ArrayList<>();
        List<CoopAmbientSystemSync.GuestAbyssProbe> probes = new ArrayList<>();
        CoopAmbientSystemSync sync = new CoopAmbientSystemSync(clock::get, new CoopAmbientSystemSync.HostWorld() {
            @Override
            public List<CoopAmbientSystemSync.GuestAbyssProbe> probeGuestsInAbyss() {
                return probes;
            }

            @Override
            public boolean triggerAbyssEncounterAt(float x, float y) {
                events.add("abyss:" + x + "," + y);
                return true;
            }

            @Override
            public boolean executeSlipsurgeAt(String actorId, float x, float y, float angleDeg, float strength) {
                events.add("surge:" + actorId + ":" + strength);
                return true;
            }
        });

        // Near host -> skipped
        probes.add(new CoopAmbientSystemSync.GuestAbyssProbe("g1", 100f, 200f, 2.5f, true, 5000f, false));
        // Already has temporary system nearby -> skipped
        probes.add(new CoopAmbientSystemSync.GuestAbyssProbe("g2", 20_000f, 0f, 3.0f, true, 20_000f, true));
        // Valid separated guest in deep abyss -> triggered!
        probes.add(new CoopAmbientSystemSync.GuestAbyssProbe("g3", -30_000f, 5_000f, 2.0f, true, 30_000f, false));

        assertEquals(1, sync.tickHostAbyssProbes());
        assertEquals(List.of("abyss:-30000.0,5000.0"), events);

        // Immediately again -> blocked by cooldown
        assertEquals(0, sync.tickHostAbyssProbes());
        clock.addAndGet(CoopAmbientSystemSync.ABYSS_CHECK_COOLDOWN_MILLIS);
        assertEquals(1, sync.tickHostAbyssProbes());
    }

    @Test
    void ambientActionCodecRoundTripsAndRateLimitsSlipsurge() {
        AtomicLong clock = new AtomicLong(50_000L);
        List<String> calls = new ArrayList<>();
        CoopAmbientSystemSync sync = new CoopAmbientSystemSync(clock::get, new CoopAmbientSystemSync.HostWorld() {
            @Override
            public List<CoopAmbientSystemSync.GuestAbyssProbe> probeGuestsInAbyss() {
                return List.of();
            }

            @Override
            public boolean triggerAbyssEncounterAt(float x, float y) {
                return true;
            }

            @Override
            public boolean executeSlipsurgeAt(String actorId, float x, float y, float angleDeg, float strength) {
                calls.add(actorId + "@" + angleDeg);
                return true;
            }
        });

        CoopAmbientSystemSync.AmbientAction orig = new CoopAmbientSystemSync.AmbientAction(
                CoopAmbientSystemSync.ActionKind.SLIPSURGE, "g1", 1200f, -3400f, 45f, 1.3f, "corvus");
        CoopAmbientSystemSync.AmbientAction decoded = CoopAmbientSystemSync.AmbientAction.decode(orig.encode());
        assertEquals(orig, decoded);

        assertTrue(sync.handleHostAction(decoded));
        assertFalse(sync.handleHostAction(decoded));
        clock.addAndGet(CoopAmbientSystemSync.SLIPSURGE_COOLDOWN_MILLIS);
        assertTrue(sync.handleHostAction(decoded));
        assertEquals(2, calls.size());
    }
}
