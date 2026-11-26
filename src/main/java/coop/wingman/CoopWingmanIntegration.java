package coop.wingman;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import coop.config.CoopOptionsPolicy;
import coop.config.CoopOptionsRegistry;
import coop.config.CoopOptionsStore;
import coop.fleet.CoopGuestMirrorHandle;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopSessionState;
import coop.ui.CoopFeed;
import coop.util.CoopLog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Phase 34: Wingman battles inside a coop campaign session.
 *
 * <p>The coop mod owns the campaign; The Wingman Project owns the battle. When both are
 * installed and {@code coop.wingmanBattles} is on (default), the host opens its Wingman session
 * from the campaign map, the guest's Wingman client connects to the host's PC, and Wingman
 * serves every battle the host fights — so both players fly in the same battle instead of the
 * solo own-fleet model. The coop layer is unchanged on the host side (it still streams
 * {@code BATTLE_BEGIN/STATUS/END} and reconciles the result into the shared world, including
 * real losses on the guest's ships through the existing ally-battle outcome path).
 *
 * <p>Host responsibilities: open the Wingman session, pump it, learn the guest's Wingman name
 * (via the {@code WINGMAN_HELLO} coop message) and reserve the guest's mirror-fleet flagship for
 * that name (updating the reservation whenever the guest changes or loses their flagship), so
 * Wingman hands the guest their own ship whenever their fleet is deployed (Fight Alongside).
 *
 * <p>Guest responsibilities: report its Wingman name, and post a one-time hint telling the
 * player where to connect Wingman (Wingman has no programmatic join; one manual step).
 *
 * <p>Everything degrades to the vanilla coop behaviour when Wingman is absent, disabled, or any
 * call fails. All state is per-session and resets on a session id change.
 */
public final class CoopWingmanIntegration {

    public static final int DEFAULT_WINGMAN_PORT = 24816;
    /** Joiner names are matched ignoring case by Wingman; we compare the same way. */
    private static final int MAX_NAME_CHARS = 64;

    private static volatile CoopWingmanIntegration active;

    public static CoopWingmanIntegration active() {
        return active;
    }

    /** Installs an explicit active integration instance (used by wiring and cross-package tests). */
    public static void install(CoopWingmanIntegration integration) {
        active = integration;
    }

    /** Game-load teardown: clear the static handle so an old campaign never leaks into the next. */
    public static void uninstall() {
        CoopWingmanIntegration current = active;
        active = null;
        if (current != null) {
            current.reset();
        }
    }

    /** Production wiring. Registers this instance as the active integration for the bridge. */
    public static CoopWingmanIntegration create(CoopNetService service, CoopSessionState sessionState,
                                                LongSupplier clockMillis,
                                                Consumer<CoopMessages.Message> reliableSender) {
        return create(service::role, service::nextSeq, sessionState::sessionId, clockMillis,
                reliableSender, CoopWingmanIntegration::configuredConnectHost);
    }

    /** Production wiring with an explicit guest host-address supplier. */
    public static CoopWingmanIntegration create(CoopNetService service, CoopSessionState sessionState,
                                                LongSupplier clockMillis,
                                                Consumer<CoopMessages.Message> reliableSender,
                                                Supplier<String> connectHostSupplier) {
        return create(service::role, service::nextSeq, sessionState::sessionId, clockMillis,
                reliableSender, connectHostSupplier);
    }

    /** Production wiring with explicit seams. */
    public static CoopWingmanIntegration create(Supplier<CoopConnectionRole> roleSupplier,
                                                LongSupplier seqSupplier,
                                                Supplier<String> sessionIdSupplier,
                                                LongSupplier clockMillis,
                                                Consumer<CoopMessages.Message> reliableSender) {
        return create(roleSupplier, seqSupplier, sessionIdSupplier, clockMillis, reliableSender,
                CoopWingmanIntegration::configuredConnectHost);
    }

    /** Production wiring with explicit seams including connect-host resolution. */
    public static CoopWingmanIntegration create(Supplier<CoopConnectionRole> roleSupplier,
                                                LongSupplier seqSupplier,
                                                Supplier<String> sessionIdSupplier,
                                                LongSupplier clockMillis,
                                                Consumer<CoopMessages.Message> reliableSender,
                                                Supplier<String> connectHostSupplier) {
        CoopWingmanApi api = new ReflectiveCoopWingmanApi();
        BooleanSupplier enabledOption = () -> {
            CoopOptionsPolicy policy = CoopOptionsPolicy.active();
            return policy != null && policy.appliedBool(CoopOptionsRegistry.WINGMAN_BATTLES);
        };
        Function<String, String> flagshipByPlayer = playerId -> {
            CampaignFleetAPI mirror = (playerId != null && !playerId.isEmpty()
                    && !PRIMARY_GUEST_KEY.equals(playerId))
                    ? CoopGuestMirrorHandle.forPlayer(playerId)
                    : CoopGuestMirrorHandle.current();
            if (mirror == null) {
                mirror = CoopGuestMirrorHandle.current();
            }
            if (mirror == null) {
                return "";
            }
            FleetMemberAPI flagship = mirror.getFlagship();
            return flagship == null ? "" : flagship.getId();
        };
        Consumer<String> feedPoster = text -> CoopFeed.post(text, null);
        CoopWingmanIntegration integration = new CoopWingmanIntegration(roleSupplier, seqSupplier,
                sessionIdSupplier, clockMillis, api, enabledOption,
                () -> flagshipByPlayer.apply(PRIMARY_GUEST_KEY), flagshipByPlayer, reliableSender,
                feedPoster, connectHostSupplier == null
                        ? CoopWingmanIntegration::configuredConnectHost : connectHostSupplier);
        active = integration;
        return integration;
    }

    private static String configuredConnectHost() {
        try {
            String host = CoopOptionsStore.system().string(CoopOptionsRegistry.CONNECT_HOST);
            return host == null ? "" : host.trim();
        } catch (RuntimeException | LinkageError ex) {
            return "";
        }
    }

    private static final String PRIMARY_GUEST_KEY = "__primary__";

    private static final class GuestReservation {
        String wingmanName = "";
        String reservedName = "";
        String reservedMemberId = "";
        boolean feedPosted;
        boolean waitingLogged;
    }

    private final Supplier<CoopConnectionRole> roleSupplier;
    private final LongSupplier seqSupplier;
    private final Supplier<String> sessionIdSupplier;
    private final LongSupplier clockMillis;
    private final CoopWingmanApi api;
    private final BooleanSupplier enabledOption;
    private final Supplier<String> guestFlagshipMemberId;
    private final Function<String, String> guestFlagshipByPlayerId;
    private final Consumer<CoopMessages.Message> reliableSender;
    private final Consumer<String> feedPoster;
    private final Supplier<String> connectHostSupplier;

    private String lastSessionId = "";
    private boolean hostOpenedForSession;
    private boolean helloSentForSession;
    private boolean hintSentForSession;
    /** Phase 27: per-guest Wingman flagship reservations keyed by guest player id. */
    private final Map<String, GuestReservation> guestReservations = new LinkedHashMap<>();

    public CoopWingmanIntegration(Supplier<CoopConnectionRole> roleSupplier,
                                  LongSupplier seqSupplier, Supplier<String> sessionIdSupplier,
                                  LongSupplier clockMillis, CoopWingmanApi api,
                                  BooleanSupplier enabledOption,
                                  Supplier<String> guestFlagshipMemberId,
                                  Consumer<CoopMessages.Message> reliableSender,
                                  Consumer<String> feedPoster) {
        this(roleSupplier, seqSupplier, sessionIdSupplier, clockMillis, api, enabledOption,
                guestFlagshipMemberId, ignored -> guestFlagshipMemberId.get(), reliableSender,
                feedPoster, CoopWingmanIntegration::configuredConnectHost);
    }

    public CoopWingmanIntegration(Supplier<CoopConnectionRole> roleSupplier,
                                  LongSupplier seqSupplier, Supplier<String> sessionIdSupplier,
                                  LongSupplier clockMillis, CoopWingmanApi api,
                                  BooleanSupplier enabledOption,
                                  Supplier<String> guestFlagshipMemberId,
                                  Consumer<CoopMessages.Message> reliableSender,
                                  Consumer<String> feedPoster,
                                  Supplier<String> connectHostSupplier) {
        this(roleSupplier, seqSupplier, sessionIdSupplier, clockMillis, api, enabledOption,
                guestFlagshipMemberId, ignored -> guestFlagshipMemberId.get(), reliableSender,
                feedPoster, connectHostSupplier);
    }

    public CoopWingmanIntegration(Supplier<CoopConnectionRole> roleSupplier,
                                  LongSupplier seqSupplier, Supplier<String> sessionIdSupplier,
                                  LongSupplier clockMillis, CoopWingmanApi api,
                                  BooleanSupplier enabledOption,
                                  Supplier<String> guestFlagshipMemberId,
                                  Function<String, String> guestFlagshipByPlayerId,
                                  Consumer<CoopMessages.Message> reliableSender,
                                  Consumer<String> feedPoster,
                                  Supplier<String> connectHostSupplier) {
        this.roleSupplier = Objects.requireNonNull(roleSupplier, "roleSupplier");
        this.seqSupplier = Objects.requireNonNull(seqSupplier, "seqSupplier");
        this.sessionIdSupplier = Objects.requireNonNull(sessionIdSupplier, "sessionIdSupplier");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.api = Objects.requireNonNull(api, "api");
        this.enabledOption = Objects.requireNonNull(enabledOption, "enabledOption");
        this.guestFlagshipMemberId =
                Objects.requireNonNull(guestFlagshipMemberId, "guestFlagshipMemberId");
        this.guestFlagshipByPlayerId = Objects.requireNonNull(guestFlagshipByPlayerId,
                "guestFlagshipByPlayerId");
        this.reliableSender = Objects.requireNonNull(reliableSender, "reliableSender");
        this.feedPoster = Objects.requireNonNull(feedPoster, "feedPoster");
        this.connectHostSupplier =
                Objects.requireNonNull(connectHostSupplier, "connectHostSupplier");
    }

    /** True when the Wingman option is enabled and the Wingman mod/API is active on this install. */
    public boolean isEnabledAndAvailable() {
        return enabledOption.getAsBoolean() && api.wingmanEnabled();
    }

    // ---- per-frame tick (campaign frames, both roles) -----------------------------------------

    /** Never throws; called once per pump frame. */
    public void tick() {
        try {
            if (!enabledOption.getAsBoolean() || !api.wingmanEnabled()) {
                return;
            }
            String sessionId = sessionIdOrEmpty();
            if (sessionId.isEmpty() || !sessionId.equals(lastSessionId)) {
                resetForSession(sessionId);
            }
            if (sessionId.isEmpty()) {
                return;
            }
            if (roleSupplier.get() == CoopConnectionRole.HOST) {
                tickHost(sessionId);
            } else if (roleSupplier.get() == CoopConnectionRole.GUEST) {
                tickGuest(sessionId);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopWingmanIntegration.class, "Coop wingman integration frame failed", ex);
        }
    }

    private void tickHost(String sessionId) {
        if (!hostOpenedForSession) {
            hostOpenedForSession = true;
            boolean opened = api.openHost();
            CoopLog.info(CoopWingmanIntegration.class,
                    "Coop wingman: host session " + (opened ? "opened" : "could not open")
                            + " for coop session " + sessionId);
        }
        String lines = api.pumpHost();
        if (!lines.isEmpty()) {
            CoopLog.info(CoopWingmanIntegration.class,
                    "Coop wingman: " + lines.replace('\n', ' '));
        }
        if (!guestReservations.isEmpty()) {
            maybeReserveAll();
        }
    }

    private void maybeReserveAll() {
        List<String> names = null;
        for (Map.Entry<String, GuestReservation> entry : guestReservations.entrySet()) {
            String playerId = entry.getKey();
            GuestReservation state = entry.getValue();
            if (state.wingmanName.isEmpty()) {
                continue;
            }
            String memberId = PRIMARY_GUEST_KEY.equals(playerId)
                    ? guestFlagshipMemberId.get()
                    : guestFlagshipByPlayerId.apply(playerId);
            if (memberId == null || memberId.isEmpty()) {
                if (!state.waitingLogged) {
                    state.waitingLogged = true;
                    CoopLog.info(CoopWingmanIntegration.class,
                            "Coop wingman: waiting for guest mirror flagship to reserve for "
                                    + state.wingmanName);
                }
                continue;
            }
            if (state.wingmanName.equalsIgnoreCase(state.reservedName)
                    && memberId.equals(state.reservedMemberId)) {
                continue;
            }
            if (names == null) {
                names = api.playerNames();
            }
            for (String name : names) {
                if (name != null && name.equalsIgnoreCase(state.wingmanName)) {
                    if (!state.reservedMemberId.isEmpty() && !memberId.equals(state.reservedMemberId)) {
                        api.reserveShip(null, state.reservedMemberId);
                    }
                    api.reserveShip(state.wingmanName, memberId);
                    state.reservedName = state.wingmanName;
                    state.reservedMemberId = memberId;
                    state.waitingLogged = false;
                    CoopLog.info(CoopWingmanIntegration.class,
                            "Coop wingman: reserved the guest flagship " + memberId + " for "
                                    + state.wingmanName);
                    if (!state.feedPosted) {
                        state.feedPosted = true;
                        feedPoster.accept("Co-op: Wingman battles are on - " + state.wingmanName
                                + " will get their flagship when it deploys.");
                    }
                    break;
                }
            }
        }
    }

    private void tickGuest(String sessionId) {
        if (!helloSentForSession) {
            String name = api.localName();
            if (!name.isEmpty()) {
                helloSentForSession = true;
                reliableSender.accept(CoopMessages.wingmanHello(sessionId, seqSupplier.getAsLong(),
                        clockMillis.getAsLong(), name));
            }
        }
        if (!hintSentForSession) {
            hintSentForSession = true;
            String host = "";
            try {
                String raw = connectHostSupplier.get();
                host = raw == null ? "" : raw.trim();
            } catch (RuntimeException | LinkageError ex) {
                host = "";
            }
            if (!host.isEmpty() && !api.isJoined() && api.supportsAutoJoin()
                    && api.join(host, DEFAULT_WINGMAN_PORT)) {
                CoopLog.info(CoopWingmanIntegration.class,
                        "Coop wingman: auto-joined " + host + ":" + DEFAULT_WINGMAN_PORT);
                feedPoster.accept("Co-op: Wingman auto-connected to " + host + ":"
                        + DEFAULT_WINGMAN_PORT + " for shared battles.");
                return;
            }
            feedPoster.accept(host.isEmpty()
                    ? "Co-op: Wingman battles are on - open Wingman's CO-OP panel (title screen) "
                            + "and connect to the host's PC on port " + DEFAULT_WINGMAN_PORT
                            + " to fly in battles."
                    : "Co-op: Wingman battles are on - open Wingman's CO-OP panel (title screen) "
                            + "and connect to " + host + ":" + DEFAULT_WINGMAN_PORT
                            + " to fly in battles.");
        }
    }

    private void resetForSession(String sessionId) {
        boolean clearingPreviousSession = sessionId.isEmpty() || !lastSessionId.isEmpty();
        if (clearingPreviousSession && api.wingmanEnabled()) {
            if (roleSupplier.get() == CoopConnectionRole.HOST) {
                for (GuestReservation state : guestReservations.values()) {
                    if (!state.reservedMemberId.isEmpty()) {
                        try {
                            api.reserveShip(null, state.reservedMemberId);
                        } catch (RuntimeException | LinkageError ignored) {
                            // best-effort teardown
                        }
                    }
                }
                if (sessionId.isEmpty() && hostOpenedForSession) {
                    api.closeHost();
                }
            } else if (roleSupplier.get() == CoopConnectionRole.GUEST && sessionId.isEmpty()
                    && api.isJoined()) {
                api.disconnect();
            }
        }
        lastSessionId = sessionId;
        hostOpenedForSession = false;
        helloSentForSession = false;
        hintSentForSession = false;
        if (clearingPreviousSession) {
            guestReservations.clear();
        }
    }

    private String sessionIdOrEmpty() {
        String id = sessionIdSupplier.get();
        return id == null ? "" : id;
    }

    // ---- WINGMAN_HELLO (host side) --------------------------------------------------------------

    /** Host: the primary guest reported its Wingman panel name. */
    public void onGuestWingmanName(String name) {
        onGuestWingmanName(PRIMARY_GUEST_KEY, name);
    }

    /** Phase 27: host records a specific guest's Wingman panel name for independent flagship reservation. */
    public void onGuestWingmanName(String guestPlayerId, String name) {
        if (roleSupplier.get() != CoopConnectionRole.HOST || name == null) {
            return;
        }
        String key = (guestPlayerId == null || guestPlayerId.trim().isEmpty())
                ? PRIMARY_GUEST_KEY : guestPlayerId.trim();
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_CHARS) {
            trimmed = trimmed.substring(0, MAX_NAME_CHARS);
        }
        GuestReservation state = guestReservations.computeIfAbsent(key, ignored -> new GuestReservation());
        if (!trimmed.equalsIgnoreCase(state.wingmanName) && !state.reservedMemberId.isEmpty()
                && api.wingmanEnabled()) {
            try {
                api.reserveShip(null, state.reservedMemberId);
            } catch (RuntimeException | LinkageError ignored) {
                // best-effort clear before re-reserving under the new name
            }
            state.reservedName = "";
            state.reservedMemberId = "";
        }
        state.wingmanName = trimmed;
        state.waitingLogged = false;
        CoopLog.info(CoopWingmanIntegration.class,
                "Coop wingman: guest (" + key + ") wingman name is \"" + trimmed + "\"");
    }

    /** Phase 27: releases a departing guest's Wingman reservation without touching other guests. */
    public void removeGuest(String guestPlayerId) {
        if (guestPlayerId == null || guestPlayerId.trim().isEmpty()) {
            return;
        }
        GuestReservation removed = guestReservations.remove(guestPlayerId.trim());
        if (removed != null && !removed.reservedMemberId.isEmpty()
                && roleSupplier.get() == CoopConnectionRole.HOST && api.wingmanEnabled()) {
            try {
                api.reserveShip(null, removed.reservedMemberId);
            } catch (RuntimeException | LinkageError ignored) {
                // best-effort cleanup
            }
        }
    }

    // ---- combat-frame consultation (guest side, via CoopBattleBridge) ---------------------------

    /**
     * The guest's combat frames in a campaign battle while this is true are Wingman join-battle
     * frames, not the guest's own engagement: the bridge must stay a spectator.
     *
     * <p>Gates: Wingman battles are enabled in campaign policy, this client is the guest, a coop
     * session is live, Wingman is installed/enabled, and this client is connected to a Wingman
     * host. The bridge adds the battle-side conditions (a campaign battle, no local battle, and
     * either the host's battle known via {@code BATTLE_BEGIN} or a synthetic Wingman battle with no
     * local mirrored opponents).
     */
    public boolean guestCombatIsWingmanJoin() {
        return enabledOption.getAsBoolean()
                && roleSupplier.get() == CoopConnectionRole.GUEST
                && !sessionIdOrEmpty().isEmpty()
                && api.wingmanEnabled()
                && api.isJoined();
    }

    /** Pure decision helper for the bridge, unit-tested. */
    public static boolean classifyGuestWingmanJoin(boolean localBattleActive,
                                                   boolean remoteBattleActive,
                                                   boolean guestRole,
                                                   boolean wingmanJoined) {
        return classifyGuestWingmanJoin(localBattleActive, remoteBattleActive, guestRole,
                wingmanJoined, false);
    }

    /**
     * Extended decision helper that also handles the TCP race where Wingman transitions the guest
     * into combat before the coop channel's {@code BATTLE_BEGIN} is read on the campaign thread.
     *
     * @param syntheticNonLocalBattle true when the guest has no active local handoff/customs
     *                                dialog and none of the combat opponents are local mirrored
     *                                NPC fleets or Dweller encounter fleets
     */
    public static boolean classifyGuestWingmanJoin(boolean localBattleActive,
                                                   boolean remoteBattleActive,
                                                   boolean guestRole,
                                                   boolean wingmanJoined,
                                                   boolean syntheticNonLocalBattle) {
        if (localBattleActive || !guestRole || !wingmanJoined) {
            return false;
        }
        return remoteBattleActive || syntheticNonLocalBattle;
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    /** Session ended or the pump went away: forget per-session state. */
    public void reset() {
        resetForSession("");
    }
}
