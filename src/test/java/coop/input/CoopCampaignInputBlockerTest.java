package coop.input;

import com.fs.starfarer.api.input.InputEventAPI;
import coop.time.CoopSharedPauseCoordinator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopCampaignInputBlockerTest {
    @Test
    void consumesSupportedCampaignPauseAndFastForwardControlsBeforeCoreInput() {
        RecordingInputEvent pause = new RecordingInputEvent(
                "GENERAL_PAUSE", true, false, false, Set.of("GENERAL_PAUSE", "FAST_FORWARD"));
        RecordingInputEvent fastForward = new RecordingInputEvent(
                "FAST_FORWARD", false, true, false, Set.of("GENERAL_PAUSE", "FAST_FORWARD"));

        new CoopCampaignInputBlocker().processCampaignInputPreCore(List.of(pause.proxy(), fastForward.proxy()));

        assertTrue(pause.consumed);
        assertTrue(fastForward.consumed);
    }

    @Test
    void ignoresUnrelatedAndAlreadyConsumedInput() {
        RecordingInputEvent unrelated = new RecordingInputEvent("INTERACT", true, false, false, Set.of("FAST_FORWARD"));
        RecordingInputEvent alreadyConsumed = new RecordingInputEvent("PAUSE", true, false, true, Set.of());

        new CoopCampaignInputBlocker().processCampaignInputPreCore(
                List.of(unrelated.proxy(), alreadyConsumed.proxy()));

        assertFalse(unrelated.consumed);
        assertTrue(alreadyConsumed.consumed);
    }

    @Test
    void ignoresUnsupportedControlEnumNamesWithoutCrashing() {
        RecordingInputEvent mouseMove = new RecordingInputEvent("MOUSE_MOVE", false, false, false, Set.of());
        RecordingInputEvent pause = new RecordingInputEvent("PAUSE", true, false, false, Set.of("FAST_FORWARD"));

        new CoopCampaignInputBlocker().processCampaignInputPreCore(List.of(mouseMove.proxy(), pause.proxy()));

        assertFalse(mouseMove.consumed);
        assertFalse(pause.consumed);
    }

    @Test
    void interactionBlockConsumesWorldInputButAllowsMouseCameraInput() {
        CoopCampaignInputBlocker blocker = new CoopCampaignInputBlocker();
        blocker.setInteractionBlocked(true);
        RecordingInputEvent mouseClick = RecordingInputEvent.mouseClick();
        RecordingInputEvent keyboard = RecordingInputEvent.keyboard();
        RecordingInputEvent mouseMove = RecordingInputEvent.mouseMove();
        RecordingInputEvent mouseScroll = RecordingInputEvent.mouseScroll();

        blocker.processCampaignInputPreCore(
                List.of(mouseClick.proxy(), keyboard.proxy(), mouseMove.proxy(), mouseScroll.proxy()));

        assertTrue(mouseClick.consumed);
        assertTrue(keyboard.consumed);
        assertFalse(mouseMove.consumed);
        assertFalse(mouseScroll.consumed);
        assertTrue(blocker.isInteractionBlocked());
    }

    @Test
    void worldInputPassesThroughWhenInteractionBlockIsCleared() {
        CoopCampaignInputBlocker blocker = new CoopCampaignInputBlocker();
        blocker.setInteractionBlocked(true);
        blocker.setInteractionBlocked(false);
        RecordingInputEvent mouseClick = RecordingInputEvent.mouseClick();
        RecordingInputEvent keyboard = RecordingInputEvent.keyboard();

        blocker.processCampaignInputPreCore(List.of(mouseClick.proxy(), keyboard.proxy()));

        assertFalse(mouseClick.consumed);
        assertFalse(keyboard.consumed);
        assertFalse(blocker.isInteractionBlocked());
    }

    @Test
    void suspendedBlockerConsumesNothingSoDialogKeysReachTheDialog() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();
        CoopCampaignInputBlocker blocker = new CoopCampaignInputBlocker(coordinator);
        blocker.setInteractionBlocked(true);
        blocker.setSuspended(true);

        // While a blocking screen owns the keyboard: pause key, world clicks, and keyboard all pass
        // through untouched so the guest can advance its station dialog to the options and ESC out.
        RecordingInputEvent pauseDown = new RecordingInputEvent(
                "GENERAL_PAUSE", true, true, false, Set.of("GENERAL_PAUSE", "FAST_FORWARD"));
        RecordingInputEvent mouseClick = RecordingInputEvent.mouseClick();
        RecordingInputEvent keyboard = RecordingInputEvent.keyboard();

        blocker.processCampaignInputPreCore(List.of(pauseDown.proxy(), mouseClick.proxy(), keyboard.proxy()));

        assertFalse(pauseDown.consumed);
        assertFalse(mouseClick.consumed);
        assertFalse(keyboard.consumed);
        // Suspension also means no spurious pause-key press is recorded.
        assertFalse(coordinator.consumeGuestKeyPress());
        assertTrue(blocker.isSuspended());
    }

    @Test
    void resumingBlockerRestoresConsumption() {
        CoopCampaignInputBlocker blocker = new CoopCampaignInputBlocker();
        blocker.setSuspended(true);
        blocker.setSuspended(false);
        RecordingInputEvent pause = new RecordingInputEvent(
                "GENERAL_PAUSE", true, false, false, Set.of("GENERAL_PAUSE", "FAST_FORWARD"));

        blocker.processCampaignInputPreCore(List.of(pause.proxy()));

        assertTrue(pause.consumed);
        assertFalse(blocker.isSuspended());
    }

    @Test
    void guestPauseKeyDownEdgeRecordsAPressAndStillConsumes() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();
        CoopCampaignInputBlocker blocker = new CoopCampaignInputBlocker(coordinator);
        // Down edge of the pause control: activated + down.
        RecordingInputEvent pauseDown = new RecordingInputEvent(
                "GENERAL_PAUSE", true, true, false, Set.of("GENERAL_PAUSE", "FAST_FORWARD"));

        blocker.processCampaignInputPreCore(List.of(pauseDown.proxy()));

        // The press is recorded for the pump to forward, and the event is consumed so vanilla never
        // pauses the guest's clock directly.
        assertTrue(coordinator.consumeGuestKeyPress());
        assertTrue(pauseDown.consumed);
    }

    @Test
    void guestPauseKeyDoesNotRecordOnNonPressEdges() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();
        CoopCampaignInputBlocker blocker = new CoopCampaignInputBlocker(coordinator);
        // Held/activated but not a down edge (down=false): must not record a press, but still consumed.
        RecordingInputEvent pauseHeld = new RecordingInputEvent(
                "GENERAL_PAUSE", true, false, false, Set.of("GENERAL_PAUSE", "FAST_FORWARD"));

        blocker.processCampaignInputPreCore(List.of(pauseHeld.proxy()));

        assertFalse(coordinator.consumeGuestKeyPress());
        assertTrue(pauseHeld.consumed);
    }

    @Test
    void guestFastForwardDownEdgeRecordsAPressAndStillConsumes() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();
        CoopCampaignInputBlocker blocker = new CoopCampaignInputBlocker(coordinator);
        RecordingInputEvent ffDown = new RecordingInputEvent(
                "FAST_FORWARD", true, true, false, Set.of("GENERAL_PAUSE", "FAST_FORWARD"));

        blocker.processCampaignInputPreCore(List.of(ffDown.proxy()));

        assertTrue(coordinator.consumeGuestFastForwardKeyPress());
        assertTrue(ffDown.consumed);
    }

    @Test
    void interactionBlockAllowsRejoinPillClickAndF6HotkeyThrough() {
        CoopCampaignInputBlocker blocker = new CoopCampaignInputBlocker();
        blocker.setInteractionBlocked(true);
        coop.ui.CoopLinkHud.setActiveRejoinPillForTest(
                new coop.ui.CoopLinkHud.PillRect(400f, 1020f, 320f, 34f));
        try {
            RecordingInputEvent insidePill = RecordingInputEvent.mouseClickAt(500, 1035);
            RecordingInputEvent outsidePill = RecordingInputEvent.mouseClickAt(100, 100);
            RecordingInputEvent f6Key = RecordingInputEvent.keyEvent(coop.ui.CoopLinkHud.REJOIN_DIALOG_KEY);
            RecordingInputEvent otherKey = RecordingInputEvent.keyEvent(org.lwjgl.input.Keyboard.KEY_A);

            blocker.processCampaignInputPreCore(List.of(
                    insidePill.proxy(), outsidePill.proxy(), f6Key.proxy(), otherKey.proxy()));

            assertFalse(insidePill.consumed, "Left-click inside rejoin HUD pill must pass through");
            assertTrue(outsidePill.consumed, "World click outside pill must stay blocked");
            assertFalse(f6Key.consumed, "F6 rejoin hotkey must pass through while rejoin pill is active");
            assertTrue(otherKey.consumed, "Other keys must stay blocked during partner interaction");
        } finally {
            coop.ui.CoopLinkHud.setActiveRejoinPillForTest(null);
        }
    }

    private static final class RecordingInputEvent {
        private final String controlName;
        private final boolean activated;
        private final boolean down;
        private final Set<String> supportedControls;
        private final boolean mouseMove;
        private final boolean mouseScroll;
        private final boolean mouseEvent;
        private final boolean keyboardEvent;
        private final boolean mouseDown;
        private final int eventValue;
        private final int x;
        private final int y;
        private boolean consumed;

        private RecordingInputEvent(String controlName, boolean activated, boolean down, boolean consumed,
                                    Set<String> supportedControls) {
            this(controlName, activated, down, consumed, supportedControls, false, false, false, false, false, 0, 0, 0);
        }

        private RecordingInputEvent(String controlName, boolean activated, boolean down, boolean consumed,
                                    Set<String> supportedControls, boolean mouseMove, boolean mouseScroll,
                                    boolean mouseEvent, boolean keyboardEvent) {
            this(controlName, activated, down, consumed, supportedControls, mouseMove, mouseScroll,
                    mouseEvent, keyboardEvent, false, 0, 0, 0);
        }

        private RecordingInputEvent(String controlName, boolean activated, boolean down, boolean consumed,
                                    Set<String> supportedControls, boolean mouseMove, boolean mouseScroll,
                                    boolean mouseEvent, boolean keyboardEvent,
                                    boolean mouseDown, int eventValue, int x, int y) {
            this.controlName = controlName;
            this.activated = activated;
            this.down = down;
            this.consumed = consumed;
            this.supportedControls = supportedControls;
            this.mouseMove = mouseMove;
            this.mouseScroll = mouseScroll;
            this.mouseEvent = mouseEvent;
            this.keyboardEvent = keyboardEvent;
            this.mouseDown = mouseDown;
            this.eventValue = eventValue;
            this.x = x;
            this.y = y;
        }

        private static RecordingInputEvent mouseClick() {
            return new RecordingInputEvent("MOUSE_CLICK", false, false, false, Set.of(),
                    false, false, true, false);
        }

        private static RecordingInputEvent mouseClickAt(int x, int y) {
            return new RecordingInputEvent("MOUSE_CLICK", false, false, false, Set.of(),
                    false, false, true, false, true, 0, x, y);
        }

        private static RecordingInputEvent keyEvent(int keyCode) {
            return new RecordingInputEvent("KEY", false, false, false, Set.of(),
                    false, false, false, true, false, keyCode, 0, 0);
        }

        private static RecordingInputEvent keyboard() {
            return new RecordingInputEvent("KEY", false, false, false, Set.of(),
                    false, false, false, true);
        }

        private static RecordingInputEvent mouseMove() {
            return new RecordingInputEvent("MOUSE_MOVE", false, false, false, Set.of(),
                    true, false, true, false);
        }

        private static RecordingInputEvent mouseScroll() {
            return new RecordingInputEvent("MOUSE_SCROLL", false, false, false, Set.of(),
                    false, true, true, false);
        }

        private InputEventAPI proxy() {
            return (InputEventAPI) Proxy.newProxyInstance(
                    InputEventAPI.class.getClassLoader(),
                    new Class<?>[]{InputEventAPI.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "isConsumed" -> {
                                return consumed;
                            }
                            case "consume" -> {
                                consumed = true;
                                return null;
                            }
                            case "isControlActivated" -> {
                                requireSupportedControl((String) args[0]);
                                return activated && controlName.equals(args[0]);
                            }
                            case "isControlDownEvent" -> {
                                requireSupportedControl((String) args[0]);
                                return down && controlName.equals(args[0]);
                            }
                            case "isControlUpEvent" -> {
                                requireSupportedControl((String) args[0]);
                                return false;
                            }
                            case "isMouseMoveEvent" -> {
                                return mouseMove;
                            }
                            case "isMouseScrollEvent" -> {
                                return mouseScroll;
                            }
                            case "isMouseEvent" -> {
                                return mouseEvent;
                            }
                            case "isKeyboardEvent" -> {
                                return keyboardEvent;
                            }
                            case "isMouseDownEvent" -> {
                                return mouseDown;
                            }
                            case "isMouseUpEvent" -> {
                                return false;
                            }
                            case "getEventValue" -> {
                                return eventValue;
                            }
                            case "getX" -> {
                                return x;
                            }
                            case "getY" -> {
                                return y;
                            }
                            default -> throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }

        private void requireSupportedControl(String control) {
            if (!supportedControls.contains(control)) {
                throw new IllegalArgumentException("No enum constant Controls." + control);
            }
        }
    }
}
