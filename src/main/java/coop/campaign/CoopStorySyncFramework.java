package coop.campaign;

import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import coop.handshake.CoopChecksum;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Host-authoritative storyline {@code MemoryAPI} flag synchronization and divergence verification
 * framework (Item 4) covering Galatia Academy ({@code $ga*}) and Sindrian Diktat / Usurpers
 * ({@code $sd*}, {@code $usurpers*}, {@code $lionsGuard*}).
 *
 * <p>Provides both the live wire codec ({@code STORY_STATE}) and a pure diff/divergence verifier
 * so functional tests and in-game sync share the exact same prefix whitelist, primitive-value
 * filter, and type preservation.
 *
 * <p><b>Safety filters:</b>
 * <ul>
 *   <li>Only scalar primitive values ({@code Boolean}, {@code Integer}, {@code Long}, finite
 *       {@code Float}/{@code Double}, {@code String}) are ever captured or overwritten. Starsector
 *       stores live Java mission/entity handles under keys such as {@code $gaATG_ref} and
 *       {@code $sdtu_ref}; serializing those as strings would corrupt guest memory.</li>
 *   <li>Keys ending with {@code _ref} (case-insensitive) and keys owned by dedicated sync paths
 *       ({@code $gatesActive}, owned by {@code GATE_ACTIVATED}) are excluded explicitly.</li>
 * </ul>
 */
public final class CoopStorySyncFramework {

    /** Whitelisted campaign storyline flag prefixes synced from host to guests. */
    public static final List<String> DEFAULT_PREFIXES = List.of(
            "$ga",
            "$galatia",
            "$sd",
            "$usurpers",
            "$lionsGuard");

    /** Lowercased keys owned by other sync authorities and therefore excluded from STORY_STATE. */
    public static final Set<String> EXCLUDED_KEYS = Set.of("$gatesactive");

    /** Hard upper bound on captured flags so a rogue mod cannot blow up TCP framing. */
    public static final int MAX_FLAGS = 2048;

    public enum ValueKind {
        BOOL,
        INT,
        LONG,
        FLOAT,
        STRING
    }

    public record StoryFlag(String key, ValueKind kind, String encodedValue) {
        public StoryFlag {
            key = key == null ? "" : key.trim();
            Objects.requireNonNull(kind, "kind");
            encodedValue = encodedValue == null ? "" : encodedValue;
        }

        public Object toJavaValue() {
            return switch (kind) {
                case BOOL -> Boolean.parseBoolean(encodedValue);
                case INT -> Integer.parseInt(encodedValue);
                case LONG -> Long.parseLong(encodedValue);
                case FLOAT -> Float.parseFloat(encodedValue);
                case STRING -> encodedValue;
            };
        }

        /**
         * Converts a syncable scalar value into a {@link StoryFlag}, or returns {@code null} when
         * {@code value} is not a safe primitive/scalar (for example a {@code HubMission} or entity
         * object handle stored in Starsector's {@link MemoryAPI}).
         */
        public static StoryFlag tryOf(String key, Object value) {
            if (!isSyncableValue(value)) {
                return null;
            }
            if (value instanceof Boolean b) {
                return new StoryFlag(key, ValueKind.BOOL, Boolean.toString(b));
            }
            if (value instanceof Integer i) {
                return new StoryFlag(key, ValueKind.INT, Integer.toString(i));
            }
            if (value instanceof Long l) {
                return new StoryFlag(key, ValueKind.LONG, Long.toString(l));
            }
            if (value instanceof Float f) {
                return new StoryFlag(key, ValueKind.FLOAT, Float.toString(f));
            }
            if (value instanceof Double d) {
                return new StoryFlag(key, ValueKind.FLOAT, Float.toString(d.floatValue()));
            }
            return new StoryFlag(key, ValueKind.STRING, (String) value);
        }

        public static StoryFlag of(String key, Object value) {
            StoryFlag scalar = tryOf(key, value);
            if (scalar != null) {
                return scalar;
            }
            return new StoryFlag(key, ValueKind.STRING, String.valueOf(value));
        }
    }

    public record DivergenceReport(List<String> missingOnGuest,
                                   List<String> mismatchedKeys,
                                   List<String> extraOnGuest) {
        public boolean isSynced() {
            return missingOnGuest.isEmpty() && mismatchedKeys.isEmpty() && extraOnGuest.isEmpty();
        }
    }

    /** Pure memory seam used both by the live {@link MemoryAPI} adapter and headless tests. */
    public interface FlagMemory {
        Map<String, Object> entries();

        void set(String key, Object value);

        void unset(String key);
    }

    private CoopStorySyncFramework() {
    }

