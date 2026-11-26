package coop.campaign;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end and edge-case tests for {@link CoopStorySyncAuthority} and
 * {@link CoopStorySyncFramework}'s live {@code STORY_STATE} pipeline.
 */
class CoopStorySyncAuthorityTest {

    private static CoopStorySyncFramework.FlagMemory mapMemory(Map<String, Object> map) {
        return new CoopStorySyncFramework.FlagMemory() {
            @Override
            public Map<String, Object> entries() {
                return map;
            }

            @Override
            public void set(String key, Object value) {
                map.put(key, value);
            }

            @Override
            public void unset(String key) {
                map.remove(key);
            }
        };
    }

    @Test
    void hostSendsEmptyBaselineOnceThenStaysSilentUntilChangeOrRearm() {
        AtomicLong clock = new AtomicLong(10_000L);
        Map<String, Object> hostMap = new LinkedHashMap<>();
        Map<String, Object> guestMap = new LinkedHashMap<>();
        guestMap.put("$gaStaleFlag", true);
        guestMap.put("$localKeep", "mine");

        List<String> sent = new ArrayList<>();
        CoopStorySyncAuthority host = new CoopStorySyncAuthority(
                clock::get, () -> mapMemory(hostMap), () -> mapMemory(hostMap), sent::add);
        CoopStorySyncAuthority guest = new CoopStorySyncAuthority(
                clock::get, () -> mapMemory(guestMap), () -> mapMemory(guestMap), body -> {});

        // First tick sends an empty baseline so stale guest flags are cleared.
        assertTrue(host.tickHost());
        assertEquals(1, sent.size());
        assertEquals("", sent.get(0));

        int writes = guest.applyGuestBody(sent.get(0));
        assertEquals(1, writes);
        assertFalse(guestMap.containsKey("$gaStaleFlag"), "empty baseline must clear stale tracked flags");
        assertEquals("mine", guestMap.get("$localKeep"), "untracked local keys must stay intact");

        // Within poll interval: throttled.
        clock.addAndGet(CoopStorySyncAuthority.POLL_MILLIS - 1);
        assertFalse(host.tickHost());
        assertEquals(1, sent.size());

        // Past poll interval with no change: zero chatter.
        clock.addAndGet(100);
        assertFalse(host.tickHost());
        assertEquals(1, sent.size());

        // Host mutates a story flag: next poll sends.
        hostMap.put("$gaIntroCompleted", true);
        hostMap.put("$sdUsurpersStage", "macario_meeting");
        hostMap.put("$gaTimestamp", 9_876_543_210L);
        clock.addAndGet(CoopStorySyncAuthority.POLL_MILLIS);
        assertTrue(host.tickHost());
        assertEquals(2, sent.size());

        assertEquals(3, guest.applyGuestBody(sent.get(1)));
        assertEquals(true, guestMap.get("$gaIntroCompleted"));
        assertEquals("macario_meeting", guestMap.get("$sdUsurpersStage"));
        assertEquals(9_876_543_210L, guestMap.get("$gaTimestamp"));

        // Re-applying identical payload is a no-op (0 writes).
        assertEquals(0, guest.applyGuestBody(sent.get(1)));

        // Rearm (reconnect or mid-session join) forces an immediate resend without waiting for poll interval.
        host.rearmBroadcast();
        assertTrue(host.tickHost());
        assertEquals(3, sent.size());
        assertEquals(sent.get(1), sent.get(2));

        // Host unsets one flag; guest clears it on next poll.
        hostMap.remove("$sdUsurpersStage");
        clock.addAndGet(CoopStorySyncAuthority.POLL_MILLIS);
        assertTrue(host.tickHost());
        assertEquals(4, sent.size());
        assertEquals(1, guest.applyGuestBody(sent.get(3)));
        assertFalse(guestMap.containsKey("$sdUsurpersStage"));
        assertEquals(true, guestMap.get("$gaIntroCompleted"));

        // Dispose resets state so a new session sends a fresh baseline immediately.
        host.dispose();
        assertTrue(host.tickHost());
        assertEquals(5, sent.size());
    }

