package coop.campaign;

import com.fs.starfarer.api.campaign.rules.MemoryAPI;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Host-authoritative storyline {@code MemoryAPI} flag synchronization and divergence verification
 * framework (Item 4) covering Galatia Academy ({@code $ga*}) and Sindrian Diktat / Usurpers
 * ({@code $sd*}, {@code $usurpers*}, {@code $lionsGuard*}).
 *
 * <p>Provides both the live wire codec ({@code STORY_STATE}) and a pure diff/divergence verifier
 * so functional tests and in-game sync share the exact same prefix whitelist and type preservation.
 */
public final class CoopStorySyncFramework {

    /** Whitelisted campaign storyline flag prefixes synced from host to guests. */
    public static final List<String> DEFAULT_PREFIXES = List.of(
            "$ga",
            "$galatia",
            "$sd",
            "$usurpers",
            "$lionsGuard");

    public enum ValueKind {
        BOOL,
        INT,
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
                case FLOAT -> Float.parseFloat(encodedValue);
                case STRING -> encodedValue;
            };
        }

        public static StoryFlag of(String key, Object value) {
            if (value instanceof Boolean b) {
                return new StoryFlag(key, ValueKind.BOOL, Boolean.toString(b));
            }
            if (value instanceof Integer i) {
                return new StoryFlag(key, ValueKind.INT, Integer.toString(i));
            }
            if (value instanceof Float f) {
                return new StoryFlag(key, ValueKind.FLOAT, Float.toString(f));
            }
            if (value instanceof Double d) {
                return new StoryFlag(key, ValueKind.FLOAT, Float.toString(d.floatValue()));
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

    public static boolean isTrackedKey(String key) {
        return isTrackedKey(key, DEFAULT_PREFIXES);
    }

    public static boolean isTrackedKey(String key, List<String> prefixes) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        for (String prefix : prefixes) {
            if (lower.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Captures all whitelisted storyline flags from {@code memory} in deterministic key order. */
    public static Map<String, StoryFlag> capture(FlagMemory memory) {
        return capture(memory, DEFAULT_PREFIXES);
    }

    public static Map<String, StoryFlag> capture(FlagMemory memory, List<String> prefixes) {
        if (memory == null) {
            return Map.of();
        }
        TreeMap<String, StoryFlag> out = new TreeMap<>();
        for (Map.Entry<String, Object> e : memory.entries().entrySet()) {
            if (e.getValue() != null && isTrackedKey(e.getKey(), prefixes)) {
                out.put(e.getKey(), StoryFlag.of(e.getKey(), e.getValue()));
            }
        }
        return Collections.unmodifiableMap(out);
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
                ValueKind kind = ValueKind.valueOf(f.get(1));
                String val = f.get(2);
                out.put(key, new StoryFlag(key, kind, val));
            } catch (IllegalArgumentException ignored) {
                // skip unknown kind
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
                        if (v != null) {
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
