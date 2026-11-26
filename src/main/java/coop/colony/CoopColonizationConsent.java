package coop.colony;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Host-approval state machine for {@code coop.guestColonizationConsent}.
 *
 * <p>When the host policy sets {@code coop.guestColonizationConsent=true}, a guest colonization
 * request is held pending until the host approves or denies it (or the timeout elapses, which fails
 * closed as denied so a stalled host never leaves a phantom colony).
 */
public final class CoopColonizationConsent {

    public static final long REQUEST_TIMEOUT_MILLIS = 30_000L;

    public enum Decision {
        PENDING,
        APPROVED,
        DENIED_BY_HOST,
        TIMED_OUT
    }

    public record Request(String requestId, String guestPlayerId, String guestName,
                          String planetId, String planetName, long createdAtMillis) {
        public Request {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(guestPlayerId, "guestPlayerId");
            guestName = guestName == null ? "Guest" : guestName.trim();
            Objects.requireNonNull(planetId, "planetId");
            planetName = planetName == null ? planetId : planetName.trim();
        }
    }

    private final Map<String, Request> pending = new LinkedHashMap<>();

    public synchronized boolean submit(Request request) {
        Objects.requireNonNull(request, "request");
        if (pending.containsKey(request.requestId())) {
            return false;
        }
        pending.put(request.requestId(), request);
        return true;
    }

    public synchronized Request nextPending() {
        return pending.isEmpty() ? null : pending.values().iterator().next();
    }

    public synchronized Decision resolve(String requestId, boolean approved) {
        Request removed = pending.remove(requestId);
        if (removed == null) {
            return null;
        }
        return approved ? Decision.APPROVED : Decision.DENIED_BY_HOST;
    }

    public synchronized Map<String, Request> expireOlderThan(long nowMillis) {
        Map<String, Request> expired = new LinkedHashMap<>();
        var it = pending.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (nowMillis - e.getValue().createdAtMillis() >= REQUEST_TIMEOUT_MILLIS) {
                expired.put(e.getKey(), e.getValue());
                it.remove();
            }
        }
        return expired;
    }

    public synchronized int pendingCount() {
        return pending.size();
    }

    public synchronized void clear() {
        pending.clear();
    }
}
