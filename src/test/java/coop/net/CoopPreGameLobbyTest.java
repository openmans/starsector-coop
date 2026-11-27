package coop.net;

import coop.ui.CoopInGameLauncherSession;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
            // Start is blocked until all players are ready
            assertFalse(session.startGameFromLobby());
            assertTrue(session.isPreGameLobbyActive());

            session.toggleLobbyReady();
            assertTrue(session.startGameFromLobby());
            assertTrue(session.wasLaunchedFromPreGameLobby());
            assertTrue(session.consumePendingAutoEnterGame());
            assertFalse(session.consumePendingAutoEnterGame());
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

    @Test
    void chunkedSharedSaveTransferDeliversExactZipToGuestBeforeStart() throws Exception {
        CoopPreGameLobby host = new CoopPreGameLobby();
        CoopPreGameLobby guest = new CoopPreGameLobby();
        try {
            assertTrue(host.startHost(0, "savedpw", "Host", "MN-11223344", "normal", "mixed", 1_000L));
            assertTrue(guest.startGuest("127.0.0.1", host.boundPort(), "savedpw", "Guest", 1_000L));
            pumpBoth(host, guest);
            guest.toggleLocalReady();
            pumpBoth(host, guest);
            assertTrue(host.allReady());

            // Multi-chunk (~140 KB) synthetic ZIP payload
            byte[] payload = new byte[140_000];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) ((i * 31) ^ (i >> 7));
            }
            assertTrue(host.triggerHostStartWithSave("save_coop_shared", payload, 15_000L));
            assertTrue(host.isTransferringSave());

            long now = 15_000L;
            byte[] received = null;
            for (int i = 0; i < 80; i++) {
                now += 25L;
                host.tick(now);
                guest.tick(now);
                byte[] chunked = guest.consumeCompletedSaveZip();
                if (chunked != null) {
                    received = chunked;
                }
                if (host.isLaunchTriggered() && guest.isLaunchTriggered()) {
                    break;
                }
                Thread.sleep(10);
            }

            assertNotNull(received, "Guest should have assembled the full shared save zip");
            assertArrayEquals(payload, received);
            assertFalse(guest.consumeCompletedSaveIsOwn());
            assertTrue(host.isLaunchTriggered());
            assertTrue(guest.isLaunchTriggered());
            assertTrue(host.useSharedSaveOnStart());
            assertTrue(guest.useSharedSaveOnStart());
        } finally {
            host.stop();
            guest.stop();
        }
    }

    @Test
    void perPlayerSaveStreamingDeliversPersonalSaveByLobbyNameAndFallbackOtherwise() throws Exception {
        CoopPreGameLobby host = new CoopPreGameLobby();
        CoopPreGameLobby guestA = new CoopPreGameLobby();
        CoopPreGameLobby guestB = new CoopPreGameLobby();
        try {
            assertTrue(host.startHost(0, "pw", "Артур", "MN-998877", "normal", "mixed", 1_000L));
            assertTrue(guestA.startGuest("127.0.0.1", host.boundPort(), "pw", "Вася", 1_000L));
            assertTrue(guestB.startGuest("127.0.0.1", host.boundPort(), "pw", "Петя", 1_000L));
            pumpBoth(host, guestA, guestB);
            guestA.toggleLocalReady();
            guestB.toggleLocalReady();
            pumpBoth(host, guestA, guestB);
            assertTrue(host.allReady());

            byte[] vasyaZip = new byte[70_000];
            byte[] fallbackZip = new byte[65_000];
            for (int i = 0; i < vasyaZip.length; i++) vasyaZip[i] = (byte) (i + 3);
            for (int i = 0; i < fallbackZip.length; i++) fallbackZip[i] = (byte) (i * 7);

            assertTrue(host.triggerHostStartWithPlayerSaves(name -> {
                if ("Вася".equals(name)) {
                    return new CoopPreGameLobby.SavePayload("save_coop_vasya", vasyaZip, true);
                }
                return new CoopPreGameLobby.SavePayload("save_coop_shared", fallbackZip, false);
            }, 10_000L));

            long now = 10_000L;
            byte[] recA = null;
            boolean ownA = false;
            byte[] recB = null;
            boolean ownB = true;
            for (int i = 0; i < 80; i++) {
                now += 25L;
                host.tick(now);
                guestA.tick(now);
                guestB.tick(now);
                byte[] zA = guestA.consumeCompletedSaveZip();
                if (zA != null) {
                    recA = zA;
                    ownA = guestA.consumeCompletedSaveIsOwn();
                }
                byte[] zB = guestB.consumeCompletedSaveZip();
                if (zB != null) {
                    recB = zB;
                    ownB = guestB.consumeCompletedSaveIsOwn();
                }
                if (host.isLaunchTriggered() && guestA.isLaunchTriggered() && guestB.isLaunchTriggered()) {
                    break;
                }
                Thread.sleep(10);
            }

            assertArrayEquals(vasyaZip, recA);
            assertTrue(ownA);
            assertArrayEquals(fallbackZip, recB);
            assertFalse(ownB);
            assertEquals("артур", coop.ambient.CoopTitleScreenBridge.sanitizePlayerVaultKey("Артур"));
            assertEquals("yasper_moglot",
                    coop.ambient.CoopTitleScreenBridge.sanitizePlayerVaultKey(" Yasper / Moglot! "));
        } finally {
            host.stop();
            guestA.stop();
            guestB.stop();
        }
    }
}
