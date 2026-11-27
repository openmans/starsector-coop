package coop.time;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared pause and fast-forward coordinator (Phases 11, 25, 27).
 *
 * <p><b>Authority model.</b> The host is authoritative. It tracks pause intents across the host and
 * every connected guest (Phase 27 multi-guest ready) and computes the effective pause as their OR:
 * <pre>effectivePaused = hostPauseIntent || (allowGuestPause &amp;&amp; anyGuestKeyPause)
 *                       || anyGuestScreenPause || eitherInCombat || reconnectHold</pre>
 * The host applies {@code effectivePaused} to its {@code Sector} clock and {@code effectiveFastForward}
 * to {@code CampaignState.fastForward}; both ride {@code TIME_SNAPSHOT} to every guest.
 *
 * <p><b>Global control by any player (Phase 25).</b> Both manual pause (when {@code allowGuestPause}
 * is {@code true}) and fast-forward work the same way: any player pressing Spacebar or Shift toggles
 * the global state against what they currently observe, with no confirmation wait. Screen pauses,
 * combat pauses, and reconnect holds remain non-overridable correctness holds.
 */
public final class CoopSharedPauseCoordinator {

    private static final String DEFAULT_GUEST_KEY = "guest";

    // ---- Host authority state ----
    private boolean hostPauseIntent;
    private boolean allowGuestPause = true;
    private final Map<String, Boolean> guestKeyPauseIntents = new HashMap<>();
    private final Map<String, Boolean> guestScreenPauseIntents = new HashMap<>();
    private final Map<String, Long> appliedGuestSeqs = new HashMap<>();
    private boolean eitherInCombat;
    private boolean liveWorldInCombat;
    private boolean reconnectHold;
    private boolean dedicatedEmptyHold;

    // ---- Phase 25: global fast-forward state (any player toggles directly) ----
    private boolean effectiveFastForward;
    private boolean observedFastForward;
    private boolean pendingGuestFfKeyPress;

    // ---- Guest local outgoing state ----
    private boolean guestScreenLevel;
    private boolean pendingGuestKeyPress;
    private long localSeqCounter;

    // ---- Shared: the pause state this client currently observes on its own clock ----
    private boolean observedPaused;

    /* ===================== Host side: pause ===================== */

    public synchronized void setAllowGuestPause(boolean allowed) {
        allowGuestPause = allowed;
    }

    public synchronized boolean allowGuestPause() {
        return allowGuestPause;
    }

    public synchronized void setHostPauseIntent(boolean paused) {
        hostPauseIntent = paused;
    }

    /**
     * Host pressed the pause key. Resolved against the observed pause state:
     * running &rarr; assert host pause; paused &rarr; clear host pause and all guests' manual key
     * pauses (never clears screen pauses, combat, or reconnect hold).
     */
    public synchronized void onHostPauseKey() {
        if (observedPaused) {
            hostPauseIntent = false;
            guestKeyPauseIntents.clear();
        } else {
            hostPauseIntent = true;
        }
    }

    public synchronized boolean hostPauseIntent() {
        return hostPauseIntent;
    }

    /** Single-guest compatibility overload. */
    public synchronized boolean applyGuestKeyPauseIntent(boolean paused, long seq) {
        return applyGuestKeyPauseIntent(DEFAULT_GUEST_KEY, paused, seq);
    }

    /**
     * Records a guest's manual key pause intent with per-sender last-writer-wins debounce.
     * Used directly by low-level tests and truth tables without altering {@code hostPauseIntent}.
     */
    public synchronized boolean applyGuestKeyPauseIntent(String senderId, boolean paused, long seq) {
        String key = normalizeSender(senderId);
        if (seq <= appliedGuestSeqs.getOrDefault(key, Long.MIN_VALUE)) {
            return false;
        }
        appliedGuestSeqs.put(key, seq);
        if (paused) {
            guestKeyPauseIntents.put(key, Boolean.TRUE);
        } else {
            guestKeyPauseIntents.remove(key);
        }
        return true;
    }

