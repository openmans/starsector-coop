package coop.wingman;

import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopWingmanIntegrationTest {

    private static final class FakeApi implements CoopWingmanApi {
        boolean enabled = true;
        boolean joined;
        String localName = "Ann";
        int openHostCalls;
        boolean openHostOk = true;
        int playerNamesCalls;
        List<String> joiners = new ArrayList<>();
        final List<String[]> reservations = new ArrayList<>();

        @Override public boolean wingmanEnabled() { return enabled; }
        @Override public boolean isJoined() { return joined; }
        @Override public String localName() { return localName; }
        @Override public boolean openHost() { openHostCalls++; return openHostOk; }
        @Override public String pumpHost() { return ""; }
        @Override public List<String> playerNames() { playerNamesCalls++; return joiners; }
        @Override public void reserveShip(String name, String fleetMemberId) {
            reservations.add(new String[]{name, fleetMemberId});
        }
    }

    private static final class Harness {
        final AtomicReference<String> sessionId = new AtomicReference<>("");
        final AtomicReference<CoopConnectionRole> role = new AtomicReference<>(CoopConnectionRole.HOST);
        final FakeApi api = new FakeApi();
        final AtomicReference<Boolean> option = new AtomicReference<>(true);
        final AtomicReference<String> flagship = new AtomicReference<>("");
        final AtomicReference<String> connectHost = new AtomicReference<>("198.51.100.42");
        final List<CoopMessages.Message> sent = new ArrayList<>();
        final List<String> feed = new ArrayList<>();
        final AtomicInteger seq = new AtomicInteger(1);
        final CoopWingmanIntegration integration = new CoopWingmanIntegration(
                role::get, seq::getAndIncrement, sessionId::get, () -> 0L, api,
                option::get, flagship::get, sent::add, feed::add, connectHost::get);
    }

    @Test
    void hostOpensAndPumpsOncePerSession() {
        Harness h = new Harness();
        h.sessionId.set("s1");
        h.integration.tick();
        h.integration.tick();
        assertEquals(1, h.api.openHostCalls);
        // a new session re-opens
        h.sessionId.set("s2");
        h.integration.tick();
        assertEquals(2, h.api.openHostCalls);
    }

    @Test
    void hostReservesGuestFlagshipOnceJoinerWithKnownNameIsConnected() {
        Harness h = new Harness();
        h.sessionId.set("s1");
        h.integration.tick();
        h.integration.onGuestWingmanName("Ann");
        h.flagship.set("flagship-7");
        h.api.joiners.add("ann"); // Wingman matches ignoring case
        h.integration.tick();
        assertEquals(1, h.api.reservations.size());
        assertEquals("Ann", h.api.reservations.get(0)[0]);
        assertEquals("flagship-7", h.api.reservations.get(0)[1]);
        int callsAfterReserve = h.api.playerNamesCalls;
        // no double reservation and no redundant playerNames poll while flagship is unchanged
        h.integration.tick();
        assertEquals(1, h.api.reservations.size());
        assertEquals(callsAfterReserve, h.api.playerNamesCalls);
        // next session clears old reservation and reserves again
        h.sessionId.set("s2");
        h.integration.tick();
        h.integration.onGuestWingmanName("Ann");
        h.integration.tick();
        assertEquals(3, h.api.reservations.size());
        assertNull(h.api.reservations.get(1)[0]);
        assertEquals("flagship-7", h.api.reservations.get(1)[1]);
        assertEquals("Ann", h.api.reservations.get(2)[0]);
        assertEquals("flagship-7", h.api.reservations.get(2)[1]);
    }

    @Test
    void hostUpdatesReservationWhenGuestChangesOrLosesFlagship() {
        Harness h = new Harness();
        h.sessionId.set("s1");
        h.integration.tick();
        h.integration.onGuestWingmanName("Ann");
        h.flagship.set("flagship-1");
        h.api.joiners.add("Ann");
        h.integration.tick();
        assertEquals(1, h.api.reservations.size());
        assertEquals(1, h.feed.size());

        // Guest switches flagship mid-session -> old reservation cleared, new one reserved, no feed spam
        h.flagship.set("flagship-2");
        h.integration.tick();
        assertEquals(3, h.api.reservations.size());
        assertNull(h.api.reservations.get(1)[0]);
        assertEquals("flagship-1", h.api.reservations.get(1)[1]);
        assertEquals("Ann", h.api.reservations.get(2)[0]);
        assertEquals("flagship-2", h.api.reservations.get(2)[1]);
        assertEquals(1, h.feed.size());
    }

    @Test
    void hostClearsReservationOnReset() {
        Harness h = new Harness();
        h.sessionId.set("s1");
        h.integration.tick();
        h.integration.onGuestWingmanName("Ann");
        h.flagship.set("flagship-1");
        h.api.joiners.add("Ann");
        h.integration.tick();
        assertEquals(1, h.api.reservations.size());

        h.integration.reset();
        assertEquals(2, h.api.reservations.size());
        assertNull(h.api.reservations.get(1)[0]);
        assertEquals("flagship-1", h.api.reservations.get(1)[1]);
    }

    @Test
    void hostDoesNotReserveBeforeFlagshipOrNameKnown() {
        Harness h = new Harness();
        h.sessionId.set("s1");
        h.integration.tick();
        h.api.joiners.add("Ann");
        h.integration.tick(); // no name yet
        assertEquals(0, h.api.reservations.size());
        h.integration.onGuestWingmanName("Ann");
        h.integration.tick(); // no flagship yet
        assertEquals(0, h.api.reservations.size());
        h.flagship.set("f1");
        h.integration.tick();
        assertEquals(1, h.api.reservations.size());
    }

    @Test
    void guestSendsOneHelloAndOneHintWithConfiguredHostPerSession() {
        Harness h = new Harness();
        h.role.set(CoopConnectionRole.GUEST);
        h.api.localName = "Guesty";
        h.sessionId.set("s1");
        h.integration.tick();
        h.integration.tick();
        assertEquals(1, h.sent.size());
        CoopMessages.Message hello = h.sent.get(0);
        assertEquals(CoopMessages.Type.WINGMAN_HELLO, hello.type());
        assertEquals("Guesty", CoopMessages.parseWingmanHelloName(hello));
        assertEquals(1, h.feed.size());
        assertTrue(h.feed.get(0).contains("198.51.100.42:24816"), h.feed.get(0));
        // next session re-sends; blank host falls back to generic hint
        h.connectHost.set("");
        h.sessionId.set("s2");
        h.integration.tick();
        assertEquals(2, h.sent.size());
        assertEquals(2, h.feed.size());
        assertTrue(h.feed.get(1).contains("port 24816"), h.feed.get(1));
    }

    @Test
    void nothingHappensWhenOptionOffOrWingmanMissing() {
        Harness h = new Harness();
        h.sessionId.set("s1");
        h.option.set(false);
        h.integration.tick();
        assertEquals(0, h.api.openHostCalls);
        assertFalse(h.integration.isEnabledAndAvailable());

        h.option.set(true);
        h.api.enabled = false;
        h.integration.tick();
        assertEquals(0, h.api.openHostCalls);
        assertFalse(h.integration.isEnabledAndAvailable());
        assertFalse(h.integration.guestCombatIsWingmanJoin());
    }

    @Test
    void guestCombatIsWingmanJoinGatesOnOptionRoleSessionEnabledAndJoin() {
        Harness h = new Harness();
        assertFalse(h.integration.guestCombatIsWingmanJoin()); // host + no session
        h.sessionId.set("s1");
        h.role.set(CoopConnectionRole.GUEST);
        assertFalse(h.integration.guestCombatIsWingmanJoin()); // not joined
        h.api.joined = true;
        assertTrue(h.integration.guestCombatIsWingmanJoin());
        h.option.set(false);
        assertFalse(h.integration.guestCombatIsWingmanJoin()); // option off
        h.option.set(true);
        h.api.enabled = false;
        assertFalse(h.integration.guestCombatIsWingmanJoin()); // mod disabled
    }

    @Test
    void classifyDecisionCoversAllQuadrantsAndEarlyWingmanRace() {
        assertTrue(CoopWingmanIntegration.classifyGuestWingmanJoin(false, true, true, true));
        assertFalse(CoopWingmanIntegration.classifyGuestWingmanJoin(true, true, true, true));
        assertFalse(CoopWingmanIntegration.classifyGuestWingmanJoin(false, false, true, true));
        assertFalse(CoopWingmanIntegration.classifyGuestWingmanJoin(false, true, false, true));
        assertFalse(CoopWingmanIntegration.classifyGuestWingmanJoin(false, true, true, false));
        // Wingman started guest combat before BATTLE_BEGIN arrived over coop TCP
        assertTrue(CoopWingmanIntegration.classifyGuestWingmanJoin(false, false, true, true, true));
        assertFalse(CoopWingmanIntegration.classifyGuestWingmanJoin(true, false, true, true, true));
        assertFalse(CoopWingmanIntegration.classifyGuestWingmanJoin(false, false, true, false, true));
    }

    @Test
    void wingmanHelloPayloadRoundTripsAndTrimsOnTheHostSide() {
        CoopMessages.Message hello = CoopMessages.wingmanHello("s1", 7L, 100L, "Ann");
        assertEquals("Ann", CoopMessages.parseWingmanHelloName(hello));
        assertEquals(CoopMessages.Type.WINGMAN_HELLO, hello.type());

        Harness h = new Harness();
        h.sessionId.set("s1");
        h.integration.tick(); // session boundary first; the name arrives on a later frame
        h.integration.onGuestWingmanName("  Someone  ");
        h.flagship.set("f9");
        h.api.joiners.add("SOMEONE");
        h.integration.tick();
        assertEquals(1, h.api.reservations.size());
        assertEquals("Someone", h.api.reservations.get(0)[0]);
    }

    @Test
    void ignoredOnTheWrongRole() {
        Harness h = new Harness();
        h.role.set(CoopConnectionRole.GUEST);
        h.sessionId.set("s1");
        h.integration.onGuestWingmanName("Ann");
        h.flagship.set("f9");
        h.api.joiners.add("Ann");
        h.integration.tick();
        assertEquals(0, h.api.reservations.size());
        assertEquals(0, h.api.openHostCalls);
    }
}
