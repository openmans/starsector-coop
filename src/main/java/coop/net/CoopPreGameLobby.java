package coop.net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Lightweight non-blocking NIO TCP pre-game lobby used on Starsector's green Title Screen
 * {@code CO-OP} modal so Host and Guest(s) can connect, see each other live, toggle Ready state,
 * receive the host's world settings, and synchronize launching into the campaign.
 *
 * <p>Deliberately uses only {@code java.nio.channels.*} and {@code java.net.*} (zero
 * {@code java.io.*}, zero background threads, zero reflection) so it stays 100% inside
 * Starsector's script sandbox and ticks cleanly on the main LWJGL frame thread.
 */
public final class CoopPreGameLobby {

    private static final int MAX_CLIENTS = 8;
    private static final int MAX_LINE_BYTES = 16 * 1024;
    private static final long PING_INTERVAL_MS = 3_000L;
    private static final long PEER_TIMEOUT_MS = 12_000L;
    private static final long CONNECT_TIMEOUT_MS = 8_000L;

    public enum State {
        IDLE,
        HOSTING,
        CONNECTING,
        IN_LOBBY,
        LAUNCHING,
        ERROR
    }

    public record Member(String id, String name, boolean host, boolean ready) {
        public Member {
            id = id == null ? "" : id;
            name = name == null || name.isBlank() ? "Player" : name.trim();
        }
    }

    private static final class PeerLink {
        final String id;
        final SocketChannel channel;
        final StringBuilder rx = new StringBuilder();
        final ByteBuffer readBuf = ByteBuffer.allocate(4096);
        final List<ByteBuffer> txQueue = new ArrayList<>();
        String name = "";
        boolean authed;
        boolean ready;
        long lastHeardAt;

        PeerLink(String id, SocketChannel channel, long now) {
            this.id = id;
            this.channel = channel;
            this.lastHeardAt = now;
        }
    }

    private State state = State.IDLE;
    private boolean hostMode;
    private ServerSocketChannel serverChannel;
    private int boundPort;
    private int nextClientSeq;

    // Host state
    private String hostPassword = "";
    private String localName = "Player";
    private boolean localReady;
    private String worldSeed = "";
    private String worldSize = "normal";
    private String worldAge = "mixed";
    private final List<PeerLink> clients = new ArrayList<>();

    // Guest state
    private PeerLink serverLink;
    private String guestPassword = "";
    private long connectStartedAt;
    private long nextPingAt;

    // Shared snapshot for UI
    private List<Member> members = List.of();
    private boolean launchTriggered;
    private String lastError = "";
    private long revision;

    public State state() { return state; }
    public boolean isActive() {
        return state == State.HOSTING || state == State.CONNECTING || state == State.IN_LOBBY;
    }
    public boolean isHost() { return hostMode; }
    public boolean isConnected() { return state == State.HOSTING || state == State.IN_LOBBY; }
    public boolean isLaunchTriggered() { return launchTriggered; }
    public boolean isLocalReady() { return localReady; }
    public int boundPort() { return boundPort; }
    public String worldSeed() { return worldSeed; }
    public String worldSize() { return worldSize; }
    public String worldAge() { return worldAge; }
    public String lastError() { return lastError; }
    public long revision() { return revision; }
    public List<Member> members() { return members; }

    public int totalCount() {
        return members.size();
    }

    public int readyCount() {
        int c = 0;
        for (Member m : members) {
            if (m.ready()) c++;
        }
        return c;
    }

    public boolean allReady() {
        return !members.isEmpty() && readyCount() == members.size();
    }

