package coop.combat;

import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import coop.campaign.CoopDelimited;
import coop.config.CoopOptionsPolicy;
import coop.config.CoopOptionsRegistry;
import coop.util.CoopLog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Interactive partner battle reinforcement coordinator supporting configurable range up to
 * {@value #DEFAULT_JOIN_RANGE_SU} su (up to 10,000 su via {@code coop.allyJoinRange}) without
 * expanding vanilla's global {@code battleJoinRange} for enemy NPC fleets.
 *
 * <p>When a player opens a pre-battle encounter and a partner is in the same star system within
 * {@code coop.allyJoinRange}, the fighting side sends {@code ALLY_JOIN_OFFER}. The partner chooses:
 * <ul>
 *   <li>{@link JoinChoice#JOIN_TACTICAL} — join with their fleet and auto-open the live Tactical
 *       Map ({@code CoopTacticalMapDialogPlugin}) once combat starts so they command their ships.</li>
 *   <li>{@link JoinChoice#JOIN_WINGMAN} — join with their fleet and pilot directly via Wingman
 *       (offered when Wingman is installed and enabled).</li>
 *   <li>{@link JoinChoice#JOIN_AI} — join with their fleet under AI officers without opening the
 *       tactical map.</li>
 *   <li>{@link JoinChoice#DECLINE} — stay out of this battle.</li>
 * </ul>
 */
public final class CoopAllyReinforceCoordinator {

    public static final int MIN_JOIN_RANGE_SU = 700;
    public static final int DEFAULT_JOIN_RANGE_SU = 3000;
    public static final int MAX_JOIN_RANGE_SU = 10000;
    public static final long OFFER_TIMEOUT_MILLIS = 30_000L;

    public enum JoinChoice {
        JOIN_TACTICAL,
        JOIN_WINGMAN,
        JOIN_AI,
        DECLINE;

        public boolean joinsBattle() {
            return this != DECLINE;
        }
    }

    public record JoinOffer(String offerId, String fighterPlayerId, String fighterName,
                            String enemySummary, int distanceSu, boolean wingmanAvailable,
                            long createdAtMillis) {
        public JoinOffer {
            offerId = clean(offerId);
            fighterPlayerId = clean(fighterPlayerId);
            fighterName = fighterName == null || fighterName.isBlank() ? "Partner" : fighterName.trim();
            enemySummary = enemySummary == null || enemySummary.isBlank() ? "hostile fleet" : enemySummary.trim();
            distanceSu = Math.max(0, distanceSu);
        }
    }

    public record JoinReply(String offerId, String partnerPlayerId, JoinChoice choice) {
        public JoinReply {
            offerId = clean(offerId);
            partnerPlayerId = clean(partnerPlayerId);
            Objects.requireNonNull(choice, "choice");
        }
    }

    private final Map<String, JoinOffer> outgoingOffers = new LinkedHashMap<>();
    private boolean autoOpenTacticalMapPending;
    private boolean wingmanDirectControlRequested;
    private long nextOfferCounter;

    /** Reads the active host policy value for {@code coop.allyJoinRange}, clamped to 700..10000. */
    public static int configuredJoinRangeSu() {
        try {
            String raw = CoopOptionsPolicy.appliedOrDefault(CoopOptionsRegistry.ALLY_JOIN_RANGE);
            return clampRange(Integer.parseInt(raw.trim()));
        } catch (RuntimeException | LinkageError ex) {
            return DEFAULT_JOIN_RANGE_SU;
        }
    }

    public static int clampRange(int su) {
        return Math.max(MIN_JOIN_RANGE_SU, Math.min(MAX_JOIN_RANGE_SU, su));
    }

    /** True when a partner in the same location is within the configured reinforcement radius. */
    public static boolean isWithinJoinRange(float distanceSu, int configuredRangeSu) {
        return !Float.isNaN(distanceSu)
                && distanceSu >= 0f
                && distanceSu <= clampRange(configuredRangeSu);
    }

    public synchronized JoinOffer createOffer(String fighterPlayerId, String fighterName,
                                              String enemySummary, float distanceSu,
                                              boolean wingmanAvailable, long nowMillis) {
        nextOfferCounter++;
        String id = "ally-offer-" + nextOfferCounter + "-" + nowMillis;
        JoinOffer offer = new JoinOffer(id, fighterPlayerId, fighterName, enemySummary,
                Math.round(Math.max(0f, distanceSu)), wingmanAvailable, nowMillis);
        outgoingOffers.put(id, offer);
        return offer;
    }

    public synchronized JoinOffer consumeOutgoingOffer(String offerId) {
        return outgoingOffers.remove(clean(offerId));
    }

    public synchronized void expireStaleOffers(long nowMillis) {
        outgoingOffers.entrySet().removeIf(
                e -> nowMillis - e.getValue().createdAtMillis() >= OFFER_TIMEOUT_MILLIS);
    }

    public synchronized boolean hasPendingOutgoingOffers() {
        return !outgoingOffers.isEmpty();
    }

    /** Records the local partner's choice when replying to an inbound {@link JoinOffer}. */
    public synchronized void noteLocalChoice(JoinChoice choice) {
        autoOpenTacticalMapPending = (choice == JoinChoice.JOIN_TACTICAL);
        wingmanDirectControlRequested = (choice == JoinChoice.JOIN_WINGMAN);
    }

    /** Consumes the one-shot auto-open latch when remote combat begins. */
    public synchronized boolean consumeAutoOpenTacticalMap() {
        boolean v = autoOpenTacticalMapPending;
        autoOpenTacticalMapPending = false;
        return v;
    }

    public synchronized boolean isWingmanDirectControlRequested() {
        return wingmanDirectControlRequested;
    }

    public synchronized void clearBattleEnd() {
        autoOpenTacticalMapPending = false;
        wingmanDirectControlRequested = false;
    }

    public synchronized void reset() {
        outgoingOffers.clear();
        autoOpenTacticalMapPending = false;
        wingmanDirectControlRequested = false;
    }

    /**
     * Pulls {@code partnerMirror} directly onto the local player's side of {@code battle} via
     * {@link BattleAPI#join(CampaignFleetAPI)}, working at any distance up to
     * {@code coop.allyJoinRange} (e.g. 3000 su) without altering global NPC {@code battleJoinRange}.
     */
    public static boolean joinMirrorIntoBattle(BattleAPI battle, CampaignFleetAPI partnerMirror) {
        if (battle == null || partnerMirror == null) {
            return false;
        }
        try {
            if (partnerMirror.getBattle() == battle) {
                return true;
            }
            if (partnerMirror.getBattle() != null) {
                return false;
            }
            MemoryAPI mem = partnerMirror.getMemoryWithoutUpdate();
            if (mem != null) {
                mem.unset(MemFlags.FLEET_IGNORES_OTHER_FLEETS);
            }
            battle.join(partnerMirror);
            boolean joined = partnerMirror.getBattle() == battle;
            if (joined) {
                CoopLog.info(CoopAllyReinforceCoordinator.class,
                        "Coop pulled partner mirror " + partnerMirror.getName()
                                + " into active battle via interactive ally join");
            }
            return joined;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopAllyReinforceCoordinator.class,
                    "Coop could not join partner mirror into battle", ex);
            return false;
        }
    }

    public static String encodeOffer(JoinOffer offer) {
        Objects.requireNonNull(offer, "offer");
        return CoopDelimited.field(offer.offerId()) + "|"
                + CoopDelimited.field(offer.fighterPlayerId()) + "|"
                + CoopDelimited.field(offer.fighterName()) + "|"
                + CoopDelimited.field(offer.enemySummary()) + "|"
                + offer.distanceSu() + "|"
                + offer.wingmanAvailable() + "|"
                + offer.createdAtMillis();
    }

    public static JoinOffer decodeOffer(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        List<String> f = CoopDelimited.split(body);
        if (f.size() < 7) {
            return null;
        }
        try {
            return new JoinOffer(
                    f.get(0),
                    f.get(1),
                    f.get(2),
                    f.get(3),
                    Integer.parseInt(f.get(4).trim()),
                    Boolean.parseBoolean(f.get(5).trim()),
                    Long.parseLong(f.get(6).trim()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static String encodeReply(JoinReply reply) {
        Objects.requireNonNull(reply, "reply");
        return CoopDelimited.field(reply.offerId()) + "|"
                + CoopDelimited.field(reply.partnerPlayerId()) + "|"
                + reply.choice().name();
    }

    public static JoinReply decodeReply(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        List<String> f = CoopDelimited.split(body);
        if (f.size() < 3) {
            return null;
        }
        try {
            return new JoinReply(f.get(0), f.get(1), JoinChoice.valueOf(f.get(2).trim()));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }
}
