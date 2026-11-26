package coop.compat;

import com.fs.starfarer.api.Global;
import coop.campaign.CoopDelimited;
import coop.util.CoopLog;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Objects;

/**
 * Optional Nexerelin compatibility adapter (Item 5).
 *
 * <p>Completely dormant when Nexerelin ({@code nexerelin}) is not enabled in the current install.
 * When Nexerelin is active, uses sandbox-safe {@link MethodHandles} (zero {@code java.lang.reflect})
 * to replicate host-authoritative Nexerelin diplomacy, war/peace, alliance, and market-invasion
 * state transitions across {@code NEX_EVENT}.
 */
public final class CoopNexerelinAdapter {

    public static final String NEXERELIN_MOD_ID = "nexerelin";

    public enum EventKind {
        DIPLOMACY_REL,
        WAR_DECLARED,
        PEACE_TREATY,
        ALLIANCE_CHANGE,
        MARKET_CAPTURED
    }

    public record NexEvent(EventKind kind, String factionA, String factionB,
                           String marketId, float value, String note) {
        public NexEvent {
            Objects.requireNonNull(kind, "kind");
            factionA = clean(factionA);
            factionB = clean(factionB);
            marketId = clean(marketId);
            note = clean(note);
        }
    }

    /** Pure world seam so Nexerelin event application is unit-tested without Nexerelin installed. */
    public interface NexWorld {
        boolean isNexerelinEnabled();

        boolean setFactionRelationship(String factionA, String factionB, float newRel);

        boolean setMarketOwner(String marketId, String newFactionId);
    }

    private static volatile boolean handlesProbed;
    private static volatile MethodHandle setRelHandle;

    private CoopNexerelinAdapter() {
    }

    /** True when the {@code nexerelin} mod is enabled in the live Starsector install. */
    public static boolean isPresent() {
        try {
            return Global.getSettings() != null
                    && Global.getSettings().getModManager() != null
                    && Global.getSettings().getModManager().isModEnabled(NEXERELIN_MOD_ID);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /** Encodes a {@link NexEvent} for {@code NEX_EVENT}. */
    public static String encode(NexEvent event) {
        Objects.requireNonNull(event, "event");
        return event.kind().name() + "|"
                + CoopDelimited.field(event.factionA()) + "|"
                + CoopDelimited.field(event.factionB()) + "|"
                + CoopDelimited.field(event.marketId()) + "|"
                + event.value() + "|"
                + CoopDelimited.field(event.note());
    }

    /** Decodes a {@code NEX_EVENT} wire body back into a {@link NexEvent}, or {@code null}. */
    public static NexEvent decode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        List<String> f = CoopDelimited.split(body);
        if (f.size() < 6) {
            return null;
        }
        try {
            EventKind kind = EventKind.valueOf(f.get(0).trim());
            float val = Float.parseFloat(f.get(4).trim());
            return new NexEvent(kind, f.get(1), f.get(2), f.get(3), val, f.get(5));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Applies a host-authoritative {@link NexEvent} onto {@code world} when Nexerelin is active. */
    public static boolean apply(NexEvent event, NexWorld world) {
        if (event == null || world == null || !world.isNexerelinEnabled()) {
            return false;
        }
        return switch (event.kind()) {
            case DIPLOMACY_REL -> world.setFactionRelationship(event.factionA(), event.factionB(), event.value());
            case WAR_DECLARED -> world.setFactionRelationship(event.factionA(), event.factionB(), -1.0f);
            case PEACE_TREATY -> world.setFactionRelationship(event.factionA(), event.factionB(), 0.0f);
            case ALLIANCE_CHANGE -> world.setFactionRelationship(event.factionA(), event.factionB(), Math.max(0.5f, event.value()));
            case MARKET_CAPTURED -> world.setMarketOwner(event.marketId(), event.factionA());
        };
    }

    /** Live Starsector + Nexerelin world adapter (gracefully no-ops if Nexerelin classes are absent). */
    public static NexWorld liveWorld() {
        return new NexWorld() {
            @Override
            public boolean isNexerelinEnabled() {
                return isPresent();
            }

            @Override
            public boolean setFactionRelationship(String factionA, String factionB, float newRel) {
                try {
                    if (Global.getSector() == null) {
                        return false;
                    }
                    var fa = Global.getSector().getFaction(factionA);
                    if (fa == null || factionB.isEmpty()) {
                        return false;
                    }
                    fa.setRelationship(factionB, newRel);
                    invokeNexDiplomacyHook(factionA, factionB, newRel);
                    return true;
                } catch (RuntimeException | LinkageError ex) {
                    CoopLog.warn(CoopNexerelinAdapter.class, "Failed to apply Nexerelin relationship", ex);
                    return false;
                }
            }

            @Override
            public boolean setMarketOwner(String marketId, String newFactionId) {
                try {
                    if (Global.getSector() == null || Global.getSector().getEconomy() == null) {
                        return false;
                    }
                    var market = Global.getSector().getEconomy().getMarket(marketId);
                    if (market == null || newFactionId.isEmpty()) {
                        return false;
                    }
                    market.setFactionId(newFactionId);
                    return true;
                } catch (RuntimeException | LinkageError ex) {
                    CoopLog.warn(CoopNexerelinAdapter.class, "Failed to apply Nexerelin market capture", ex);
                    return false;
                }
            }
        };
    }

    private static void invokeNexDiplomacyHook(String factionA, String factionB, float newRel) {
        if (!handlesProbed) {
            handlesProbed = true;
            try {
                Class<?> mgr = Class.forName("exerelin.campaign.DiplomacyManager",
                        false, CoopNexerelinAdapter.class.getClassLoader());
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                setRelHandle = lookup.findStatic(mgr, "adjustRelations",
                        MethodType.methodType(void.class, String.class, String.class, float.class));
            } catch (Throwable ignored) {
                setRelHandle = null;
            }
        }
        if (setRelHandle != null) {
            try {
                setRelHandle.invokeExact(factionA, factionB, 0f);
            } catch (Throwable ignored) {
                // vanilla setRelationship already applied above
            }
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }
}
