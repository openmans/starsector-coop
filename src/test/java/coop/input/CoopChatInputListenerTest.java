package coop.input;

import coop.mark.CoopMarkKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The pass-through contract the guest input blocker reads: both hotkey listeners publish the key
 * they watch so {@link CoopCampaignInputBlocker} can leave it alone, and neither may leak a stale
 * code once it is uninstalled.
 */
class CoopChatInputListenerTest {

    @AfterEach
    void clear() {
        CoopChatInputListener.setPassThroughKeyCode(CoopMarkKey.KEY_NONE);
    }

    @Test
    void publishesAndClearsTheKeyTheBlockerMustNotEat() {
        assertEquals(CoopMarkKey.KEY_NONE, CoopChatInputListener.passThroughKeyCode());

        CoopChatInputListener.setPassThroughKeyCode(0x42);
        assertEquals(0x42, CoopChatInputListener.passThroughKeyCode());

        CoopChatInputListener.setPassThroughKeyCode(CoopMarkKey.KEY_NONE);
        assertEquals(CoopMarkKey.KEY_NONE, CoopChatInputListener.passThroughKeyCode());
    }
}