    /** Starts a non-blocking TCP lobby server on {@code port} (pass {@code 0} for an ephemeral test port). */
    public boolean startHost(int port, String password, String hostName,
                             String seed, String sectorSize, String sectorAge, long nowMillis) {
        stop();
        this.hostMode = true;
        this.hostPassword = password == null ? "" : password.trim();
        this.localName = sanitizeName(hostName, "Host");
        this.localReady = true;
        this.worldSeed = seed == null ? "" : seed.trim();
        this.worldSize = sectorSize == null || sectorSize.isBlank() ? "normal" : sectorSize.trim();
        this.worldAge = sectorAge == null || sectorAge.isBlank() ? "mixed" : sectorAge.trim();
        this.launchTriggered = false;
        this.lastError = "";

        try {
            ServerSocketChannel ssc = ServerSocketChannel.open();
            ssc.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            ssc.configureBlocking(false);
            ssc.bind(new InetSocketAddress(port));
            this.serverChannel = ssc;
            this.boundPort = ((InetSocketAddress) ssc.getLocalAddress()).getPort();
            this.state = State.HOSTING;
            this.nextPingAt = nowMillis + PING_INTERVAL_MS;
            rebuildHostMembers();
            bumpRevision();
            return true;
        } catch (Exception ex) {
            this.lastError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            this.state = State.ERROR;
            bumpRevision();
            closeQuietly(serverChannel);
            serverChannel = null;
            return false;
        }
    }

    /** Updates world metadata while hosting and pushes the updated roster to all connected guests. */
    public void updateHostWorld(String seed, String sectorSize, String sectorAge) {
        if (!hostMode || state != State.HOSTING) {
            return;
        }
        String s = seed == null ? "" : seed.trim();
        String sz = sectorSize == null || sectorSize.isBlank() ? "normal" : sectorSize.trim();
        String ag = sectorAge == null || sectorAge.isBlank() ? "mixed" : sectorAge.trim();
        if (!s.equals(worldSeed) || !sz.equals(worldSize) || !ag.equals(worldAge)) {
            worldSeed = s;
            worldSize = sz;
            worldAge = ag;
            broadcastRoster();
            bumpRevision();
        }
    }

    /** Begins a non-blocking TCP connection to a host's pre-game lobby. */
    public boolean startGuest(String host, int port, String password, String guestName, long nowMillis) {
        stop();
        this.hostMode = false;
        this.guestPassword = password == null ? "" : password.trim();
        this.localName = sanitizeName(guestName, "Guest");
        this.localReady = false;
        this.launchTriggered = false;
        this.lastError = "";
        this.boundPort = port;
        this.members = List.of(new Member("local", this.localName, false, false));

        try {
            SocketChannel ch = SocketChannel.open();
            ch.configureBlocking(false);
            ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
            boolean immediate = ch.connect(new InetSocketAddress(host, port));
            this.serverLink = new PeerLink("host", ch, nowMillis);
            this.connectStartedAt = nowMillis;
            this.nextPingAt = nowMillis + PING_INTERVAL_MS;
            this.state = State.CONNECTING;
            if (immediate) {
                sendGuestHello();
            }
            bumpRevision();
            return true;
        } catch (Exception ex) {
            this.lastError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            this.state = State.ERROR;
            bumpRevision();
            return false;
        }
    }

    /** Toggles local Ready state and syncs it immediately across the lobby. */
    public void toggleLocalReady() {
        setLocalReady(!localReady);
    }

    public void setLocalReady(boolean ready) {
        if (!isActive() || this.localReady == ready) {
            return;
        }
        this.localReady = ready;
        if (hostMode) {
            rebuildHostMembers();
            broadcastRoster();
        } else if (serverLink != null && state == State.IN_LOBBY) {
            JSONObject msg = new JSONObject();
            putQuiet(msg, "t", "READY");
            putQuiet(msg, "ready", ready);
            enqueue(serverLink, msg);
        }
        bumpRevision();
    }

    /** Host triggers campaign launch: notifies all guests with final world settings and closes the lobby port. */
    public void triggerHostStart() {
        if (!hostMode || state != State.HOSTING) {
            return;
        }
        JSONObject start = new JSONObject();
        putQuiet(start, "t", "START");
        putQuiet(start, "seed", worldSeed);
        putQuiet(start, "size", worldSize);
        putQuiet(start, "age", worldAge);
        putQuiet(start, "port", boundPort);
        for (PeerLink link : clients) {
            if (link.authed) {
                enqueue(link, start);
                flush(link);
            }
        }
        launchTriggered = true;
        state = State.LAUNCHING;
        closeChannelsOnly();
        bumpRevision();
    }