    /**
     * Production guest pause-key toggle handler: when {@link #allowGuestPause()} is enabled and a
     * guest requests unpause ({@code paused == false}), clears both guest manual pauses and
     * {@code hostPauseIntent} so any player can pause and unpause the shared world equally. Screen
     * pauses, combat, and reconnect holds are never cleared here.
     */
    public synchronized boolean applyGuestKeyPauseToggle(String senderId, boolean paused, long seq) {
        String key = normalizeSender(senderId);
        if (seq <= appliedGuestSeqs.getOrDefault(key, Long.MIN_VALUE)) {
            return false;
        }
        appliedGuestSeqs.put(key, seq);
        if (!allowGuestPause) {
            return true;
        }
        if (paused) {
            guestKeyPauseIntents.put(key, Boolean.TRUE);
        } else {
            guestKeyPauseIntents.clear();
            hostPauseIntent = false;
        }
        return true;
    }

    /** Single-guest compatibility overload. */
    public synchronized boolean applyGuestScreenPauseIntent(boolean paused, long seq) {
        return applyGuestScreenPauseIntent(DEFAULT_GUEST_KEY, paused, seq);
    }

    /** Records a guest's screen-open pause intent with per-sender last-writer-wins debounce. */
    public synchronized boolean applyGuestScreenPauseIntent(String senderId, boolean paused, long seq) {
        String key = normalizeSender(senderId);
        if (seq <= appliedGuestSeqs.getOrDefault(key, Long.MIN_VALUE)) {
            return false;
        }
        appliedGuestSeqs.put(key, seq);
        if (paused) {
            guestScreenPauseIntents.put(key, Boolean.TRUE);
        } else {
            guestScreenPauseIntents.remove(key);
        }
        return true;
    }

    /** Clears all pause intents and seq watermark belonging to one departing guest (Phase 27). */
    public synchronized void removeGuest(String senderId) {
        String key = normalizeSender(senderId);
        guestKeyPauseIntents.remove(key);
        guestScreenPauseIntents.remove(key);
        appliedGuestSeqs.remove(key);
    }

    public synchronized boolean guestKeyPauseIntent() {
        return !guestKeyPauseIntents.isEmpty();
    }

    public synchronized boolean guestScreenPauseIntent() {
        return !guestScreenPauseIntents.isEmpty();
    }

    /** Clears all guest seq watermarks (single-guest resume or full reconnect). */
    public synchronized void resetGuestIntentWatermark() {
        appliedGuestSeqs.clear();
    }

    /** Clears one guest's seq watermark on that peer's resume (Phase 27). */
    public synchronized void resetGuestIntentWatermark(String senderId) {
        appliedGuestSeqs.remove(normalizeSender(senderId));
    }

    /** Host override: force-clear all guests' manual (key) pauses; never screen pauses. */
    public synchronized void forceClearGuestKeyPauseIntent() {
        guestKeyPauseIntents.clear();
    }

    public synchronized void setEitherInCombat(boolean inCombat) {
        eitherInCombat = inCombat;
        if (inCombat) {
            effectiveFastForward = false;
        }
    }

    public synchronized boolean eitherInCombat() {
        return eitherInCombat;
    }

    public synchronized void setLiveWorldInCombat(boolean live) {
        liveWorldInCombat = live;
    }

    public synchronized boolean liveWorldInCombat() {
        return liveWorldInCombat;
    }

    public synchronized void setDedicatedEmptyHold(boolean held) {
        dedicatedEmptyHold = held;
        if (held) {
            effectiveFastForward = false;
        }
    }

    public synchronized boolean dedicatedEmptyHold() {
        return dedicatedEmptyHold;
    }

    public synchronized void setReconnectHold(boolean held) {
        reconnectHold = held;
        if (held) {
            effectiveFastForward = false;
        }
    }

    public synchronized boolean reconnectHold() {
        return reconnectHold;
    }

    /** The host-authoritative effective pause: the OR of all active intents. */
    public synchronized boolean effectivePaused() {
        return hostPauseIntent
                || (allowGuestPause && !guestKeyPauseIntents.isEmpty())
                || !guestScreenPauseIntents.isEmpty()
                || (!liveWorldInCombat && eitherInCombat)
                || reconnectHold
                || dedicatedEmptyHold;
    }

    /* ===================== Phase 25: global fast-forward ===================== */

