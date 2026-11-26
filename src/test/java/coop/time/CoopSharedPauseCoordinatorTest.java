package coop.time;

import coop.net.CoopMessages;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopSharedPauseCoordinatorTest {

    @Test
    void effectivePausedIsOrOfHostGuestKeyGuestScreenAndCombatIntents() {
        for (int mask = 0; mask < 16; mask++) {
            boolean host = (mask & 1) != 0;
            boolean key = (mask & 2) != 0;
            boolean screen = (mask & 4) != 0;
            boolean combat = (mask & 8) != 0;

            CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();
            coordinator.setHostPauseIntent(host);
            // Distinct, increasing seqs so both guest intents are applied (last-writer-wins debounce).
            coordinator.applyGuestKeyPauseIntent(key, 1L);
            coordinator.applyGuestScreenPauseIntent(screen, 2L);
            coordinator.setEitherInCombat(combat);

            boolean expected = host || key || screen || combat;
            assertEquals(expected, coordinator.effectivePaused(),
                    "host=" + host + " key=" + key + " screen=" + screen + " combat=" + combat);
        }
    }

    @Test
    void guestPauseKeyPressIsForwardedAsOppositeOfObservedState() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();

        // Running -> a press wants to pause.
        coordinator.setObservedPaused(false);
        coordinator.recordGuestPauseKeyPress();
        assertTrue(coordinator.consumeGuestKeyPress());
        assertTrue(coordinator.desiredKeyPause());
        // Press is consumed once.
        assertFalse(coordinator.consumeGuestKeyPress());

        // Paused -> a press wants to run.
        coordinator.setObservedPaused(true);
        coordinator.recordGuestPauseKeyPress();
        assertTrue(coordinator.consumeGuestKeyPress());
        assertFalse(coordinator.desiredKeyPause());
    }

    @Test
    void guestScreenLevelChangeDetection() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();

        assertTrue(coordinator.updateGuestScreenLevel(true));   // opened -> changed
        assertFalse(coordinator.updateGuestScreenLevel(true));  // still open -> no change
        assertTrue(coordinator.updateGuestScreenLevel(false));  // closed -> changed
        assertFalse(coordinator.updateGuestScreenLevel(false)); // still closed -> no change
    }

    @Test
    void hostMayOverrideGuestKeyPauseButNotGuestScreenPause() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();
        coordinator.applyGuestKeyPauseIntent(true, 1L);
        coordinator.applyGuestScreenPauseIntent(true, 2L);
        coordinator.setObservedPaused(true);

        // Host taps pause while paused: clears its own intent + the guest's manual (key) pause...
        coordinator.onHostPauseKey();

        assertFalse(coordinator.hostPauseIntent());
        assertFalse(coordinator.guestKeyPauseIntent());
        // ...but the guest's screen pause survives, so the world stays paused.
        assertTrue(coordinator.guestScreenPauseIntent());
        assertTrue(coordinator.effectivePaused());
    }

    @Test
    void hostPauseKeyAssertsPauseWhenRunning() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();
        coordinator.setObservedPaused(false);

        coordinator.onHostPauseKey();

        assertTrue(coordinator.hostPauseIntent());
        assertTrue(coordinator.effectivePaused());
    }

    @Test
    void guestPauseIntentAppliesLastWriterWinsBySeq() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();

        assertTrue(coordinator.applyGuestKeyPauseIntent(true, 5L));
        assertTrue(coordinator.guestKeyPauseIntent());

        // Stale (lower) seq is ignored.
        assertFalse(coordinator.applyGuestKeyPauseIntent(false, 3L));
        assertTrue(coordinator.guestKeyPauseIntent());

        // Equal seq is ignored.
        assertFalse(coordinator.applyGuestKeyPauseIntent(false, 5L));
        assertTrue(coordinator.guestKeyPauseIntent());

        // Newer seq wins (and the seq counter is shared with screen intents).
        assertTrue(coordinator.applyGuestScreenPauseIntent(true, 6L));
        assertFalse(coordinator.applyGuestKeyPauseIntent(false, 6L));
        assertTrue(coordinator.applyGuestKeyPauseIntent(false, 7L));
        assertFalse(coordinator.guestKeyPauseIntent());
    }

    @Test
    void forceClearGuestKeyPauseLeavesScreenPause() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();
        coordinator.applyGuestKeyPauseIntent(true, 1L);
        coordinator.applyGuestScreenPauseIntent(true, 2L);

        coordinator.forceClearGuestKeyPauseIntent();

        assertFalse(coordinator.guestKeyPauseIntent());
        assertTrue(coordinator.guestScreenPauseIntent());
    }

    @Test
    void nextLocalSeqIsMonotonic() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();
        assertEquals(1L, coordinator.nextLocalSeq());
        assertEquals(2L, coordinator.nextLocalSeq());
        assertEquals(3L, coordinator.nextLocalSeq());
    }

    @Test
    void resetClearsAllIntentState() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();
        coordinator.setHostPauseIntent(true);
        coordinator.applyGuestKeyPauseIntent(true, 9L);
        coordinator.applyGuestScreenPauseIntent(true, 10L);
        coordinator.setEitherInCombat(true);
        coordinator.updateGuestScreenLevel(true);
        coordinator.recordGuestPauseKeyPress();
        coordinator.setObservedPaused(true);
        coordinator.nextLocalSeq();

        coordinator.reset();

        assertFalse(coordinator.hostPauseIntent());
        assertFalse(coordinator.guestKeyPauseIntent());
        assertFalse(coordinator.guestScreenPauseIntent());
        assertFalse(coordinator.eitherInCombat());
        assertFalse(coordinator.guestScreenLevel());
        assertFalse(coordinator.consumeGuestKeyPress());
        assertFalse(coordinator.observedPaused());
        assertFalse(coordinator.effectivePaused());
        // The applied-seq tracker is reset, so the next intent (any seq) is accepted again.
        assertTrue(coordinator.applyGuestKeyPauseIntent(true, 1L));
        assertEquals(1L, coordinator.nextLocalSeq());
    }

    @Test
    void guestKeyPressDoesNotQueueAPhantomPauseWhileHostHoldsTheWorldPaused() {
        // Regression: host holds the world paused; the guest taps pause meaning "let me run". The
        // forwarded value is the opposite of the observed (paused) state -> false, so no phantom pause
        // is asserted on the host.
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();
        coordinator.setObservedPaused(true); // guest clock mirrors the host's paused clock

        coordinator.recordGuestPauseKeyPress();

        assertTrue(coordinator.consumeGuestKeyPress());
        assertFalse(coordinator.desiredKeyPause());
    }

    @Test
    void pauseIntentMessageRoundTrips() {
        CoopMessages.Message message = CoopMessages.pauseIntent(
                "session-z", 4L, 7000L, CoopMessages.PauseSource.KEY, true, 11L);
        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(message));

        assertEquals(CoopMessages.Type.PAUSE_INTENT, decoded.type());
        assertEquals("session-z", decoded.sessionId());
        assertEquals(4L, decoded.seq());
        assertEquals(7000L, decoded.sentAtMillis());
        assertEquals("KEY", CoopMessages.requiredPayloadString(decoded, "source"));
        assertEquals("true", CoopMessages.requiredPayloadString(decoded, "paused"));
        assertEquals(11L, CoopMessages.requiredPayloadLong(decoded, "intentSeq"));
    }

    // ---- Phase 20.2 reconnect hold ----------------------------------------------------------------

    @Test
    void theReconnectHoldPausesTheWorldAndNoPauseKeyClearsIt() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();
        coordinator.setReconnectHold(true);

        assertTrue(coordinator.reconnectHold());
        assertTrue(coordinator.effectivePaused());

        // The host tapping pause resolves against the observed (paused) clock and clears its own
        // intent -- but the hold is not a player's choice and survives.
        coordinator.setObservedPaused(true);
        coordinator.onHostPauseKey();

        assertFalse(coordinator.hostPauseIntent());
        assertTrue(coordinator.effectivePaused(), "only the grace window closing releases the hold");

        coordinator.setReconnectHold(false);
        assertFalse(coordinator.effectivePaused());
    }

    @Test
    void aSessionResetClearsTheReconnectHoldWithEverythingElse() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();
        coordinator.setReconnectHold(true);

        coordinator.reset();

        assertFalse(coordinator.reconnectHold());
        assertFalse(coordinator.effectivePaused());
    }

    // ---- Phase 25: allowGuestPause + global any-player pause & fast-forward toggle ----------------

    @Test
    void allowGuestPauseFalseIgnoresGuestKeyPauseButKeepsScreenCombatAndReconnectPauses() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();
        coordinator.setAllowGuestPause(false);

        coordinator.applyGuestKeyPauseIntent(true, 1L);
        assertFalse(coordinator.effectivePaused(), "manual guest pause is ignored when disallowed");

        coordinator.applyGuestScreenPauseIntent(true, 2L);
        assertTrue(coordinator.effectivePaused(), "screen pause is never gated by allowGuestPause");

        coordinator.applyGuestScreenPauseIntent(false, 3L);
        coordinator.setEitherInCombat(true);
        assertTrue(coordinator.effectivePaused(), "combat pause is never gated");

        coordinator.setEitherInCombat(false);
        coordinator.setReconnectHold(true);
        assertTrue(coordinator.effectivePaused(), "reconnect hold is never gated");
    }

    @Test
    void guestUnpauseToggleClearsHostManualPauseWhenAllowedButNotScreenOrCombat() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();
        coordinator.setHostPauseIntent(true);
        assertTrue(coordinator.effectivePaused());

        assertTrue(coordinator.applyGuestKeyPauseToggle("guest-1", false, 1L));
        assertFalse(coordinator.hostPauseIntent());
        assertFalse(coordinator.effectivePaused());

        // When disallowed, guest cannot unpause host manual pause either.
        coordinator.setAllowGuestPause(false);
        coordinator.setHostPauseIntent(true);
        assertTrue(coordinator.applyGuestKeyPauseToggle("guest-1", false, 2L));
        assertTrue(coordinator.hostPauseIntent());
        assertTrue(coordinator.effectivePaused());

        // Even when allowed, guest key unpause never clears combat or screen holds.
        coordinator.setAllowGuestPause(true);
        coordinator.applyGuestScreenPauseIntent("guest-2", true, 1L);
        assertTrue(coordinator.applyGuestKeyPauseToggle("guest-1", false, 3L));
        assertFalse(coordinator.hostPauseIntent());
        assertTrue(coordinator.guestScreenPauseIntent());
        assertTrue(coordinator.effectivePaused());
    }

    @Test
    void anyPlayerCanToggleGlobalFastForwardDirectlyWithoutConfirmation() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();

        // Guest presses Shift while observing 1x -> desired is true, host applies immediately.
        coordinator.setObservedFastForward(false);
        coordinator.recordGuestFastForwardKeyPress();
        assertTrue(coordinator.consumeGuestFastForwardKeyPress());
        assertFalse(coordinator.consumeGuestFastForwardKeyPress());
        assertTrue(coordinator.desiredFastForward());

        assertTrue(coordinator.applyGuestFastForwardIntent("guest-1", true, 1L));
        assertTrue(coordinator.effectiveFastForward());

        // Host presses Shift while observing 2x -> toggles off immediately.
        coordinator.setObservedFastForward(true);
        coordinator.onHostFastForwardKey();
        assertFalse(coordinator.effectiveFastForward());

        // Stale seq is ignored.
        assertFalse(coordinator.applyGuestFastForwardIntent("guest-1", true, 1L));
        assertFalse(coordinator.effectiveFastForward());

        // Reconnect hold clears fast-forward; shared pause does not.
        assertTrue(coordinator.applyGuestFastForwardIntent("guest-1", true, 2L));
        coordinator.setHostPauseIntent(true);
        assertTrue(coordinator.effectiveFastForward(), "FF survives a shared pause");
        coordinator.setReconnectHold(true);
        assertFalse(coordinator.effectiveFastForward(), "reconnect hold clears FF");
    }

    @Test
    void ffIntentMessageRoundTrips() {
        CoopMessages.Message message = CoopMessages.ffIntent("session-z", 5L, 8000L, true, 12L);
        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(message));

        assertEquals(CoopMessages.Type.FF_INTENT, decoded.type());
        assertEquals("session-z", decoded.sessionId());
        assertEquals("true", CoopMessages.requiredPayloadString(decoded, "fastForward"));
        assertEquals(12L, CoopMessages.requiredPayloadLong(decoded, "intentSeq"));
    }
}
