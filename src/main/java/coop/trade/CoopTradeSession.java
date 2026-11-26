package coop.trade;

import coop.campaign.CoopDelimited;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Direct player-to-player atomic trade state machine (Item 5):
 * {@code OPEN -> OFFERING -> LOCKED -> CONFIRMED -> COMMITTED / CANCELLED}.
 *
 * <p>Supports credits, cargo stacks (commodity id + quantity), and fleet ship member ids. Any
 * modification by either player automatically unlocks both sides so neither player can bait-and-
 * switch an offer after the partner locks. On cancel or disconnect, nothing is committed (atomic
 * rollback).
 */
public final class CoopTradeSession {

    public enum State {
        OPEN,
        LOCKED,
        COMMITTED,
        CANCELLED
    }

    public record CargoItem(String commodityId, int quantity) {
        public CargoItem {
            commodityId = commodityId == null ? "" : commodityId.trim();
            quantity = Math.max(0, quantity);
        }
    }

    public record Offer(long credits, List<CargoItem> cargo, List<String> shipMemberIds,
                        boolean locked, boolean confirmed) {
        public static final Offer EMPTY = new Offer(0L, List.of(), List.of(), false, false);

        public Offer {
            credits = Math.max(0L, credits);
            cargo = cargo == null ? List.of() : List.copyOf(cargo);
            shipMemberIds = shipMemberIds == null ? List.of() : List.copyOf(shipMemberIds);
        }

        public Offer unlocked() {
            return new Offer(credits, cargo, shipMemberIds, false, false);
        }

        public Offer withLocked(boolean value) {
            return new Offer(credits, cargo, shipMemberIds, value, value && confirmed);
        }

        public Offer withConfirmed(boolean value) {
            return new Offer(credits, cargo, shipMemberIds, locked || value, value);
        }
    }

    /** Settlement seam executed once both sides confirm; also allows deterministic unit tests. */
    public interface Settlement {
        boolean transfer(String fromPlayerId, String toPlayerId, Offer offer);
    }

    private final String tradeId;
    private final String initiatorId;
    private final String partnerId;
    private final Map<String, Offer> offers = new LinkedHashMap<>();
    private State state = State.OPEN;
    private String cancelReason = "";

    public CoopTradeSession(String tradeId, String initiatorId, String partnerId) {
        this.tradeId = requireNonBlank(tradeId, "tradeId");
        this.initiatorId = requireNonBlank(initiatorId, "initiatorId");
        this.partnerId = requireNonBlank(partnerId, "partnerId");
        if (this.initiatorId.equals(this.partnerId)) {
            throw new IllegalArgumentException("Cannot trade with self");
        }
        offers.put(this.initiatorId, Offer.EMPTY);
        offers.put(this.partnerId, Offer.EMPTY);
    }

    public String tradeId() {
        return tradeId;
    }

    public String initiatorId() {
        return initiatorId;
    }