    /**
     * Returns {@code true} only for scalar values that are safe to serialize over {@code STORY_STATE}
     * and write back into Starsector's {@link MemoryAPI}. Arbitrary Java objects (such as
     * {@code HubMission}, {@code PersonAPI}, or entity references) return {@code false}.
     */
    public static boolean isSyncableValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long
                || value instanceof String) {
            return true;
        }
        if (value instanceof Float f) {
            return Float.isFinite(f);
        }
        if (value instanceof Double d) {
            return Double.isFinite(d);
        }
        return false;
    }

    public static boolean isTrackedKey(String key) {
        return isTrackedKey(key, DEFAULT_PREFIXES);
    }

    public static boolean isTrackedKey(String key, List<String> prefixes) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        if (EXCLUDED_KEYS.contains(lower) || lower.endsWith("_ref")) {
            return false;
        }
        for (String prefix : prefixes) {
            if (lower.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Captures all whitelisted scalar storyline flags from {@code memory} in deterministic key order. */
    public static Map<String, StoryFlag> capture(FlagMemory memory) {
        return capture(memory, DEFAULT_PREFIXES);
    }

    public static Map<String, StoryFlag> capture(FlagMemory memory, List<String> prefixes) {
        if (memory == null) {
            return Map.of();
        }
        TreeMap<String, StoryFlag> out = new TreeMap<>();
        for (Map.Entry<String, Object> e : memory.entries().entrySet()) {
            if (!isTrackedKey(e.getKey(), prefixes)) {
                continue;
            }
            StoryFlag flag = StoryFlag.tryOf(e.getKey(), e.getValue());
            if (flag == null) {
                continue;
            }
            out.put(e.getKey(), flag);
            if (out.size() >= MAX_FLAGS) {
                break;
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** Deterministic SHA-256 digest of the encoded flag map, used for change-only broadcasting. */
    public static String contentHash(Map<String, StoryFlag> flags) {
        return CoopChecksum.sha256Text(encode(flags));
    }

    /**
     * Applies authoritative {@code hostFlags} onto {@code guestMemory}, unsetting tracked keys that
     * were cleared on the host and updating changed/missing keys. Returns the number of writes.
     */
    public static int applyAuthoritative(Map<String, StoryFlag> hostFlags, FlagMemory guestMemory) {
        return applyAuthoritative(hostFlags, guestMemory, DEFAULT_PREFIXES);
    }

    public static int applyAuthoritative(Map<String, StoryFlag> hostFlags,
                                         FlagMemory guestMemory,
                                         List<String> prefixes) {
        Objects.requireNonNull(guestMemory, "guestMemory");
        Map<String, StoryFlag> target = hostFlags == null ? Map.of() : hostFlags;
        Map<String, StoryFlag> current = capture(guestMemory, prefixes);
        int writes = 0;

        for (String existingKey : current.keySet()) {
            if (!target.containsKey(existingKey)) {
                guestMemory.unset(existingKey);
                writes++;
            }
        }
        for (Map.Entry<String, StoryFlag> e : target.entrySet()) {
            StoryFlag prev = current.get(e.getKey());
            if (prev == null || !prev.equals(e.getValue())) {
                guestMemory.set(e.getKey(), e.getValue().toJavaValue());
                writes++;
            }
        }
        return writes;
    }

    /** Computes exact flag divergence between host and guest storyline memories. */
    public static DivergenceReport diff(Map<String, StoryFlag> hostFlags,
                                        Map<String, StoryFlag> guestFlags) {
        Map<String, StoryFlag> h = hostFlags == null ? Map.of() : hostFlags;
        Map<String, StoryFlag> g = guestFlags == null ? Map.of() : guestFlags;
        List<String> missing = new ArrayList<>();
        List<String> mismatched = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        for (Map.Entry<String, StoryFlag> e : h.entrySet()) {
            StoryFlag gf = g.get(e.getKey());
            if (gf == null) {
                missing.add(e.getKey());
            } else if (!gf.equals(e.getValue())) {
                mismatched.add(e.getKey());
            }
        }
        for (String gk : g.keySet()) {
            if (!h.containsKey(gk)) {
                extra.add(gk);
            }
        }
        return new DivergenceReport(
                Collections.unmodifiableList(missing),
                Collections.unmodifiableList(mismatched),
                Collections.unmodifiableList(extra));
    }

    public static String encode(Map<String, StoryFlag> flags) {
        if (flags == null || flags.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(flags.size() * 32);
        for (StoryFlag flag : flags.values()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(CoopDelimited.field(flag.key())).append('|')
                    .append(flag.kind().name()).append('|')
                    .append(CoopDelimited.field(flag.encodedValue()));
        }
        return sb.toString();
    }

    public static Map<String, StoryFlag> decode(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        Map<String, StoryFlag> out = new LinkedHashMap<>();
        for (String line : body.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            List<String> f = CoopDelimited.split(line);
            if (f.size() != 3) {
                continue;
            }
            try {
                String key = f.get(0);
                if (!isTrackedKey(key)) {
                    continue;
                }
                ValueKind kind = ValueKind.valueOf(f.get(1));
                String val = f.get(2);
                StoryFlag flag = new StoryFlag(key, kind, val);
                // Validate numeric parsing upfront so applyAuthoritative never throws on a bad frame.
                Object parsed = flag.toJavaValue();
                if (!isSyncableValue(parsed)) {
                    continue;
                }
                out.put(key, flag);
                if (out.size() >= MAX_FLAGS) {
                    break;
                }
            } catch (IllegalArgumentException ignored) {
                // skip unknown kind or malformed numeric value
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** Wraps Starsector's global sector {@link MemoryAPI} into {@link FlagMemory}. */
    public static FlagMemory wrapMemory(MemoryAPI memory) {
        Objects.requireNonNull(memory, "memory");
        return new FlagMemory() {
            @Override
            public Map<String, Object> entries() {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (String key : memory.getKeys()) {
                    if (isTrackedKey(key)) {
                        Object v = memory.get(key);
                        if (isSyncableValue(v)) {
                            copy.put(key, v);
                        }
                    }
                }
                return copy;
            }

            @Override
            public void set(String key, Object value) {
                memory.set(key, value);
            }

            @Override
            public void unset(String key) {
                memory.unset(key);
            }
        };
    }
}