    /** Advances non-blocking I/O; call once per UI frame. Returns {@code true} if state/roster changed. */
    public boolean tick(long nowMillis) {
        long before = revision;
        if (hostMode && state == State.HOSTING) {
            tickHost(nowMillis);
        } else if (!hostMode && (state == State.CONNECTING || state == State.IN_LOBBY)) {
            tickGuest(nowMillis);
        }
        return revision != before;
    }

    /** Closes all sockets and resets lobby state to {@link State#IDLE}. */
    public void stop() {
        closeChannelsOnly();
        state = State.IDLE;
        hostMode = false;
        boundPort = 0;
        localReady = false;
        launchTriggered = false;
        lastError = "";
        members = List.of();
        bumpRevision();
    }

    /** Closes sockets while preserving {@link #launchTriggered} and world settings for transition into campaign. */
    public void closeChannelsKeepSummary() {
        closeChannelsOnly();
        if (state == State.HOSTING || state == State.CONNECTING || state == State.IN_LOBBY) {
            state = State.IDLE;
            bumpRevision();
        }
    }

    // ---- Host NIO loop ----

    private void tickHost(long now) {
        if (serverChannel != null) {
            try {
                while (true) {
                    SocketChannel accepted = serverChannel.accept();
                    if (accepted == null) {
                        break;
                    }
                    if (clients.size() >= MAX_CLIENTS) {
                        closeQuietly(accepted);
                        continue;
                    }
                    accepted.configureBlocking(false);
                    accepted.setOption(StandardSocketOptions.TCP_NODELAY, true);
                    String id = "g" + (++nextClientSeq);
                    clients.add(new PeerLink(id, accepted, now));
                }
            } catch (Exception ex) {
                this.lastError = "Accept error: " + ex.getMessage();
                bumpRevision();
            }
        }

        boolean changed = false;
        boolean sendPing = now >= nextPingAt;
        if (sendPing) {
            nextPingAt = now + PING_INTERVAL_MS;
        }

        Iterator<PeerLink> it = clients.iterator();
        while (it.hasNext()) {
            PeerLink link = it.next();
            boolean alive = readAndProcessHost(link, now);
            if (alive && now - link.lastHeardAt > PEER_TIMEOUT_MS) {
                alive = false;
            }
            if (alive && sendPing && link.authed) {
                JSONObject ping = new JSONObject();
                putQuiet(ping, "t", "PING");
                enqueue(link, ping);
            }
            if (alive && !flush(link)) {
                alive = false;
            }
            if (!alive) {
                boolean wasAuthed = link.authed;
                closeQuietly(link.channel);
                it.remove();
                if (wasAuthed) {
                    changed = true;
                }
            }
        }

        if (changed) {
            rebuildHostMembers();
            broadcastRoster();
            bumpRevision();
        }
    }

