package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import coop.handshake.CoopChecksum;
import coop.util.CoopLog;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Host-authoritative runtime authority that polls whitelisted campaign storyline flags
 * ({@link CoopStorySyncFramework}) on the host and broadcasts {@code STORY_STATE} snapshots to
 * connected guests on session start, reconnect/mid-session join, and whenever any tracked flag
 * changes.
 *
 * <p>Mirrors {@link CoopBountyAuthority}'s poll/hash/rearm lifecycle:
 * <ul>
 *   <li>Throttled to {@link #POLL_MILLIS} on the host campaign thread (no background threads).</li>
 *   <li>Always emits one baseline snapshot per session or {@link #rearmBroadcast()} — even when
 *       the host currently has zero tracked flags — so a reconnecting or newly joined guest clears
 *       any stale flags it may hold.</li>
 *   <li>Silent when unchanged: subsequent ticks with the same content hash send zero packets.</li>
 *   <li>Bounded payload size ({@link #MAX_WIRE_CHARS}) so a misbehaving mod cannot exceed TCP
 *       frame limits.</li>
 * </ul>
 */
public final class CoopStorySyncAuthority {

    /** Host poll cadence for storyline memory flags. */
    public static final long POLL_MILLIS = 2_000L;

    /** Maximum encoded STORY_STATE body size accepted for outbound broadcast. */
    public static final int MAX_WIRE_CHARS = 128 * 1024;

    private final LongSupplier clockMillis;
    private final Supplier<CoopStorySyncFramework.FlagMemory> hostMemory;
    private final Supplier<CoopStorySyncFramework.FlagMemory> guestMemory;
    private final Consumer<String> broadcaster;

    private boolean baselineSent;
    private String lastSentHash;
    private long nextPollAtMillis;
    private boolean oversizeWarned;

    public CoopStorySyncAuthority(LongSupplier clockMillis, Consumer<String> broadcaster) {
        this(clockMillis, CoopStorySyncAuthority::liveSectorMemory,
                CoopStorySyncAuthority::liveSectorMemory, broadcaster);
    }

    public CoopStorySyncAuthority(LongSupplier clockMillis,
                                  Supplier<CoopStorySyncFramework.FlagMemory> hostMemory,
                                  Supplier<CoopStorySyncFramework.FlagMemory> guestMemory,
                                  Consumer<String> broadcaster) {
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.hostMemory = Objects.requireNonNull(hostMemory, "hostMemory");
        this.guestMemory = Objects.requireNonNull(guestMemory, "guestMemory");
        this.broadcaster = Objects.requireNonNull(broadcaster, "broadcaster");
    }

    /** Host tick: poll tracked storyline flags and broadcast {@code STORY_STATE} on change or rearm. */
    public boolean tickHost() {
        long now = clockMillis.getAsLong();
        if (now < nextPollAtMillis) {
            return false;
        }
        nextPollAtMillis = now + POLL_MILLIS;
        try {
            CoopStorySyncFramework.FlagMemory memory = hostMemory.get();
            if (memory == null) {
                return false;
            }
            Map<String, CoopStorySyncFramework.StoryFlag> flags = CoopStorySyncFramework.capture(memory);
            String encoded = CoopStorySyncFramework.encode(flags);
            if (encoded.length() > MAX_WIRE_CHARS) {
                if (!oversizeWarned) {
                    oversizeWarned = true;
                    CoopLog.warn(CoopStorySyncAuthority.class,
                            "Coop STORY_STATE skipped: payload too large (" + encoded.length()
                                    + " chars > " + MAX_WIRE_CHARS + ")");
                }
                return false;
            }
            oversizeWarned = false;
            String hash = CoopChecksum.sha256Text(encoded);
            if (baselineSent && hash.equals(lastSentHash)) {
                return false;
            }
            broadcaster.accept(encoded);
            baselineSent = true;
            lastSentHash = hash;
            CoopLog.info(CoopStorySyncAuthority.class,
                    "Coop sent STORY_STATE flags=" + flags.size());
            return true;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopStorySyncAuthority.class, "Failed to poll host story flags", ex);
            return false;
        }
    }

    /** Forces the next host tick to send a fresh full {@code STORY_STATE} snapshot immediately. */
    public void rearmBroadcast() {
        baselineSent = false;
        lastSentHash = null;
        nextPollAtMillis = 0L;
    }

    /** Guest receive path: decodes and applies an authoritative {@code STORY_STATE} payload. */
    public int applyGuestBody(String body) {
        try {
            CoopStorySyncFramework.FlagMemory memory = guestMemory.get();
            if (memory == null) {
                return 0;
            }
            Map<String, CoopStorySyncFramework.StoryFlag> flags = CoopStorySyncFramework.decode(body);
            int writes = CoopStorySyncFramework.applyAuthoritative(flags, memory);
            if (writes > 0) {
                CoopLog.info(CoopStorySyncAuthority.class,
                        "Coop applied STORY_STATE flags=" + flags.size() + " writes=" + writes);
            }
            return writes;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopStorySyncAuthority.class, "Failed to apply STORY_STATE", ex);
            return 0;
        }
    }

    /** Clears transient sync state on session teardown. */
    public void dispose() {
        baselineSent = false;
        lastSentHash = null;
        nextPollAtMillis = 0L;
        oversizeWarned = false;
    }

    private static CoopStorySyncFramework.FlagMemory liveSectorMemory() {
        SectorAPI sector = Global.getSector();
        if (sector == null) {
            return null;
        }
        MemoryAPI memory = sector.getMemoryWithoutUpdate();
        return memory == null ? null : CoopStorySyncFramework.wrapMemory(memory);
    }
}
