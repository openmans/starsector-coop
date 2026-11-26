package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.ReputationActionResponsePlugin.ReputationAdjustmentResult;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.comm.IntelManagerAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActionEnvelope;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActions;
import com.fs.starfarer.api.impl.campaign.intel.PersonBountyIntel;
import com.fs.starfarer.api.impl.campaign.intel.PersonBountyIntel.BountyResult;
import com.fs.starfarer.api.impl.campaign.intel.PersonBountyIntel.BountyResultType;
import com.fs.starfarer.api.impl.campaign.intel.SystemBountyIntel;
import coop.combat.CoopBattleResult;
import coop.fleet.CoopFleetSnapshot;
import coop.fleet.CoopLocations;
import coop.handshake.CoopChecksum;
import coop.net.CoopMessages;
import coop.ui.CoopFeed;
import coop.ui.CoopPersonBountyIntel;
import coop.ui.CoopSystemBountyIntel;
import coop.util.CoopLog;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Phase 34: replicates host-authoritative person and system bounties to guests and awards the
 * guest their bounty credits from the host's pre-reconcile {@code BATTLE_RESULT} pass before
 * {@code CoopBattleResultReconciler} despawns the destroyed target fleet.
 *
 * <p>All private/protected access into vanilla {@link PersonBountyIntel} uses sandbox-safe
 * {@link MethodHandles} (never {@code java.lang.reflect}).
 */
public final class CoopBountyAuthority {

    public static final long POLL_MILLIS = 3_000L;
    private static final String PERSON_TAG = "P";
    private static final String SYSTEM_TAG = "S";
    private static final int MAX_PAID_BATTLES = 512;

    public record PersonBounty(String bountyId, String coopFleetId, String personName,
                               String portraitId, String factionId, int level, int credits,
                               String hideoutSystemId, String hideoutName, int daysRemaining,
                               String stage) {
        public PersonBounty {
            bountyId = nonNull(bountyId);
            coopFleetId = nonNull(coopFleetId);
            personName = nonNull(personName);
            portraitId = nonNull(portraitId);
            factionId = nonNull(factionId);
            hideoutSystemId = nonNull(hideoutSystemId);
            hideoutName = nonNull(hideoutName);
            stage = nonNull(stage);
        }
    }

    public record SystemBounty(String bountyId, String systemId, String systemName,
                               String marketId, String factionId, int creditsPerFp,
                               int daysRemaining) {
        public SystemBounty {
            bountyId = nonNull(bountyId);
            systemId = nonNull(systemId);
            systemName = nonNull(systemName);
            marketId = nonNull(marketId);
            factionId = nonNull(factionId);
        }
    }

    public record BountySet(List<PersonBounty> personBounties, List<SystemBounty> systemBounties) {
        public static final BountySet EMPTY = new BountySet(List.of(), List.of());

        public BountySet {
            personBounties = personBounties == null ? List.of() : List.copyOf(personBounties);
            systemBounties = systemBounties == null ? List.of() : List.copyOf(systemBounties);
        }

        public String encode() {
            List<String> lines = new ArrayList<>(personBounties.size() + systemBounties.size());
            for (PersonBounty p : personBounties) {
                lines.add(PERSON_TAG + "|" + esc(p.bountyId()) + "|" + esc(p.coopFleetId())
                        + "|" + esc(p.personName()) + "|" + esc(p.portraitId())
                        + "|" + esc(p.factionId()) + "|" + p.level() + "|" + p.credits()
                        + "|" + esc(p.hideoutSystemId()) + "|" + esc(p.hideoutName())
                        + "|" + p.daysRemaining() + "|" + esc(p.stage()));
            }
            for (SystemBounty s : systemBounties) {
                lines.add(SYSTEM_TAG + "|" + esc(s.bountyId()) + "|" + esc(s.systemId())
                        + "|" + esc(s.systemName()) + "|" + esc(s.marketId())
                        + "|" + esc(s.factionId()) + "|" + s.creditsPerFp()
                        + "|" + s.daysRemaining());
            }
            return String.join("\n", lines);
        }