    public String partnerId() {
        return partnerId;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized String cancelReason() {
        return cancelReason;
    }

    public synchronized Offer offerOf(String playerId) {
        Offer o = offers.get(playerId);
        return o == null ? Offer.EMPTY : o;
    }

    /** Updates one player's offer and unlocks both sides to prevent bait-and-switch. */
    public synchronized boolean updateOffer(String playerId, long credits,
                                            List<CargoItem> cargo, List<String> shipMemberIds) {
        if (!isParticipant(playerId) || state == State.COMMITTED || state == State.CANCELLED) {
            return false;
        }
        offers.put(playerId, new Offer(credits, cargo, shipMemberIds, false, false));
        String other = otherOf(playerId);
        offers.put(other, offers.get(other).unlocked());
        state = State.OPEN;
        return true;
    }

    /** Locks a player's current offer; once both lock, state advances to {@link State#LOCKED}. */
    public synchronized boolean lock(String playerId) {
        if (!isParticipant(playerId) || state == State.COMMITTED || state == State.CANCELLED) {
            return false;
        }
        offers.put(playerId, offers.get(playerId).withLocked(true));
        if (bothLocked()) {
            state = State.LOCKED;
        }
        return true;
    }

    /**
     * Confirms a locked trade. When both sides have confirmed, atomically runs {@code settlement}
     * in both directions; if either transfer fails, cancels with rollback reason.
     */
    public synchronized boolean confirm(String playerId, Settlement settlement) {
        Objects.requireNonNull(settlement, "settlement");
        if (!isParticipant(playerId) || state != State.LOCKED) {
            return false;
        }
        offers.put(playerId, offers.get(playerId).withConfirmed(true));
        if (!bothConfirmed()) {
            return true;
        }
        Offer fromInit = offers.get(initiatorId);
        Offer fromPartner = offers.get(partnerId);
        boolean okA = settlement.transfer(initiatorId, partnerId, fromInit);
        if (!okA) {
            cancel("initiator-transfer-failed");
            return false;
        }
        boolean okB = settlement.transfer(partnerId, initiatorId, fromPartner);
        if (!okB) {
            // Roll back initiator's transfer cleanly
            settlement.transfer(partnerId, initiatorId, fromInit);
            cancel("partner-transfer-failed");
            return false;
        }
        state = State.COMMITTED;
        return true;
    }

    public synchronized void cancel(String reason) {
        if (state == State.COMMITTED) {
            return;
        }
        state = State.CANCELLED;
        cancelReason = reason == null ? "cancelled" : reason.trim();
    }

    private boolean isParticipant(String playerId) {
        return initiatorId.equals(playerId) || partnerId.equals(playerId);
    }

    private String otherOf(String playerId) {
        return initiatorId.equals(playerId) ? partnerId : initiatorId;
    }

    private boolean bothLocked() {
        return offers.get(initiatorId).locked() && offers.get(partnerId).locked();
    }

    private boolean bothConfirmed() {
        return offers.get(initiatorId).confirmed() && offers.get(partnerId).confirmed();
    }

    /** Encodes a single player's offer update for {@code TRADE_SYNC}. */
    public static String encodeOffer(String tradeId, String playerId, String action, Offer offer) {
        Offer o = offer == null ? Offer.EMPTY : offer;
        StringBuilder sb = new StringBuilder();
        sb.append("T|")
                .append(CoopDelimited.field(tradeId)).append('|')
                .append(CoopDelimited.field(playerId)).append('|')
                .append(CoopDelimited.field(action)).append('|')
                .append(o.credits()).append('|')
                .append(o.locked()).append('|')
                .append(o.confirmed());
        for (CargoItem c : o.cargo()) {
            sb.append("\nC|").append(CoopDelimited.field(c.commodityId())).append('|').append(c.quantity());
        }
        for (String shipId : o.shipMemberIds()) {
            sb.append("\nS|").append(CoopDelimited.field(shipId));
        }
        return sb.toString();
    }

    public record WireFrame(String tradeId, String playerId, String action, Offer offer) {
    }

    public static WireFrame decodeOffer(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String tradeId = "";
        String playerId = "";
        String action = "";
        long credits = 0L;
        boolean locked = false;
        boolean confirmed = false;
        List<CargoItem> cargo = new ArrayList<>();
        List<String> ships = new ArrayList<>();
        boolean headerSeen = false;
        for (String line : body.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            List<String> f = CoopDelimited.split(line);
            if ("T".equals(f.get(0)) && f.size() >= 7) {
                headerSeen = true;
                tradeId = f.get(1);
                playerId = f.get(2);
                action = f.get(3);
                credits = parseLong(f.get(4));
                locked = Boolean.parseBoolean(f.get(5));
                confirmed = Boolean.parseBoolean(f.get(6));
            } else if ("C".equals(f.get(0)) && f.size() >= 3) {
                cargo.add(new CargoItem(f.get(1), (int) parseLong(f.get(2))));
            } else if ("S".equals(f.get(0)) && f.size() >= 2) {
                ships.add(f.get(1));
            }
        }
        if (!headerSeen) {
            return null;
        }
        return new WireFrame(tradeId, playerId, action,
                new Offer(credits, Collections.unmodifiableList(cargo),
                        Collections.unmodifiableList(ships), locked, confirmed));
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
