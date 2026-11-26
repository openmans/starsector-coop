package coop.campaign;

import coop.handshake.CoopChecksum;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits oversized {@code MARKET_SNAPSHOT} payloads (for example a shared storage locker holding
 * dozens of refitted ships past the 1 MB TCP frame cap) into bounded fragments carried inside
 * ordinary {@code MARKET_SNAPSHOT} messages, and reassembles them atomically on the guest.
 *
 * <p>Format: {@code MKT_PART1:<transferId>:<index>:<count>:<totalChars>:<sha256>:<piece>}.
 * Payloads at or below {@link #CHUNK_THRESHOLD_BYTES} are returned unchanged so normal markets
 * pay zero overhead and stay wire-identical.
 *
 * <p>No {@code java.io}, {@code java.nio.file}, or {@code java.lang.reflect} — safe inside
 * Starsector's script sandbox.
 */
public final class CoopMarketSnapshotChunker {

    public static final String PREFIX = "MKT_PART1:";
    /** Payloads above this UTF-8 size are fragmented to leave envelope/escaping headroom under 1 MB. */
    public static final int CHUNK_THRESHOLD_BYTES = 768 * 1024;
    /** Characters per chunk body; stays well below the 1 MB frame limit even after JSON escaping. */
    public static final int CHUNK_BODY_CHARS = 512 * 1024;
    /** Maximum number of chunks per single submarket snapshot (up to ~16 MiB). */
    public static final int MAX_PARTS = 32;
    /** Maximum assembled character length accepted by the receiver. */
    public static final int MAX_TOTAL_CHARS = MAX_PARTS * CHUNK_BODY_CHARS;
    /** Incomplete assemblies older than this are discarded so memory stays bounded. */
    public static final long ASSEMBLY_TIMEOUT_MILLIS = 60_000L;
    /** Maximum simultaneous in-flight chunked snapshots across markets/submarkets. */
    public static final int MAX_PENDING_ASSEMBLIES = 16;

    private CoopMarketSnapshotChunker() {
    }

    /** True when {@code stock} is a fragment emitted by {@link #fragmentIfNeeded}. */
    public static boolean isChunk(String stock) {
        return stock != null && stock.startsWith(PREFIX);
    }

    /**
     * Fragments {@code encodedStock} when it exceeds {@link #CHUNK_THRESHOLD_BYTES}.
     *
     * @return single-element list containing {@code encodedStock} when no chunking is needed;
     *         multiple {@code MKT_PART1:...} strings when chunked; empty list if the payload
     *         exceeds the {@link #MAX_TOTAL_CHARS} safety cap.
     */
    public static List<String> fragmentIfNeeded(long transferId, String encodedStock) {
        return fragmentIfNeeded(transferId, encodedStock, CHUNK_THRESHOLD_BYTES, CHUNK_BODY_CHARS);
    }

    /** Package-private overload with configurable thresholds for unit tests. */
    static List<String> fragmentIfNeeded(long transferId, String encodedStock,
                                         int thresholdBytes, int chunkBodyChars) {
        String body = encodedStock == null ? "" : encodedStock;
        int utf8Bytes = body.getBytes(StandardCharsets.UTF_8).length;
        if (utf8Bytes <= thresholdBytes) {
            return List.of(body);
        }
        int totalChars = body.length();
        int step = Math.max(1, chunkBodyChars);
        int count = (totalChars + step - 1) / step;
        if (count > MAX_PARTS || totalChars > MAX_TOTAL_CHARS) {
            return List.of();
        }
        String sha = CoopChecksum.sha256Text(body);
        List<String> parts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int start = i * step;
            int end = Math.min(totalChars, start + step);
            String piece = body.substring(start, end);
            parts.add(PREFIX + transferId + ":" + i + ":" + count + ":" + totalChars + ":" + sha + ":" + piece);
        }
        return parts;
    }

    /** Parsed view of one {@code MKT_PART1:...} header and payload piece. */
    public record Part(long transferId, int index, int count, int totalChars, String sha256, String piece) {
    }

    /** Parses a chunk string, or returns {@code null} when malformed or out of bounds. */
    public static Part parsePart(String raw) {
        if (!isChunk(raw)) {
            return null;
        }
        int pos = PREFIX.length();
        int c1 = raw.indexOf(':', pos);
        int c2 = c1 < 0 ? -1 : raw.indexOf(':', c1 + 1);
        int c3 = c2 < 0 ? -1 : raw.indexOf(':', c2 + 1);
        int c4 = c3 < 0 ? -1 : raw.indexOf(':', c3 + 1);
        int c5 = c4 < 0 ? -1 : raw.indexOf(':', c4 + 1);
        if (c5 < 0) {
            return null;
        }
        try {
            long transferId = Long.parseLong(raw.substring(pos, c1));
            int index = Integer.parseInt(raw.substring(c1 + 1, c2));
            int count = Integer.parseInt(raw.substring(c2 + 1, c3));
            int totalChars = Integer.parseInt(raw.substring(c3 + 1, c4));
            String sha = raw.substring(c4 + 1, c5);
            String piece = raw.substring(c5 + 1);
            if (count < 2 || count > MAX_PARTS || index < 0 || index >= count
                    || totalChars <= 0 || totalChars > MAX_TOTAL_CHARS
                    || sha.isEmpty()) {
                return null;
            }
            return new Part(transferId, index, count, totalChars, sha, piece);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Stateful guest-side reassembler for chunked {@code MARKET_SNAPSHOT} batches. Bounded in both
     * age and concurrent entry count; drops mismatched/corrupted transfers atomically.
     */
    public static final class Assembler {

        private final Map<String, PendingAssembly> pending = new LinkedHashMap<>();

        /**
         * Feeds one incoming {@code stock} field. Unchunked strings are returned immediately.
         * Chunked parts return {@code null} until every part of the transfer arrives and validates
         * against the declared length and SHA-256 digest, at which point the complete stock string
         * is returned and the assembly entry is cleared.
         */
        public String accept(String marketId, String submarketId, String rawStock, long nowMillis) {
            expireStale(nowMillis);
            String key = (marketId == null ? "" : marketId) + "|" + (submarketId == null ? "" : submarketId);
            if (!isChunk(rawStock)) {
                pending.remove(key);
                return rawStock == null ? "" : rawStock;
            }
            Part part = parsePart(rawStock);
            if (part == null) {
                pending.remove(key);
                return null;
            }
            PendingAssembly entry = pending.get(key);
            if (entry == null || entry.transferId != part.transferId()
                    || entry.count != part.count()
                    || entry.totalChars != part.totalChars()
                    || !entry.sha256.equals(part.sha256())) {
                if (entry == null && pending.size() >= MAX_PENDING_ASSEMBLIES) {
                    Iterator<String> oldest = pending.keySet().iterator();
                    if (oldest.hasNext()) {
                        oldest.next();
                        oldest.remove();
                    }
                }
                entry = new PendingAssembly(part.transferId(), part.count(), part.totalChars(),
                        part.sha256(), nowMillis);
                pending.put(key, entry);
            }
            String existing = entry.parts[part.index()];
            if (existing != null) {
                if (!existing.equals(part.piece())) {
                    pending.remove(key);
                    return null;
                }
            } else {
                entry.parts[part.index()] = part.piece();
                entry.received++;
            }
            if (entry.received < entry.count) {
                return null;
            }
            pending.remove(key);
            StringBuilder joined = new StringBuilder(entry.totalChars);
            for (String piece : entry.parts) {
                if (piece == null) {
                    return null;
                }
                joined.append(piece);
            }
            if (joined.length() != entry.totalChars) {
                return null;
            }
            String assembled = joined.toString();
            if (!entry.sha256.equals(CoopChecksum.sha256Text(assembled))) {
                return null;
            }
            return assembled;
        }

        public int pendingCount() {
            return pending.size();
        }

        public void reset() {
            pending.clear();
        }

        private void expireStale(long nowMillis) {
            if (pending.isEmpty()) {
                return;
            }
            Iterator<Map.Entry<String, PendingAssembly>> it = pending.entrySet().iterator();
            while (it.hasNext()) {
                PendingAssembly entry = it.next().getValue();
                if (nowMillis - entry.startedAtMillis > ASSEMBLY_TIMEOUT_MILLIS) {
                    it.remove();
                }
            }
        }

        private static final class PendingAssembly {
            final long transferId;
            final int count;
            final int totalChars;
            final String sha256;
            final long startedAtMillis;
            final String[] parts;
            int received;

            PendingAssembly(long transferId, int count, int totalChars, String sha256, long startedAtMillis) {
                this.transferId = transferId;
                this.count = count;
                this.totalChars = totalChars;
                this.sha256 = sha256;
                this.startedAtMillis = startedAtMillis;
                this.parts = new String[count];
            }
        }
    }
}