        public static BountySet decode(String encoded) {
            if (encoded == null || encoded.isBlank()) {
                return EMPTY;
            }
            List<PersonBounty> persons = new ArrayList<>();
            List<SystemBounty> systems = new ArrayList<>();
            for (String line : encoded.split("\n", -1)) {
                if (line.isEmpty()) {
                    continue;
                }
                List<String> parts = CoopDelimited.split(line);
                if (parts.isEmpty()) {
                    continue;
                }
                String tag = parts.get(0);
                if (PERSON_TAG.equals(tag) && parts.size() >= 12) {
                    persons.add(new PersonBounty(
                            parts.get(1), parts.get(2), parts.get(3),
                            parts.get(4), parts.get(5),
                            parseInt(parts.get(6)), parseInt(parts.get(7)),
                            parts.get(8), parts.get(9),
                            parseInt(parts.get(10)), parts.get(11)));
                } else if (SYSTEM_TAG.equals(tag) && parts.size() >= 8) {
                    systems.add(new SystemBounty(
                            parts.get(1), parts.get(2), parts.get(3),
                            parts.get(4), parts.get(5),
                            parseInt(parts.get(6)), parseInt(parts.get(7))));
                }
            }
            return new BountySet(persons, systems);
        }

        public String contentHash() {
            return CoopChecksum.sha256Text(encode());
        }
    }

    /** Host-side bounty payout descriptor produced before the reconciler despawns fleets. */
    public record BountyPayout(String bountyId, String kind, String guestPlayerId, int credits,
                               String reason) {
    }

    /** Host-side engine seam for capturing bounties and settling them before fleet despawn. */
    public interface HostWorld {
        BountySet captureHostBounties();

        /**
         * Evaluates a guest's battle result against live host bounties BEFORE the reconciler runs.
         * Implementations mark matched {@link PersonBountyIntel} as {@code END_PLAYER_BOUNTY} and
         * apply shared faction reputation, returning the credit grants to ship to the guest.
         */
        List<BountyPayout> settleGuestBattleResult(CoopBattleResult result, String guestPlayerId);
    }

    /** Guest-side engine seam for applying mirrored bounty intel. */
    public interface GuestWorld {
        void reconcile(BountySet set);

        void clearMirrored();
    }

    /** Sends an authoritative bounty credit grant (no debit on host wallet) to the named guest. */
    public interface GrantSender {
        void sendBountyGrant(String recipientPlayerId, String ledgerId, int amount, String reason);
    }

    private final LongSupplier clockMillis;
    private final HostWorld hostWorld;
    private final GuestWorld guestWorld;
    private final java.util.function.Consumer<String> setBroadcaster;
    private final GrantSender grantSender;

    private String lastSentHash = "";
    private long nextPollAtMillis;
    private long grantCounter;
    private final Set<String> settledBattles = new LinkedHashSet<>();

    public CoopBountyAuthority(LongSupplier clockMillis,
                               java.util.function.Consumer<String> setBroadcaster,
                               GrantSender grantSender) {
        this(clockMillis, new LiveHostWorld(), new LiveGuestWorld(), setBroadcaster, grantSender);
    }

    public CoopBountyAuthority(LongSupplier clockMillis,
                               HostWorld hostWorld,
                               GuestWorld guestWorld,
                               java.util.function.Consumer<String> setBroadcaster,
                               GrantSender grantSender) {
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.hostWorld = Objects.requireNonNull(hostWorld, "hostWorld");
        this.guestWorld = Objects.requireNonNull(guestWorld, "guestWorld");
        this.setBroadcaster = Objects.requireNonNull(setBroadcaster, "setBroadcaster");
        this.grantSender = Objects.requireNonNull(grantSender, "grantSender");
    }

