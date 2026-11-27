package coop.net;

import coop.ui.CoopInGameLauncherSession;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopPreGameLobbyTest {

    private static void pumpBoth(CoopPreGameLobby host, CoopPreGameLobby... guests) throws InterruptedException {
        long now = 10_000L;
        for (int i = 0; i < 40; i++) {
            now += 25L;
            host.tick(now);
            for (CoopPreGameLobby g : guests) {
                g.tick(now);
            }
            Thread.sleep(10);
        }
    }

    @Test
    void hostAndMultipleGuestsSeeEachOtherToggleReadyAndStartGame() throws Exception {
        CoopPreGameLobby host = new CoopPreGameLobby();
        CoopPreGameLobby guest1 = new CoopPreGameLobby();
        CoopPreGameLobby guest2 = new CoopPreGameLobby();
        try {
            assertTrue(host.startHost(0, "secret", "Артур", "MN-1234567890", "small", "old", 1_000L));
            int port = host.boundPort();
            assertTrue(port > 0);
            assertEquals(1, host.totalCount());
            assertEquals(1, host.readyCount());
            assertTrue(host.allReady());

            assertTrue(guest1.startGuest("127.0.0.1", port, "secret", "Саша", 1_000L));
            assertTrue(guest2.startGuest("127.0.0.1", port, "secret", "Дима", 1_000L));
            pumpBoth(host, guest1, guest2);

            // All three see each other with exact roles and synced world settings
            assertEquals(3, host.totalCount());
            assertEquals(3, guest1.totalCount());
            assertEquals(3, guest2.totalCount());
            assertEquals("MN-1234567890", guest1.worldSeed());
            assertEquals("small", guest1.worldSize());
            assertEquals("old", guest1.worldAge());
            assertEquals(1, host.readyCount());
            assertFalse(host.allReady());

            List<String> namesOnGuest1 = guest1.members().stream().map(CoopPreGameLobby.Member::name).toList();
            assertTrue(namesOnGuest1.contains("Артур"));
            assertTrue(namesOnGuest1.contains("Саша"));
            assertTrue(namesOnGuest1.contains("Дима"));

            // Guests toggle ready -> everyone sees 3/3 ready
            guest1.toggleLocalReady();
            guest2.toggleLocalReady();
            pumpBoth(host, guest1, guest2);

            assertEquals(3, host.readyCount());
            assertTrue(host.allReady());
            assertTrue(guest1.allReady());
            assertTrue(guest2.allReady());

            // Host clicks Start Game -> guests receive START and channels close
            host.triggerHostStart();
            pumpBoth(host, guest1, guest2);

            assertTrue(host.isLaunchTriggered());
            assertTrue(guest1.isLaunchTriggered());
            assertTrue(guest2.isLaunchTriggered());
            assertFalse(host.isActive());
            assertFalse(guest1.isActive());
            assertFalse(guest2.isActive());
        } finally {
            host.stop();
            guest1.stop();
            guest2.stop();
        }
    }

    @Test
    void wrongPasswordIsRejectedClearly() throws Exception {
        CoopPreGameLobby host = new CoopPreGameLobby();
        CoopPreGameLobby guest = new CoopPreGameLobby();
        try {
            assertTrue(host.startHost(0, "correctpw", "Host", "MN-1111111", "normal", "mixed", 1_000L));
            assertTrue(guest.startGuest("127.0.0.1", host.boundPort(), "wrongpw", "Guest", 1_000L));
            pumpBoth(host, guest);

            assertEquals(CoopPreGameLobby.State.ERROR, guest.state());
            assertEquals("WRONG_PASSWORD", guest.lastError());
            assertEquals(1, host.totalCount());
        } finally {
            host.stop();
            guest.stop();
        }
    }

    @Test
    void titleButtonLabelReflectsLobbyReadiness() throws Exception {
        int freePort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            freePort = probe.getLocalPort();
        }
        CoopInGameLauncherSession session = new CoopInGameLauncherSession();
        session.setRussian(true);
        session.setPlayerName("Артур");
        session.setHostPort(String.valueOf(freePort));
        session.setHostSeed("MN-7777777777");
        try {
            assertTrue(session.openHostLobby(null));
            assertTrue(session.isPreGameLobbyActive());
            assertEquals("CO-OP · ЛОББИ (1/1)", session.titleButtonLabel());

            session.toggleLobbyReady();
            assertEquals("CO-OP · ЛОББИ (0/1)", session.titleButtonLabel());

            session.startGameFromLobby();
            assertFalse(session.isPreGameLobbyActive());
            assertEquals("CO-OP · ВХОД В МИР!", session.titleButtonLabel());
        } finally {
            session.leavePreGameLobby();
        }
    }

    @Test
    void quickConnectLastSettingsFailsCleanlyWithoutHostAndJoinsWithSavedHost() throws Exception {
        CoopInGameLauncherSession emptySession = new CoopInGameLauncherSession();
        emptySession.setRussian(true);
        assertFalse(emptySession.hasSavedJoinSettings());
        assertFalse(emptySession.quickConnectLastSettings(null));
        assertEquals(CoopInGameLauncherSession.Tab.JOIN, emptySession.activeTab());
        assertFalse(emptySession.statusText().isEmpty());

        CoopPreGameLobby host = new CoopPreGameLobby();
        CoopInGameLauncherSession guestSession = new CoopInGameLauncherSession();
        guestSession.setRussian(true);
        guestSession.setPlayerName("Тестер");
        try {
            assertTrue(host.startHost(0, "pw123", "Хост", "MN-5555555555", "normal", "mixed", 1_000L));
            guestSession.setGuestHost("127.0.0.1");
            guestSession.setGuestPort(String.valueOf(host.boundPort()));
            guestSession.setGuestPassword("pw123");
            assertTrue(guestSession.hasSavedJoinSettings());
            assertTrue(guestSession.quickConnectLastSettings(null));

            pumpBoth(host, guestSession.preGameLobby());
            assertEquals(CoopPreGameLobby.State.IN_LOBBY, guestSession.preGameLobby().state());
            assertEquals(2, host.totalCount());
            assertEquals(2, guestSession.preGameLobby().totalCount());
        } finally {
            host.stop();
            guestSession.leavePreGameLobby();
        }
    }
}
