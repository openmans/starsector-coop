package coop.net;

import java.util.Locale;

/**
 * Text handshake protocol for the optional CGNAT TCP relay ({@code coop.relayAddress}, Item 5).
 *
 * <p>Kept pure and sandbox-safe inside {@code coop.net} so both the mod client and the standalone
 * {@code CoopRelayServer} (in {@code coop-launcher.jar}) speak the exact same handshake lines:
 * <pre>
 *   Host  -&gt; Relay: RELAY_REGISTER &lt;roomCode&gt; &lt;token&gt;\n
 *   Relay -&gt; Host : RELAY_OK\n
 *   Guest -&gt; Relay: RELAY_JOIN &lt;roomCode&gt; &lt;token&gt;\n
 *   Relay -&gt; Guest: RELAY_OK\n
 * </pre>
 * After {@code RELAY_OK}, the relay transparently bridges raw TCP bytes between paired sockets.
 */
public final class CoopRelayProtocol {

    public static final String CMD_REGISTER = "RELAY_REGISTER";
    public static final String CMD_JOIN = "RELAY_JOIN";
    public static final String RESP_OK = "RELAY_OK";
    public static final String RESP_ERR = "RELAY_ERR";
    public static final int DEFAULT_RELAY_PORT = 7790;

    public record Endpoint(String host, int port) {
    }

    public record Command(String verb, String roomCode, String token) {
    }

    private CoopRelayProtocol() {
    }

    /** Parses {@code host:port} (or {@code host} defaulting to {@value #DEFAULT_RELAY_PORT}). */
    public static Endpoint parseAddress(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        int colon = trimmed.lastIndexOf(':');
        if (colon < 0) {
            return new Endpoint(trimmed, DEFAULT_RELAY_PORT);
        }
        String host = trimmed.substring(0, colon).trim();
        if (host.isEmpty()) {
            return null;
        }
        try {
            int port = Integer.parseInt(trimmed.substring(colon + 1).trim());
            if (port < 1 || port > 65535) {
                return null;
            }
            return new Endpoint(host, port);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static String formatRegister(String roomCode, String token) {
        return CMD_REGISTER + " " + cleanToken(roomCode) + " " + cleanToken(token) + "\n";
    }

    public static String formatJoin(String roomCode, String token) {
        return CMD_JOIN + " " + cleanToken(roomCode) + " " + cleanToken(token) + "\n";
    }

    public static String formatOk() {
        return RESP_OK + "\n";
    }

    public static String formatError(String reason) {
        return RESP_ERR + " " + cleanToken(reason) + "\n";
    }

    public static Command parseCommand(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 2) {
            return null;
        }
        String verb = parts[0].toUpperCase(Locale.ROOT);
        if (!CMD_REGISTER.equals(verb) && !CMD_JOIN.equals(verb)) {
            return null;
        }
        String room = cleanToken(parts[1]);
        String token = parts.length >= 3 ? cleanToken(parts[2]) : "";
        return room.isEmpty() ? null : new Command(verb, room, token);
    }

    private static String cleanToken(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("[^A-Za-z0-9._:-]", "");
    }
}
