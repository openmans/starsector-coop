package coop.launcher;

import coop.net.CoopRelayProtocol;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight standalone CGNAT TCP relay server (Item 5) runnable directly from
 * {@code coop-launcher.jar --relay <port>} for players behind double NAT / CGNAT who cannot forward
 * ports or use VPN.
 *
 * <p>Pairs a {@code RELAY_REGISTER <room> <token>} host socket with a matching
 * {@code RELAY_JOIN <room> <token>} guest socket and transparently pumps raw TCP bytes in both
 * directions (including encrypted {@code ENC1:} frames without ever inspecting plaintext).
 */
public final class CoopRelayServer implements Closeable {

    private record PendingHost(Socket socket, String token) {
    }

    private final ServerSocket serverSocket;
    private final Map<String, PendingHost> waitingRooms = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "coop-relay-worker");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;

    public CoopRelayServer(int port) throws IOException {
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress(port));
    }

    public int boundPort() {
        return serverSocket.getLocalPort();
    }

    /** Starts the accept loop on a background daemon thread. */
    public void startAsync() {
        workers.submit(this::acceptLoop);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                client.setTcpNoDelay(true);
                workers.submit(() -> handleClient(client));
            } catch (IOException ex) {
                if (running) {
                    // continue until closed
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(15_000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            String line = readHeaderLine(in);
            CoopRelayProtocol.Command cmd = CoopRelayProtocol.parseCommand(line);
            if (cmd == null) {
                writeUtf8(out, CoopRelayProtocol.formatError("invalid-command"));
                closeQuietly(socket);
                return;
            }
            socket.setSoTimeout(0);
            if (CoopRelayProtocol.CMD_REGISTER.equals(cmd.verb())) {
                PendingHost previous = waitingRooms.put(cmd.roomCode(), new PendingHost(socket, cmd.token()));
                if (previous != null) {
                    closeQuietly(previous.socket());
                }
                writeUtf8(out, CoopRelayProtocol.formatOk());
                return;
            }
            if (CoopRelayProtocol.CMD_JOIN.equals(cmd.verb())) {
                PendingHost host = waitingRooms.remove(cmd.roomCode());
                if (host == null || host.socket().isClosed()) {
                    writeUtf8(out, CoopRelayProtocol.formatError("room-not-found"));
                    closeQuietly(socket);
                    return;
                }
                if (!host.token().isEmpty() && !host.token().equals(cmd.token())) {
                    waitingRooms.putIfAbsent(cmd.roomCode(), host);
                    writeUtf8(out, CoopRelayProtocol.formatError("bad-token"));
                    closeQuietly(socket);
                    return;
                }
                writeUtf8(out, CoopRelayProtocol.formatOk());
                bridge(host.socket(), socket);
            }
        } catch (IOException ex) {
            closeQuietly(socket);
        }
    }

    private void bridge(Socket a, Socket b) {
        workers.submit(() -> pump(a, b));
        workers.submit(() -> pump(b, a));
    }

    private static void pump(Socket from, Socket to) {
        byte[] buf = new byte[8192];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // peer closed
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private static String readHeaderLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                sb.append((char) b);
                if (sb.length() > 256) {
                    break;
                }
            }
        }
        return sb.toString();
    }

    private static void writeUtf8(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void closeQuietly(Socket s) {
        try {
            if (s != null) {
                s.close();
            }
        } catch (IOException ignored) {
            // ignore
        }
    }

    @Override
    public void close() throws IOException {
        running = false;
        serverSocket.close();
        for (PendingHost pending : waitingRooms.values()) {
            closeQuietly(pending.socket());
        }
        waitingRooms.clear();
        workers.shutdownNow();
    }
}
