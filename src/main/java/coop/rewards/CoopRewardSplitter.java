package coop.rewards;

import java.util.Objects;

/**
 * Phase 24 milestone 3: the co-op share policy, as pure arithmetic.
 *
 * <p>V1 has exactly one policy — {@link Policy#EVEN}, the 50/50 split decided 2026-06-10 — and one
 * caller, the monthly colony-income split. It is a class rather than a division by two because the
 * plan pins the name and the package: Phase 22 (post-V1) extends it for joint-combat spoils, and
 * Phase 28's options registry is where {@code coop.incomeSplit} would make the policy configurable.
 * Keeping the arithmetic here, tested, means neither of those has to re-derive the rounding rule.
 *
 * <p><b>The rounding rule.</b> {@link Split#localShare()} truncates toward zero and the remainder
 * goes to {@link Split#remoteShare()}, so the two always sum <em>exactly</em> to the total, for
 * positive and negative totals alike. Every engine computing a split of the same total therefore
 * keeps the same number, which is what makes the income model work: nothing about the money crosses
 * the wire, so the two clients have to agree by construction rather than by agreement.
 *
 * <p>Negative totals split symmetrically — a colony month that loses money costs each player about
 * half of the loss, not one player all of it.
 */
public final class CoopRewardSplitter {

    private CoopRewardSplitter() {
    }

    /** How a total is divided between the local player and the peer. */
    public enum Policy {
        /** 50/50, the V1 rule. Odd remainders land on the peer's side; see {@link Split}. */
        EVEN
    }

    /**
     * One divided total. {@code localShare + remoteShare == total} always holds.
     *
     * @param localShare  what the client performing the split keeps.
     * @param remoteShare what it does not keep. Under the local-half income model this is what the
     *                    engine deducts from its own player's wallet; the peer's engine keeps its
     *                    own {@code localShare} of its own identical total, so no credits are ever
     *                    transferred across the wire.
     */
    public record Split(long localShare, long remoteShare) {

        public long total() {
            return localShare + remoteShare;
        }

        public boolean isZero() {
            return localShare == 0 && remoteShare == 0;
        }
    }

    /** The V1 policy, applied. */
    public static Split split(long total) {
        return split(total, Policy.EVEN);
    }

    public static Split split(long total, Policy policy) {
        return split(total, policy, 2);
    }

    /**
     * Phase 27: equal N-way split across {@code playerCount} players (2..4). Keeps exact 50/50
     * behaviour for {@code playerCount <= 2}.
     */
    public static Split split(long total, int playerCount) {
        return split(total, Policy.EVEN, playerCount);
    }

    public static Split split(long total, Policy policy, int playerCount) {
        Objects.requireNonNull(policy, "policy");
        int divisor = Math.max(2, playerCount);
        // Java's integer division truncates toward zero: each side keeps total / divisor and
        // deducts the remaining share (total - local) from its local wallet.
        long local = total / divisor;
        return new Split(local, total - local);
    }

    /**
     * The local player's share of a colony month, rounded to whole credits.
     *
     * <p>Credits are a float in the engine ({@code MutableValue}), and a monthly report node is a
     * float too, so the split happens on a rounded long and the deduction is applied as that long.
     * Rounding once, here, is what keeps the two clients' deductions identical rather than
     * differing by a fraction of a credit that would compound over a campaign.
     */
    public static Split splitCredits(float total) {
        return split(Math.round(total));
    }

    /** Phase 27: N-way credit split across {@code playerCount} players (2..4). */
    public static Split splitCredits(float total, int playerCount) {
        return split(Math.round(total), playerCount);
    }

    /**
     * Splits colony monthly credits honouring {@code coop.incomeSplit} ({@code equal} vs
     * {@code host-banks}): under {@code host-banks}, the host keeps 100% and each guest deducts 100%.
     */
    public static Split splitColonyCredits(float total, int playerCount, boolean isHost,
                                           String incomeSplitPolicy) {
        long rounded = Math.round(total);
        if ("host-banks".equalsIgnoreCase(incomeSplitPolicy == null ? "" : incomeSplitPolicy.trim())) {
            return isHost ? new Split(rounded, 0L) : new Split(0L, rounded);
        }
        return split(rounded, playerCount);
    }

    /**
     * Splits joint-battle salvage credit value honouring {@code coop.lootSplit}
     * ({@code equal} vs {@code pilot-keeps}).
     */
    public static Split splitSalvageCredits(long totalSalvageCredits, int participants,
                                            String lootSplitPolicy) {
        if (totalSalvageCredits <= 0L || participants <= 1
                || "pilot-keeps".equalsIgnoreCase(lootSplitPolicy == null ? "" : lootSplitPolicy.trim())) {
            return new Split(Math.max(0L, totalSalvageCredits), 0L);
        }
        return split(totalSalvageCredits, participants);
    }
}