    private boolean readAndProcessHost(PeerLink link, long now) {
        List<String> lines = readLines(link, now);
        if (lines == null) {
            return false;
        }
        for (String line : lines) {
            try {
                JSONObject msg = new JSONObject(line);
                String type = msg.optString("t", "");
                switch (type) {
                    case "HELLO" -> {
                        String pw = msg.optString("pw", "").trim();
                        if (!hostPassword.isEmpty() && !hostPassword.equals(pw)) {
                            JSONObject rej = new JSONObject();
                            putQuiet(rej, "t", "REJECT");
                            putQuiet(rej, "reason", "wrong_password");
                            enqueue(link, rej);
                            flush(link);
                            return false;
                        }
                        link.name = sanitizeName(msg.optString("name", ""), "Guest " + link.id);
                        link.authed = true;
                        link.ready = false;
                        rebuildHostMembers();
                        broadcastRoster();
                        bumpRevision();
                    }
                    case "READY" -> {
                        if (link.authed) {
                            boolean r = msg.optBoolean("ready", false);
                            if (link.ready != r) {
                                link.ready = r;
                                rebuildHostMembers();
                                broadcastRoster();
                                bumpRevision();
                            }
                        }
                    }
                    case "LEAVE" -> {
                        return false;
                    }
                    case "PING" -> {
                        JSONObject pong = new JSONObject();
                        putQuiet(pong, "t", "PONG");
                        enqueue(link, pong);
                    }
                    default -> {
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return true;
    }

    private void rebuildHostMembers() {
        List<Member> list = new ArrayList<>(1 + clients.size());
        list.add(new Member("host", localName, true, localReady));
        for (PeerLink link : clients) {
            if (link.authed) {
                list.add(new Member(link.id, link.name, false, link.ready));
            }
        }
        this.members = List.copyOf(list);
    }

    private void broadcastRoster() {
        JSONObject msg = new JSONObject();
        putQuiet(msg, "t", "ROSTER");
        putQuiet(msg, "seed", worldSeed);
        putQuiet(msg, "size", worldSize);
        putQuiet(msg, "age", worldAge);
        putQuiet(msg, "port", boundPort);
        JSONArray arr = new JSONArray();
        for (Member m : members) {
            JSONObject item = new JSONObject();
            putQuiet(item, "id", m.id());
            putQuiet(item, "name", m.name());
            putQuiet(item, "host", m.host());
            putQuiet(item, "ready", m.ready());
            arr.put(item);
        }
        putQuiet(msg, "members", arr);
        for (PeerLink link : clients) {
            if (link.authed) {
                enqueue(link, msg);
                flush(link);
            }
        }
    }

    // ---- Guest NIO loop ----

    private void tickGuest(long now) {
        if (serverLink == null) {
            state = State.ERROR;
            lastError = "Disconnected";
            bumpRevision();
            return;
        }
        if (state == State.CONNECTING) {
            try {
                if (serverLink.channel.finishConnect()) {
                    sendGuestHello();
                    serverLink.lastHeardAt = now;
                } else if (now - connectStartedAt > CONNECT_TIMEOUT_MS) {
                    failGuest("Connection timed out");
                    return;
                }
            } catch (Exception ex) {
                failGuest(ex.getMessage() == null ? "Could not reach host" : ex.getMessage());
                return;
            }
        }

        if (now >= nextPingAt && state == State.IN_LOBBY) {
            nextPingAt = now + PING_INTERVAL_MS;
            JSONObject ping = new JSONObject();
            putQuiet(ping, "t", "PING");
            enqueue(serverLink, ping);
        }

        if (!flush(serverLink)) {
            failGuest("Lost connection to host lobby");
            return;
        }

        List<String> lines = readLines(serverLink, now);
        if (lines == null) {
            if (!launchTriggered) {
                failGuest("Host closed the lobby");
            }
            return;
        }
        for (String line : lines) {
            handleGuestMessage(line);
            if (state != State.CONNECTING && state != State.IN_LOBBY) {
                return;
            }
        }
        if (state == State.IN_LOBBY && now - serverLink.lastHeardAt > PEER_TIMEOUT_MS) {
            failGuest("Host lobby timed out");
        }
    }

    private void sendGuestHello() {
        if (serverLink == null || serverLink.authed) {
            return;
        }
        serverLink.authed = true;
        JSONObject hello = new JSONObject();
        putQuiet(hello, "t", "HELLO");
        putQuiet(hello, "name", localName);
        putQuiet(hello, "pw", guestPassword);
        enqueue(serverLink, hello);
        flush(serverLink);
    }

    private void handleGuestMessage(String line) {
        try {
            JSONObject msg = new JSONObject(line);
            String type = msg.optString("t", "");
            switch (type) {
                case "ROSTER" -> {
                    state = State.IN_LOBBY;
                    worldSeed = msg.optString("seed", worldSeed).trim();
                    String sz = msg.optString("size", "").trim();
                    if (!sz.isEmpty()) worldSize = sz;
                    String ag = msg.optString("age", "").trim();
                    if (!ag.isEmpty()) worldAge = ag;
                    int p = msg.optInt("port", boundPort);
                    if (p > 0) boundPort = p;

                    List<Member> parsed = new ArrayList<>();
                    JSONArray arr = msg.optJSONArray("members");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject item = arr.optJSONObject(i);
                            if (item != null) {
                                parsed.add(new Member(
                                        item.optString("id", ""),
                                        item.optString("name", "Player"),
                                        item.optBoolean("host", false),
                                        item.optBoolean("ready", false)));
                            }
                        }
                    }
                    if (!parsed.equals(this.members)) {
                        this.members = List.copyOf(parsed);
                    }
                    bumpRevision();
                }
                case "START" -> {
                    String s = msg.optString("seed", "").trim();
                    if (!s.isEmpty()) worldSeed = s;
                    String sz = msg.optString("size", "").trim();
                    if (!sz.isEmpty()) worldSize = sz;
                    String ag = msg.optString("age", "").trim();
                    if (!ag.isEmpty()) worldAge = ag;
                    int p = msg.optInt("port", boundPort);
                    if (p > 0) boundPort = p;
                    launchTriggered = true;
                    state = State.LAUNCHING;
                    closeChannelsOnly();
                    bumpRevision();
                }
                case "REJECT" -> {
                    String reason = msg.optString("reason", "rejected");
                    failGuest("wrong_password".equals(reason) ? "WRONG_PASSWORD" : reason);
                }
                case "PING" -> {
                    JSONObject pong = new JSONObject();
                    putQuiet(pong, "t", "PONG");
                    enqueue(serverLink, pong);
                    flush(serverLink);
                }
                default -> {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void failGuest(String error) {
        closeChannelsOnly();
        this.lastError = error == null ? "Connection failed" : error;
        this.state = State.ERROR;
        bumpRevision();
    }

    // ---- Framing helpers (zero java.io.*) ----

    private static List<String> readLines(PeerLink link, long now) {
        List<String> out = new ArrayList<>();
        try {
            while (true) {
                link.readBuf.clear();
                int n = link.channel.read(link.readBuf);
                if (n < 0) {
                    return out.isEmpty() ? null : out;
                }
                if (n == 0) {
                    break;
                }
                link.lastHeardAt = now;
                link.readBuf.flip();
                byte[] bytes = new byte[link.readBuf.remaining()];
                link.readBuf.get(bytes);
                link.rx.append(new String(bytes, StandardCharsets.UTF_8));
                if (link.rx.length() > MAX_LINE_BYTES * 4) {
                    return null;
                }
                int nl;
                while ((nl = link.rx.indexOf("\n")) >= 0) {
                    String line = link.rx.substring(0, nl).trim();
                    link.rx.delete(0, nl + 1);
                    if (!line.isEmpty()) {
                        out.add(line);
                    }
                }
            }
            return out;
        } catch (Exception ex) {
            return out.isEmpty() ? null : out;
        }
    }

    private static void enqueue(PeerLink link, JSONObject msg) {
        if (link == null) return;
        byte[] data = (msg.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        link.txQueue.add(ByteBuffer.wrap(data));
    }

    private static boolean flush(PeerLink link) {
        if (link == null) return false;
        try {
            while (!link.txQueue.isEmpty()) {
                ByteBuffer buf = link.txQueue.get(0);
                link.channel.write(buf);
                if (buf.hasRemaining()) {
                    break;
                }
                link.txQueue.remove(0);
            }
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private void closeChannelsOnly() {
        if (!hostMode && serverLink != null && state == State.IN_LOBBY && !launchTriggered) {
            JSONObject leave = new JSONObject();
            putQuiet(leave, "t", "LEAVE");
            enqueue(serverLink, leave);
            flush(serverLink);
        }
        closeQuietly(serverChannel);
        serverChannel = null;
        for (PeerLink link : clients) {
            closeQuietly(link.channel);
        }
        clients.clear();
        if (serverLink != null) {
            closeQuietly(serverLink.channel);
            serverLink = null;
        }
    }

    private static void closeQuietly(java.nio.channels.Channel ch) {
        if (ch != null) {
            try {
                ch.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void putQuiet(JSONObject obj, String key, Object val) {
        try {
            obj.put(key, val);
        } catch (Exception ignored) {
        }
    }

    private static String sanitizeName(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String clean = raw.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.isEmpty()) {
            return fallback;
        }
        return clean.length() > 28 ? clean.substring(0, 28) : clean;
    }

    private void bumpRevision() {
        revision++;
    }
}
