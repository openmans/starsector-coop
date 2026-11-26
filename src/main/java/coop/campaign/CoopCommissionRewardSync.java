package coop.campaign;

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Host-authoritative commission reward synchronization (Item 4):
 *
 * <ul>
 *   <li>Monthly commission stipend sharing for connected guests when the host holds an active
 *       faction commission (deduped per campaign month key).</li>
 *   <li>Commission combat bounty payouts when a guest destroys ships belonging to a faction hostile
 *       to the host's commissioning faction (deduped per {@code battleId}).</li>
 * </ul>
 *
 * <p>Emits standard {@code CREDITS_GRANT} transfers via {@link GrantSink}, reusing the existing
 * idempotent credit-grant ledger so reconnects never double-pay.
 */
public final class CoopCommissionRewardSync {

    public static final String REASON_SALARY_PREFIX = "commission:salary:";
    public static final String REASON_BOUNTY_PREFIX = "commission:bounty:";

    /** Engine seam so commission logic can run both in-game and inside functional test harnesses. */
    public interface CommissionWorld {
        /** Active commissioning faction id on the host, or {@code ""} if none. */
        String commissionFactionId();

        /** Base monthly commission salary in credits. */
        long monthlySalaryCredits();

        /** True when {@code targetFactionId} is hostile to {@code commissionFactionId}. */
        boolean isHostileToCommission(String commissionFactionId, String targetFactionId);
    }

    @FunctionalInterface
    public interface GrantSink {
        void grant(String guestPlayerId, long credits, String reason);
    }

    private final Set<String> paidMonthKeys = new HashSet<>();
    private final Set<String> paidBattleIds = new HashSet<>();

    /**
     * Pays the guest's share of the monthly commission stipend once per {@code monthKey} (for
     * example {@code "c207-m4"}). Returns the amount granted to each guest (0 if skipped).
     */
    public synchronized long onMonthEnd(String monthKey,
                                        Iterable<String> connectedGuestIds,
                                        CommissionWorld world,
                                        GrantSink sink) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(sink, "sink");
        String key = normalize(monthKey);
        if (key.isEmpty() || !paidMonthKeys.add(key)) {
            return 0L;
        }
        String factionId = normalize(world.commissionFactionId());
        long salary = world.monthlySalaryCredits();
        if (factionId.isEmpty() || salary <= 0L || connectedGuestIds == null) {
            return 0L;
        }
        int guestCount = 0;
        for (String id : connectedGuestIds) {
            if (id != null && !id.isBlank()) {
                guestCount++;
            }
        }
        if (guestCount == 0) {
            return 0L;
        }
        // Host and each guest receive an equal share of the commission stipend.
        long perPlayer = salary / (guestCount + 1L);
        if (perPlayer <= 0L) {
            return 0L;
        }
        String reason = REASON_SALARY_PREFIX + factionId + ":" + key;
        for (String guestId : connectedGuestIds) {
            if (guestId != null && !guestId.isBlank()) {
                sink.grant(guestId.trim(), perPlayer, reason);
            }
        }
        return perPlayer;
    }

    /**
     * Evaluates a guest combat victory against {@code defeatedFactionId} and awards the commission
     * bounty if the defeated faction is hostile to the host's commissioning faction.
     */
    public synchronized long onGuestBattleVictory(String battleId,
                                                  String guestPlayerId,
                                                  String defeatedFactionId,
                                                  long bountyCredits,
                                                  CommissionWorld world,
                                                  GrantSink sink) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(sink, "sink");
        String bid = normalize(battleId);
        String gid = normalize(guestPlayerId);
        String enemy = normalize(defeatedFactionId);
        if (bid.isEmpty() || gid.isEmpty() || enemy.isEmpty() || bountyCredits <= 0L) {
            return 0L;
        }
        String commissionFaction = normalize(world.commissionFactionId());
        if (commissionFaction.isEmpty()
                || !world.isHostileToCommission(commissionFaction, enemy)) {
            return 0L;
        }
        if (!paidBattleIds.add(bid)) {
            return 0L;
        }
        sink.grant(gid, bountyCredits, REASON_BOUNTY_PREFIX + commissionFaction + ":" + bid);
        return bountyCredits;
    }

    public synchronized void reset() {
        paidMonthKeys.clear();
        paidBattleIds.clear();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