    /**
     * Host pressed the fast-forward key: immediately toggles global fast-forward against what the
     * host currently observes.
     */
    public synchronized void onHostFastForwardKey() {
        if (eitherInCombat || reconnectHold || dedicatedEmptyHold) {
            effectiveFastForward = false;
            return;
        }
        effectiveFastForward = !observedFastForward;
    }

    /** Single-guest compatibility overload. */
    public synchronized boolean applyGuestFastForwardIntent(boolean fastForward, long seq) {
        return applyGuestFastForwardIntent(DEFAULT_GUEST_KEY, fastForward, seq);
    }

    /**
     * Guest pressed the fast-forward key: immediately applies the requested global fast-forward
     * state with per-sender last-writer-wins debounce (shares the monotonic per-guest seq counter).
     */
    public synchronized boolean applyGuestFastForwardIntent(String senderId, boolean fastForward, long seq) {
        String key = normalizeSender(senderId);
        if (seq <= appliedGuestSeqs.getOrDefault(key, Long.MIN_VALUE)) {
            return false;
        }
        appliedGuestSeqs.put(key, seq);
        effectiveFastForward = !(eitherInCombat || reconnectHold || dedicatedEmptyHold) && fastForward;
        return true;
    }

    public synchronized void setEffectiveFastForward(boolean fastForward) {
        effectiveFastForward = !(eitherInCombat || reconnectHold || dedicatedEmptyHold) && fastForward;
    }

    public synchronized boolean effectiveFastForward() {
        if (eitherInCombat || reconnectHold || dedicatedEmptyHold) {
            return false;
        }
        return effectiveFastForward;
    }

    public synchronized void setObservedFastForward(boolean fastForward) {
        observedFastForward = fastForward;
    }

    public synchronized boolean observedFastForward() {
        return observedFastForward;
    }

    /** Guest pressed Shift (FAST_FORWARD): record edge to be forwarded as {@code FF_INTENT}. */
    public synchronized void recordGuestFastForwardKeyPress() {
        pendingGuestFfKeyPress = true;
    }

    /** Pump: consume a pending guest fast-forward key press (true once per press). */
    public synchronized boolean consumeGuestFastForwardKeyPress() {
        boolean pending = pendingGuestFfKeyPress;
        pendingGuestFfKeyPress = false;
        return pending;
    }

    /** The fast-forward value to send for a Shift press: the opposite of what the guest observes. */
    public synchronized boolean desiredFastForward() {
        return !observedFastForward;
    }

    /* ===================== Guest side: pause ===================== */

    public synchronized void recordGuestPauseKeyPress() {
        pendingGuestKeyPress = true;
    }

    public synchronized boolean consumeGuestKeyPress() {
        boolean pending = pendingGuestKeyPress;
        pendingGuestKeyPress = false;
        return pending;
    }

    public synchronized boolean desiredKeyPause() {
        return !observedPaused;
    }

    public synchronized boolean updateGuestScreenLevel(boolean open) {
        if (open == guestScreenLevel) {
            return false;
        }
        guestScreenLevel = open;
        return true;
    }

    public synchronized boolean guestScreenLevel() {
        return guestScreenLevel;
    }

    public synchronized void setObservedPaused(boolean paused) {
        observedPaused = paused;
    }

    public synchronized boolean observedPaused() {
        return observedPaused;
    }

    public synchronized long nextLocalSeq() {
        return ++localSeqCounter;
    }

    /** Clear all transient intent state on session end so nothing leaks across sessions. */
    public synchronized void reset() {
        hostPauseIntent = false;
        allowGuestPause = true;
        guestKeyPauseIntents.clear();
        guestScreenPauseIntents.clear();
        appliedGuestSeqs.clear();
        eitherInCombat = false;
        liveWorldInCombat = false;
        reconnectHold = false;
        dedicatedEmptyHold = false;
        effectiveFastForward = false;
        observedFastForward = false;
        pendingGuestFfKeyPress = false;
        guestScreenLevel = false;
        pendingGuestKeyPress = false;
        localSeqCounter = 0L;
        observedPaused = false;
    }

    private static String normalizeSender(String senderId) {
        return (senderId == null || senderId.isBlank()) ? DEFAULT_GUEST_KEY : senderId;
    }
}