    @Test
    void nonPrimitiveEngineObjectsRefHandlesAndGatesActiveAreNeverSyncedOrClobbered() {
        Object hostMissionHandle = new Object();
        Object guestMissionHandle = new Object();
        Object arbitraryEngineObj = new Object();

        Map<String, Object> hostMap = new LinkedHashMap<>();
        hostMap.put("$gaATG_ref", hostMissionHandle);
        hostMap.put("$sdtu_ref", "even-string-ref-is-excluded");
        hostMap.put("$gaSomeLiveObject", arbitraryEngineObj);
        hostMap.put("$gatesActive", true);
        hostMap.put("$gaBadFloat", Float.NaN);
        hostMap.put("$gaInfDouble", Double.POSITIVE_INFINITY);
        hostMap.put("$gaATG_completed", true);
        hostMap.put("$sdse_achillesWon", true);
        hostMap.put("$galatiaNote", "pipe|slash\\line1\nline2");

        Map<String, Object> guestMap = new LinkedHashMap<>();
        guestMap.put("$gaATG_ref", guestMissionHandle);
        guestMap.put("$gaSomeLiveObject", guestMissionHandle);
        guestMap.put("$gatesActive", false);

        Map<String, CoopStorySyncFramework.StoryFlag> captured =
                CoopStorySyncFramework.capture(mapMemory(hostMap));
        assertEquals(3, captured.size(), "only safe scalar story flags should be captured: " + captured.keySet());
        assertTrue(captured.containsKey("$gaATG_completed"));
        assertTrue(captured.containsKey("$sdse_achillesWon"));
        assertTrue(captured.containsKey("$galatiaNote"));
        assertFalse(captured.containsKey("$gaATG_ref"));
        assertFalse(captured.containsKey("$sdtu_ref"));
        assertFalse(captured.containsKey("$gaSomeLiveObject"));
        assertFalse(captured.containsKey("$gatesActive"));

        String wire = CoopStorySyncFramework.encode(captured);
        Map<String, CoopStorySyncFramework.StoryFlag> decoded = CoopStorySyncFramework.decode(wire);
        assertEquals(captured, decoded);

        int writes = CoopStorySyncFramework.applyAuthoritative(decoded, mapMemory(guestMap));
        assertEquals(3, writes);
        assertEquals(true, guestMap.get("$gaATG_completed"));
        assertEquals(true, guestMap.get("$sdse_achillesWon"));
        assertEquals("pipe|slash\\line1\nline2", guestMap.get("$galatiaNote"));
        assertSame(guestMissionHandle, guestMap.get("$gaATG_ref"), "_ref handles must remain untouched");
        assertSame(guestMissionHandle, guestMap.get("$gaSomeLiveObject"), "non-primitive objects must remain untouched");
        assertEquals(false, guestMap.get("$gatesActive"), "$gatesActive is owned by GATE_ACTIVATED");
    }

    @Test
    void decodeSkipsMalformedNumericUnknownKindAndUntrackedKeys() {
        String corruptWire = String.join("\n",
                "$gaValidBool|BOOL|true",
                "$gaBadInt|INT|not-a-number",
                "$gaBadLong|LONG|99999999999999999999999999",
                "$gaBadFloat|FLOAT|NaN",
                "$gaUnknownKind|MAGIC|123",
                "$unrelatedKey|BOOL|true",
                "$gatesActive|BOOL|true",
                "$gaATG_ref|STRING|injected",
                "only_two|fields",
                "$sdValidLong|LONG|42000000000");

        Map<String, CoopStorySyncFramework.StoryFlag> decoded = CoopStorySyncFramework.decode(corruptWire);
        assertEquals(2, decoded.size(), "only valid tracked scalar flags should survive decode: " + decoded);
        assertEquals(true, decoded.get("$gaValidBool").toJavaValue());
        assertEquals(42_000_000_000L, decoded.get("$sdValidLong").toJavaValue());
    }

    @Test
    void multiGuestMidSessionJoinReceivesFullStoryState() {
        AtomicLong clock = new AtomicLong(5_000L);
        Map<String, Object> hostMap = new LinkedHashMap<>();
        Map<String, Object> guest1Map = new LinkedHashMap<>();
        Map<String, Object> guest2Map = new LinkedHashMap<>();
        List<Map<String, Object>> connectedGuests = new ArrayList<>();
        connectedGuests.add(guest1Map);

        CoopStorySyncAuthority host = new CoopStorySyncAuthority(
                clock::get,
                () -> mapMemory(hostMap),
                () -> mapMemory(hostMap),
                body -> {
                    for (Map<String, Object> g : connectedGuests) {
                        new CoopStorySyncAuthority(clock::get, () -> mapMemory(g), () -> mapMemory(g), b -> {})
                                .applyGuestBody(body);
                    }
                });

        hostMap.put("$gaKA_completed", true);
        hostMap.put("$sdtu_completed", true);
        hostMap.put("$lionsGuardSuspicion", 2.5f);

        assertTrue(host.tickHost());
        assertEquals(true, guest1Map.get("$gaKA_completed"));
        assertEquals(true, guest1Map.get("$sdtu_completed"));
        assertEquals(2.5f, guest1Map.get("$lionsGuardSuspicion"));
        assertTrue(guest2Map.isEmpty());

        // Second guest joins mid-session -> forceFullRebroadcast calls rearmBroadcast().
        connectedGuests.add(guest2Map);
        host.rearmBroadcast();
        assertTrue(host.tickHost());

        assertEquals(true, guest2Map.get("$gaKA_completed"));
        assertEquals(true, guest2Map.get("$sdtu_completed"));
        assertEquals(2.5f, guest2Map.get("$lionsGuardSuspicion"));
    }
}
