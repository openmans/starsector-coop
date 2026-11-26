package coop.chat;

import coop.campaign.CoopDelimited;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-game co-op text chat service (Item 5).
 *
 * <p>Maintains a bounded ring buffer of the last {@value #MAX_HISTORY} messages, enforces a
 * {@value #MAX_TEXT_LENGTH}-character message limit, sanitizes control characters, and applies a
 * per-sender token/window rate limit ({@value #MAX_PER_WINDOW} messages per
 * {@value #RATE_WINDOW_MILLIS} ms) so chat can never flood the reliable TCP stream.
 */
public final class CoopChatService {

    public static final int MAX_HISTORY = 100;
    public static final int MAX_TEXT_LENGTH = 240;
    public static final int MAX_PER_WINDOW = 5;
    public static final long RATE_WINDOW_MILLIS = 3_000L;

    public record ChatMessage(String senderId, String senderName, String text, long sentAtMillis) {
        public ChatMessage {
            senderId = senderId == null ? "" : senderId.trim();
            senderName = senderName == null || senderName.isBlank() ? "Pilot" : senderName.trim();
            text = sanitize(text);
        }
    }

    private static volatile CoopChatService active;

    private final Deque<ChatMessage> history = new ArrayDeque<>();
    private final Map<String, Deque<Long>> senderWindows = new HashMap<>();

    public static void install(CoopChatService service) {
        active = service;
    }

    public static void uninstall() {
        active = null;
    }

    public static CoopChatService active() {
        return active;
    }

    /**
     * Records a chat message if non-blank and within the sender's rate limit. Returns the accepted
     * sanitized {@link ChatMessage}, or {@code null} if blank or rate-limited.
     */
    public synchronized ChatMessage accept(String senderId, String senderName,
                                           String rawText, long nowMillis) {
        String clean = sanitize(rawText);
        if (clean.isEmpty()) {
            return null;
        }
        String key = senderId == null ? "" : senderId.trim();
        Deque<Long> timestamps = senderWindows.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!timestamps.isEmpty() && nowMillis - timestamps.peekFirst() >= RATE_WINDOW_MILLIS) {
            timestamps.pollFirst();
        }
        if (timestamps.size() >= MAX_PER_WINDOW) {
            return null;
        }
        timestamps.addLast(nowMillis);

        ChatMessage msg = new ChatMessage(key, senderName, clean, nowMillis);
        history.addLast(msg);
        while (history.size() > MAX_HISTORY) {
            history.pollFirst();
        }
        return msg;
    }

    public synchronized List<ChatMessage> recent(int limit) {
        if (limit <= 0 || history.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> all = new ArrayList<>(history);
        int from = Math.max(0, all.size() - limit);
        return List.copyOf(all.subList(from, all.size()));
    }

    public synchronized void clear() {
        history.clear();
        senderWindows.clear();
    }

    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), MAX_TEXT_LENGTH));
        for (int i = 0; i < raw.length() && sb.length() < MAX_TEXT_LENGTH; i++) {
            char c = raw.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t') {
                sb.append(' ');
            } else if (c >= 0x20 && c != 0x7F) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    public static String encode(ChatMessage msg) {
        return CoopDelimited.field(msg.senderId()) + "|"
                + CoopDelimited.field(msg.senderName()) + "|"
                + msg.sentAtMillis() + "|"
                + CoopDelimited.field(msg.text());
    }

    public static ChatMessage decode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        List<String> f = CoopDelimited.split(body);
        if (f.size() < 4) {
            return null;
        }
        long ts = 0L;
        try {
            ts = Long.parseLong(f.get(2).trim());
        } catch (NumberFormatException ignored) {
            // keep 0
        }
        ChatMessage msg = new ChatMessage(f.get(0), f.get(1), f.get(3), ts);
        return msg.text().isEmpty() ? null : msg;
    }
}
