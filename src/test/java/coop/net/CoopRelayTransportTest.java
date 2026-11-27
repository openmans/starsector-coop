package coop.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Item 5's client half: the mod dials the relay itself instead of listening or connecting to the
 * host. A hand-rolled relay is enough to prove the two things that matter - the right handshake verb
 * with the configured room and token, and that the bridged socket becomes the peer channel.
 */
class CoopRelayTransportTest {

    private static final String ADDRESS = "coop.relayAddress";
    private static final String ROOM = "coop.relayRoom";
    private static final String TOKEN = "coop.relayToken";

    @AfterEach
    void clear() {
        System.clearProperty(ADDRESS);
        System.clearProperty(ROOM);
        System.clearProperty(TOKEN);
    }

    @Test
    void theHostRegistersOnTheRelayAndUsesTheBridgedSocket() throws Exception {
        List<String> handshakes = new CopyOnWriteArrayList<>();
        try (ServerSocket relay = new ServerSocket(0)) {
            Thread worker = acceptOne(relay, handshakes);
            configure(relay.getLocalPort());

            CoopNetService service = new CoopNetService();
            service.setLocalSenderId("host-1");
            service.startHost(7799);

            awaitHandshake(handshakes);
            assertEquals(CoopConnectionRole.HOST, service.role());
            assertTrue(service.isConnected(),
                    "a registered relay socket is the host's peer channel, exactly like an accepted one");
            assertEquals(List.of(CoopRelayProtocol.CMD_REGISTER + " room-1 tok-1"), handshakes);
        }
    }

    @Test
    void theGuestJoinsTheRoomInsteadOfDiallingTheHost() throws Exception {
        List<String> handshakes = new CopyOnWriteArrayList<>();
        try (ServerSocket relay = new ServerSocket(0)) {
            Thread worker = acceptOne(relay, handshakes);
            configure(relay.getLocalPort());

            CoopNetService service = new CoopNetService();
            service.setLocalSenderId("guest-1");
            service.connect("some-lan-host", 7777);

            awaitHandshake(handshakes);
            assertEquals(CoopConnectionRole.GUEST, service.role());
            assertTrue(service.isConnected(), "the joined relay socket carries the session");
            assertEquals(List.of(CoopRelayProtocol.CMD_JOIN + " room-1 tok-1"), handshakes);
        }
    }

    /** Waits for the fake relay to see the handshake; the dial itself has already completed. */
    private static void awaitHandshake(List<String> handshakes) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        while (handshakes.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertFalse(handshakes.isEmpty(), "the relay never saw a handshake line");
    }

    /** Accepts one socket, records its handshake line, and answers RELAY_OK. */
    private static Thread acceptOne(ServerSocket relay, List<String> handshakes) {
        Thread worker = new Thread(() -> {
            try (Socket socket = relay.accept()) {
                socket.setSoTimeout(5000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));
                String line = reader.readLine();
                if (line != null) {
                    handshakes.add(line.trim());
                }
                socket.getOutputStream().write(
                        CoopRelayProtocol.formatOk().getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                // Hold the bridge open while the assertions run; the transport only needs the socket
                // to stay up long enough to be attached and reported connected.
                Thread.sleep(2000L);
            } catch (Exception ignored) {
                // The socket closing is the normal end of this fake relay's life.
            }
        }, "fake-coop-relay");
        worker.setDaemon(true);
        worker.start();
        return worker;
    }

    private static void configure(int port) {
        System.setProperty(ADDRESS, "127.0.0.1:" + port);
        System.setProperty(ROOM, "room-1");
        System.setProperty(TOKEN, "tok-1");
    }
}