    /** Host tick: poll active bounties and broadcast {@code BOUNTY_SET} on change. */
    public void tickHost() {
        long now = clockMillis.getAsLong();
        if (now < nextPollAtMillis) {
            return;
        }
        nextPollAtMillis = now + POLL_MILLIS;
        try {
            BountySet set = hostWorld.captureHostBounties();
            if (set == null) {
                return;
            }
            String hash = set.contentHash();
            if (hash.equals(lastSentHash)) {
                return;
            }
            setBroadcaster.accept(set.encode());
            lastSentHash = hash;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBountyAuthority.class, "Failed to poll host bounties", ex);
        }
    }

    /** Forces the next host tick to re-broadcast the full bounty set (session start / resume). */
    public void rearmBroadcast() {
        lastSentHash = "";
        nextPollAtMillis = 0L;
    }

    /**
     * Host pre-reconcile hook: must be invoked BEFORE {@code CoopBattleResultReconciler.apply(result)}
     * so destroyed bounty fleets are settled as {@code END_PLAYER_BOUNTY} rather than {@code END_OTHER}.
     */
    public List<BountyPayout> onGuestBattleResultPreReconcile(CoopBattleResult result,
                                                              String sessionId,
                                                              String guestPlayerId) {
        if (result == null || result.battleId().isEmpty()) {
            return List.of();
        }
        if (settledBattles.contains(result.battleId())) {
            return List.of();
        }
        rememberBattle(result.battleId());
        List<BountyPayout> payouts;
        try {
            payouts = hostWorld.settleGuestBattleResult(result, guestPlayerId);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBountyAuthority.class,
                    "Failed to evaluate guest battle result for bounty payout battleId=" + result.battleId(), ex);
            return List.of();
        }
        if (payouts == null || payouts.isEmpty()) {
            return List.of();
        }
        for (BountyPayout payout : payouts) {
            if (payout.credits() <= 0) {
                continue;
            }
            int capped = Math.min(payout.credits(), CoopMessages.MAX_CREDITS_GRANT);
            String ledgerId = (sessionId == null ? "s" : sessionId) + "-bounty-" + (++grantCounter);
            String reason = truncateReason(payout.reason());
            grantSender.sendBountyGrant(payout.guestPlayerId(), ledgerId, capped, reason);
            CoopLog.info(CoopBountyAuthority.class, "Coop bounty paid kind=" + payout.kind()
                    + " bountyId=" + payout.bountyId() + " guest=" + payout.guestPlayerId()
                    + " amount=" + capped + " ledger=" + ledgerId);
        }
        rearmBroadcast();
        return payouts;
    }

    /** Guest receive path: applies an incoming {@code BOUNTY_SET} payload. */
    public void applyGuestSet(String encoded) {
        try {
            BountySet set = BountySet.decode(encoded);
            guestWorld.reconcile(set);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBountyAuthority.class, "Failed to apply BOUNTY_SET", ex);
        }
    }

    /** Session teardown: clears mirrored guest bounty intel and resets polling/dedup state. */
    public void dispose() {
        rearmBroadcast();
        settledBattles.clear();
        try {
            guestWorld.clearMirrored();
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBountyAuthority.class, "Failed to clear mirrored guest bounties", ex);
        }
    }

    /** Pure helper: determines if a surviving fleet lost its flagship or target commander. */
    public static boolean targetCollectedInResult(String targetFleetId, String targetFlagshipHullId,
                                                  String targetCommanderName, CoopBattleResult result) {
        if (targetFleetId == null || targetFleetId.isEmpty() || result == null) {
            return false;
        }
        if (result.destroyedFleetIds().contains(targetFleetId)) {
            return true;
        }
        for (CoopBattleResult.SurvivingFleet survivor : result.survivingFleets()) {
            if (!targetFleetId.equals(survivor.coopFleetId())) {
                continue;
            }
            if (survivor.members().isEmpty()) {
                return true;
            }
            boolean hasCaptain = targetCommanderName != null && !targetCommanderName.isEmpty();
            boolean hasHull = targetFlagshipHullId != null && !targetFlagshipHullId.isEmpty();
            if (hasCaptain || hasHull) {
                boolean flagshipAlive = false;
                for (CoopFleetSnapshot.Member member : survivor.members()) {
                    boolean captainMatch = hasCaptain && targetCommanderName.equals(member.captainName());
                    boolean hullMatch = !hasCaptain && hasHull && targetFlagshipHullId.equals(member.hullId());
                    if (captainMatch || hullMatch) {
                        flagshipAlive = true;
                        break;
                    }
                }
                if (!flagshipAlive) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Pure helper: computes system-bounty credit reward from destroyed fleet points. */
    public static int computeSystemBountyReward(int creditsPerFp, int destroyedFleetPoints) {
        if (creditsPerFp <= 0 || destroyedFleetPoints <= 0) {
            return 0;
        }
        long total = (long) creditsPerFp * (long) destroyedFleetPoints;
        return (int) Math.min(total, CoopMessages.MAX_CREDITS_GRANT);
    }

    private void rememberBattle(String battleId) {
        settledBattles.add(battleId);
        if (settledBattles.size() > MAX_PAID_BATTLES) {
            String oldest = settledBattles.iterator().next();
            settledBattles.remove(oldest);
        }
    }

    private static String truncateReason(String reason) {
        String safe = nonNull(reason);
        if (safe.isEmpty()) {
            return CoopCreditTransfer.REASON_BOUNTY;
        }
        return safe.length() <= CoopMessages.MAX_CREDITS_REASON_CHARS
                ? safe : safe.substring(0, CoopMessages.MAX_CREDITS_REASON_CHARS);
    }

    private static String esc(String s) {
        return CoopDelimited.field(nonNull(s));
    }

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    // ---- Sandbox-safe MethodHandles into PersonBountyIntel -------------------------------------

    static final class PersonBountyHandles {
        private static volatile boolean resolved;
        private static MethodHandle resultSetter;
        private static MethodHandle willPayMethod;
        private static MethodHandle willRepIncreaseMethod;
        private static MethodHandle cleanUpMethod;

        static void ensureResolved() {
            if (resolved) {
                return;
            }
            resolved = true;
            try {
                MethodHandles.Lookup priv = MethodHandles.privateLookupIn(
                        PersonBountyIntel.class, MethodHandles.lookup());
                resultSetter = priv.findSetter(PersonBountyIntel.class, "result", BountyResult.class);
                willPayMethod = priv.findVirtual(PersonBountyIntel.class, "willPay",
                        MethodType.methodType(boolean.class));
                willRepIncreaseMethod = priv.findVirtual(PersonBountyIntel.class, "willRepIncrease",
                        MethodType.methodType(boolean.class));
                cleanUpMethod = priv.findVirtual(PersonBountyIntel.class, "cleanUpFleetAndEndIfNecessary",
                        MethodType.methodType(void.class));
            } catch (Throwable ex) {
                CoopLog.warn(CoopBountyAuthority.class,
                        "Could not resolve PersonBountyIntel MethodHandles; bounty auto-settlement degraded", ex);
            }
        }

        static boolean willPay(PersonBountyIntel intel) {
            ensureResolved();
            if (willPayMethod == null) {
                return true;
            }
            try {
                return (boolean) willPayMethod.invoke(intel);
            } catch (Throwable ex) {
                return true;
            }
        }

        static boolean willRepIncrease(PersonBountyIntel intel) {
            ensureResolved();
            if (willRepIncreaseMethod == null) {
                return true;
            }
            try {
                return (boolean) willRepIncreaseMethod.invoke(intel);
            } catch (Throwable ex) {
                return true;
            }
        }

        static boolean settle(PersonBountyIntel intel, BountyResult bountyResult) {
            ensureResolved();
            if (resultSetter == null) {
                return false;
            }
            try {
                resultSetter.invoke(intel, bountyResult);
                if (cleanUpMethod != null) {
                    cleanUpMethod.invoke(intel);
                }
                return true;
            } catch (Throwable ex) {
                CoopLog.warn(CoopBountyAuthority.class, "Failed to settle PersonBountyIntel", ex);
                return false;
            }
        }
    }

    // ---- Live host world -----------------------------------------------------------------------

    static final class LiveHostWorld implements HostWorld {

        @Override
        public BountySet captureHostBounties() {
            SectorAPI sector = sectorOrNull();
            if (sector == null || sector.getIntelManager() == null) {
                return null;
            }
            IntelManagerAPI manager = sector.getIntelManager();
            List<PersonBounty> persons = new ArrayList<>();
            for (IntelInfoPlugin plugin : manager.getIntel(PersonBountyIntel.class)) {
                if (!(plugin instanceof PersonBountyIntel pb)) {
                    continue;
                }
                if (pb.isEnded() || pb.isEnding() || pb.getResult() != null) {
                    continue;
                }
                CampaignFleetAPI fleet = pb.getFleet();
                PersonAPI person = pb.getPerson();
                String fleetId = fleet == null ? "" : nonNull(fleet.getId());
                String bountyId = !fleetId.isEmpty() ? "pb:" + fleetId
                        : (person != null && person.getId() != null ? "pb:" + person.getId()
                        : "pb:" + System.identityHashCode(pb));
                String personName = person == null ? "" : nonNull(person.getNameString());
                String portrait = person == null ? "" : nonNull(person.getPortraitSprite());
                String factionId = pb.getFaction() == null ? "" : nonNull(pb.getFaction().getId());
                SectorEntityToken hideout = pb.getHideoutLocation();
                LocationAPI loc = hideout == null ? null : hideout.getContainingLocation();
                String hideoutSystemId = loc == null ? "" : nonNull(loc.getId());
                String hideoutName = loc == null ? (hideout == null ? "" : nonNull(hideout.getName()))
                        : nonNull(loc.getName());
                int daysLeft = Math.max(0, Math.round(pb.getDuration() - pb.getElapsedDays()));
                persons.add(new PersonBounty(bountyId, fleetId, personName, portrait, factionId,
                        pb.getLevel(), Math.round(pb.getBountyCredits()),
                        hideoutSystemId, hideoutName, daysLeft, "ACTIVE"));
            }

            List<SystemBounty> systems = new ArrayList<>();
            for (IntelInfoPlugin plugin : manager.getIntel(SystemBountyIntel.class)) {
                if (!(plugin instanceof SystemBountyIntel sb)) {
                    continue;
                }
                if (sb.isEnded() || sb.isEnding()) {
                    continue;
                }
                MarketAPI market = sb.getMarket();
                LocationAPI loc = sb.getLocation();
                String marketId = market == null ? "" : nonNull(market.getId());
                String factionId = sb.getFactionForUIColors() == null ? ""
                        : nonNull(sb.getFactionForUIColors().getId());
                String systemId = loc == null ? "" : nonNull(loc.getId());
                String systemName = loc == null ? "" : nonNull(loc.getName());
                String bountyId = "sb:" + marketId + ":" + factionId;
                int daysLeft = Math.max(0, Math.round(sb.getDuration() - sb.getElapsedDays()));
                systems.add(new SystemBounty(bountyId, systemId, systemName, marketId, factionId,
                        Math.round(sb.getBaseBounty()), daysLeft));
            }
            return new BountySet(persons, systems);
        }

        @Override
        public List<BountyPayout> settleGuestBattleResult(CoopBattleResult result, String guestPlayerId) {
            SectorAPI sector = sectorOrNull();
            if (sector == null || sector.getIntelManager() == null) {
                return List.of();
            }
            IntelManagerAPI manager = sector.getIntelManager();
            List<BountyPayout> payouts = new ArrayList<>();

            // 1. Person bounties
            for (IntelInfoPlugin plugin : new ArrayList<>(manager.getIntel(PersonBountyIntel.class))) {
                if (!(plugin instanceof PersonBountyIntel pb)) {
                    continue;
                }
                if (pb.isEnded() || pb.isEnding() || pb.getResult() != null) {
                    continue;
                }
                CampaignFleetAPI fleet = pb.getFleet();
                if (fleet == null || fleet.getId() == null) {
                    continue;
                }
                FleetMemberAPI flagship = pb.getFlagship();
                String flagshipHullId = flagship == null || flagship.getHullSpec() == null
                        ? "" : nonNull(flagship.getHullSpec().getHullId());
                String commanderName = pb.getPerson() == null ? "" : nonNull(pb.getPerson().getNameString());
                if (!targetCollectedInResult(fleet.getId(), flagshipHullId, commanderName, result)) {
                    continue;
                }
                boolean pays = PersonBountyHandles.willPay(pb);
                int amount = pays ? Math.max(0, Math.round(pb.getBountyCredits())) : 0;
                ReputationAdjustmentResult rep = null;
                if (PersonBountyHandles.willRepIncrease(pb) && pb.getFaction() != null) {
                    try {
                        rep = sector.adjustPlayerReputation(
                                new RepActionEnvelope(RepActions.PERSON_BOUNTY_REWARD, null, null, true),
                                pb.getFaction().getId());
                    } catch (RuntimeException | LinkageError ex) {
                        CoopLog.warn(CoopBountyAuthority.class, "Failed to apply person bounty rep", ex);
                    }
                }
                BountyResultType resultType = pays ? BountyResultType.END_PLAYER_BOUNTY
                        : BountyResultType.END_PLAYER_NO_BOUNTY;
                PersonBountyHandles.settle(pb, new BountyResult(resultType, amount, rep));
                if (amount > 0) {
                    String bountyId = "pb:" + fleet.getId();
                    payouts.add(new BountyPayout(bountyId, "person", guestPlayerId, amount,
                            CoopCreditTransfer.REASON_BOUNTY + ":" + bountyId));
                }
            }

            // 2. System bounties — sum destroyed FP of hostile fleets in each active bounty's system
            Map<String, CampaignFleetAPI> hostFleetsById = indexFleets(sector);
            for (IntelInfoPlugin plugin : new ArrayList<>(manager.getIntel(SystemBountyIntel.class))) {
                if (!(plugin instanceof SystemBountyIntel sb) || sb.isEnded() || sb.isEnding()) {
                    continue;
                }
                LocationAPI bountyLoc = sb.getLocation();
                FactionAPI bountyFaction = sb.getFactionForUIColors();
                if (bountyLoc == null || bountyFaction == null) {
                    continue;
                }
                int destroyedFp = 0;
                for (String fleetId : result.destroyedFleetIds()) {
                    CampaignFleetAPI hostFleet = hostFleetsById.get(fleetId);
                    if (hostFleet == null || hostFleet.getContainingLocation() != bountyLoc) {
                        continue;
                    }
                    if (!isHostile(hostFleet.getFaction(), bountyFaction)) {
                        continue;
                    }
                    destroyedFp += totalFleetPoints(hostFleet);
                }
                for (CoopBattleResult.SurvivingFleet survivor : result.survivingFleets()) {
                    CampaignFleetAPI hostFleet = hostFleetsById.get(survivor.coopFleetId());
                    if (hostFleet == null || hostFleet.getContainingLocation() != bountyLoc) {
                        continue;
                    }
                    if (!isHostile(hostFleet.getFaction(), bountyFaction)) {
                        continue;
                    }
                    destroyedFp += missingFleetPoints(hostFleet, survivor);
                }
                int reward = computeSystemBountyReward(Math.round(sb.getBaseBounty()), destroyedFp);
                if (reward > 0) {
                    String marketId = sb.getMarket() == null ? nonNull(bountyLoc.getId()) : nonNull(sb.getMarket().getId());
                    String bountyId = "sb:" + marketId + ":" + nonNull(bountyFaction.getId());
                    payouts.add(new BountyPayout(bountyId, "system", guestPlayerId, reward,
                            CoopCreditTransfer.REASON_BOUNTY + ":sys:" + marketId));
                }
            }
            return payouts;
        }

        private static Map<String, CampaignFleetAPI> indexFleets(SectorAPI sector) {
            Map<String, CampaignFleetAPI> out = new HashMap<>();
            CoopLocations.forEach(sector, loc -> {
                List<CampaignFleetAPI> fleets = loc.getFleets();
                if (fleets == null) {
                    return;
                }
                for (CampaignFleetAPI fleet : fleets) {
                    if (fleet != null && fleet.getId() != null) {
                        out.put(fleet.getId(), fleet);
                    }
                }
            });
            return out;
        }

        private static boolean isHostile(FactionAPI target, FactionAPI bountyFaction) {
            if (target == null || bountyFaction == null) {
                return false;
            }
            try {
                return bountyFaction.isHostileTo(target);
            } catch (RuntimeException | LinkageError ex) {
                return false;
            }
        }

        private static int totalFleetPoints(CampaignFleetAPI fleet) {
            if (fleet == null || fleet.getFleetData() == null) {
                return 0;
            }
            int sum = 0;
            for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
                if (m != null) {
                    sum += Math.max(0, m.getFleetPointCost());
                }
            }
            return sum;
        }

        private static int missingFleetPoints(CampaignFleetAPI hostFleet,
                                              CoopBattleResult.SurvivingFleet survivor) {
            if (hostFleet == null || hostFleet.getFleetData() == null) {
                return 0;
            }
            // Match surviving hull counts against host's real roster (mirror member ids are local).
            Map<String, Integer> survivingByHull = new HashMap<>();
            for (CoopFleetSnapshot.Member m : survivor.members()) {
                String key = nonNull(m.hullId());
                survivingByHull.put(key, survivingByHull.getOrDefault(key, 0) + 1);
            }
            int lost = 0;
            for (FleetMemberAPI m : hostFleet.getFleetData().getMembersListCopy()) {
                if (m == null) {
                    continue;
                }
                String hullId = m.getHullSpec() == null ? "" : nonNull(m.getHullSpec().getHullId());
                int rem = survivingByHull.getOrDefault(hullId, 0);
                if (rem > 0) {
                    survivingByHull.put(hullId, rem - 1);
                } else {
                    lost += Math.max(0, m.getFleetPointCost());
                }
            }
            return lost;
        }
    }

    // ---- Live guest world ----------------------------------------------------------------------

    static final class LiveGuestWorld implements GuestWorld {

        @Override
        public void reconcile(BountySet set) {
            SectorAPI sector = sectorOrNull();
            if (sector == null || sector.getIntelManager() == null) {
                return;
            }
            IntelManagerAPI manager = sector.getIntelManager();

            // Person bounties
            Map<String, CoopPersonBountyIntel> existingPersons = new LinkedHashMap<>();
            for (IntelInfoPlugin plugin : manager.getIntel(CoopPersonBountyIntel.class)) {
                if (plugin instanceof CoopPersonBountyIntel pb && !pb.isEnded() && !pb.isEnding()) {
                    existingPersons.put(pb.bountyId(), pb);
                }
            }
            Set<String> wantedPersons = new HashSet<>();
            for (PersonBounty pb : set.personBounties()) {
                wantedPersons.add(pb.bountyId());
                CoopPersonBountyIntel existing = existingPersons.get(pb.bountyId());
                if (existing != null) {
                    existing.update(pb);
                } else {
                    CoopPersonBountyIntel created = new CoopPersonBountyIntel(pb);
                    manager.addIntel(created, false);
                }
            }
            for (Map.Entry<String, CoopPersonBountyIntel> entry : existingPersons.entrySet()) {
                if (!wantedPersons.contains(entry.getKey())) {
                    CoopPersonBountyIntel gone = entry.getValue();
                    gone.endImmediately();
                    manager.removeIntel(gone);
                }
            }

            // System bounties
            Map<String, CoopSystemBountyIntel> existingSystems = new LinkedHashMap<>();
            for (IntelInfoPlugin plugin : manager.getIntel(CoopSystemBountyIntel.class)) {
                if (plugin instanceof CoopSystemBountyIntel sb && !sb.isEnded() && !sb.isEnding()) {
                    existingSystems.put(sb.bountyId(), sb);
                }
            }
            Set<String> wantedSystems = new HashSet<>();
            for (SystemBounty sb : set.systemBounties()) {
                wantedSystems.add(sb.bountyId());
                CoopSystemBountyIntel existing = existingSystems.get(sb.bountyId());
                if (existing != null) {
                    existing.update(sb);
                } else {
                    CoopSystemBountyIntel created = new CoopSystemBountyIntel(sb);
                    manager.addIntel(created, false);
                }
            }
            for (Map.Entry<String, CoopSystemBountyIntel> entry : existingSystems.entrySet()) {
                if (!wantedSystems.contains(entry.getKey())) {
                    CoopSystemBountyIntel gone = entry.getValue();
                    gone.endImmediately();
                    manager.removeIntel(gone);
                }
            }
        }

        @Override
        public void clearMirrored() {
            SectorAPI sector = sectorOrNull();
            if (sector == null || sector.getIntelManager() == null) {
                return;
            }
            IntelManagerAPI manager = sector.getIntelManager();
            for (IntelInfoPlugin plugin : new ArrayList<>(manager.getIntel(CoopPersonBountyIntel.class))) {
                if (plugin instanceof CoopPersonBountyIntel pb) {
                    pb.endImmediately();
                    manager.removeIntel(pb);
                }
            }
            for (IntelInfoPlugin plugin : new ArrayList<>(manager.getIntel(CoopSystemBountyIntel.class))) {
                if (plugin instanceof CoopSystemBountyIntel sb) {
                    sb.endImmediately();
                    manager.removeIntel(sb);
                }
            }
        }
    }

    private static SectorAPI sectorOrNull() {
        try {
            return Global.getSector();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }
}
