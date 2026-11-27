package coop.input;

import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.input.InputEventAPI;
import coop.mark.CoopMarkKey;
import coop.util.CoopLog;

import java.util.List;
import java.util.Objects;

/**
 * The in-game chat hotkey ({@code coop.chatKey}, default {@code F8}), installed for the length of a
 * coop session.
 *
 * <p>The same shape as {@link CoopMarkInputListener}, for the same reasons: plain key detection
 * rather than a named control (the chat key is a coop setting, not a bindable engine control), the
 * down edge only, and the event is consumed so vanilla never sees a key the player has bound to
 * something else.
 *
 * <p><b>The guest's blocker is the other half of this.</b> {@link CoopCampaignInputBlocker}
 * consumes keyboard input while the partner holds an interaction, and listeners at equal priority
 * have no defined order, so the blocker asks {@link #passThroughKeyCode()} and leaves this key
 * alone - exactly as it does for the marker key.
 *
 * <p>Whether the chat dialog may actually open is decided downstream by
 * {@code CoopDialogArbiter.CHAT}: a reconnect/desync/lobby dialog that outranks chat keeps the
 * screen, and F8 while it is up simply does nothing.
 */
public final class CoopChatInputListener implements CampaignInputListener {

    private static final int INPUT_PRIORITY = Integer.MAX_VALUE;

    /**
     * The key code the installed listener is watching, or {@link CoopMarkKey#KEY_NONE} when no chat
     * listener is installed. Static because the guest input blocker has to know it and the two
     * listeners are installed independently by {@code CoopTimeLock}.
     */
    private static volatile int passThroughKeyCode = CoopMarkKey.KEY_NONE;

    private final CoopMarkKey key;
    private final Runnable onChat;

    public CoopChatInputListener(CoopMarkKey key, Runnable onChat) {
        this.key = Objects.requireNonNull(key, "key");
        this.onChat = Objects.requireNonNull(onChat, "onChat");
    }

    /** The key the guest input blocker must not eat; {@link CoopMarkKey#KEY_NONE} when none. */
    public static int passThroughKeyCode() {
        return passThroughKeyCode;
    }

    /** Called by {@code CoopTimeLock} on install/remove; the blocker reads the result. */
    public static void setPassThroughKeyCode(int code) {
        passThroughKeyCode = code;
    }

    /** The key this listener is watching. */
    public CoopMarkKey key() {
        return key;
    }

    @Override
    public int getListenerInputPriority() {
        return INPUT_PRIORITY;
    }

    @Override
    public void processCampaignInputPreCore(List<InputEventAPI> events) {
        if (events == null || key.code() == CoopMarkKey.KEY_NONE) {
            return;
        }
        for (InputEventAPI event : events) {
            if (event == null || event.isConsumed() || !isChatDownEdge(event)) {
                continue;
            }
            event.consume();
            try {
                onChat.run();
            } catch (RuntimeException | LinkageError ex) {
                // A chat dialog that throws must not take the campaign frame down with it.
                CoopLog.warn(CoopChatInputListener.class, "Coop chat hotkey failed", ex);
            }
        }
    }

    @Override
    public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {
    }

    @Override
    public void processCampaignInputPostCore(List<InputEventAPI> events) {
    }

    private boolean isChatDownEdge(InputEventAPI event) {
        try {
            return event.isKeyDownEvent() && !event.isRepeat() && event.getEventValue() == key.code();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }
}
