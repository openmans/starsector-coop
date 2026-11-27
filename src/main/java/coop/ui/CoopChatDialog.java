package coop.ui;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.VisualPanelAPI;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import coop.chat.CoopChatService;
import coop.util.CoopLog;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * In-game co-op text chat dialog (Item 5), opened via {@code coop.chatKey} (default {@code F8})
 * or the Coop Session intel page.
 *
 * <p>Four canned messages cover the common calls ("ready", "regroup", "wait", "engaging") because
 * they are one click and never need the keyboard in the middle of a fight. The free-text field is
 * the other half: a player who wants to say something specific types it into the dialog's custom
 * panel and presses <em>Send typed message</em>.
 *
 * <p>The field is installed once, in {@link #init}; {@link #render} only rewrites the transcript and
 * the option list, so a refresh after sending does not rebuild the panel under the player's cursor.
 */
public final class CoopChatDialog implements InteractionDialogPlugin {

    private static final String OPT_SEND_TYPED = "chat_send_typed";
    private static final String OPT_SEND_READY = "chat_ready";
    private static final String OPT_SEND_REGROUP = "chat_regroup";
    private static final String OPT_SEND_WAIT = "chat_wait";
    private static final String OPT_SEND_ENGAGE = "chat_engage";
    private static final String OPT_CLOSE = "chat_close";

    /** Panel geometry for the field; the dialog scales it, the numbers only need to be sane. */
    private static final float PANEL_WIDTH = 520f;
    private static final float PANEL_HEIGHT = 46f;

    /** The same ceiling the service applies to an inbound message. */
    private static final int MAX_TYPED_CHARS = CoopChatService.MAX_TEXT_LENGTH;

    private final CoopChatService chatService;
    private final Consumer<String> onSend;
    private InteractionDialogAPI dialog;
    private TextFieldAPI field;

    public CoopChatDialog(CoopChatService chatService, Consumer<String> onSend) {
        this.chatService = chatService == null ? new CoopChatService() : chatService;
        this.onSend = onSend == null ? s -> {} : onSend;
    }

    @Override
    public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        render();
        installField();
        updateTypedOptionState();
    }

    @Override
    public void optionSelected(String optionText, Object optionData) {
        String id = String.valueOf(optionData);
        switch (id) {
            case OPT_SEND_TYPED -> sendTyped();
            case OPT_SEND_READY -> sendAndRefresh("Ready to move out.");
            case OPT_SEND_REGROUP -> sendAndRefresh("Regroup on my position.");
            case OPT_SEND_WAIT -> sendAndRefresh("Hold on — managing fleet/market.");
            case OPT_SEND_ENGAGE -> sendAndRefresh("Engaging hostile fleet!");
            case OPT_CLOSE -> {
                if (dialog != null) {
                    dialog.dismiss();
                }
            }
            default -> {
            }
        }
    }

    /**
     * Sends whatever is in the field and clears it. Blank input is not an error worth a dialog of
     * its own: the option is disabled while the field is empty, and a race that empties it between
     * the click and this call simply sends nothing.
     */
    private void sendTyped() {
        if (field == null) {
            return;
        }
        try {
            String text = field.getText();
            if (text == null || text.trim().isEmpty()) {
                return;
            }
            field.setText("");
            sendAndRefresh(text.trim());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopChatDialog.class, "Coop chat could not send the typed message", ex);
        }
    }

    private void sendAndRefresh(String text) {
        onSend.accept(text);
        render();
        updateTypedOptionState();
    }

    private void render() {
        if (dialog == null) {
            return;
        }
        TextPanelAPI text = dialog.getTextPanel();
        OptionPanelAPI opts = dialog.getOptionPanel();
        text.clear();
        opts.clearOptions();

        text.addPara(CoopUiLang.tr("Co-op Comms Channel", "Чат коопа"), Misc.getHighlightColor());
        List<CoopChatService.ChatMessage> recent = chatService.recent(12);
        if (recent.isEmpty()) {
            text.addPara(CoopUiLang.tr("No messages yet.", "Сообщений пока нет."), Misc.getGrayColor());
        } else {
            for (CoopChatService.ChatMessage m : recent) {
                text.addPara("[" + m.senderName() + "] " + m.text());
            }
        }

        opts.addOption(CoopUiLang.tr("Send typed message", "Отправить набранное"), OPT_SEND_TYPED);
        opts.addOption(CoopUiLang.tr("Send: \"Ready to move out.\"", "Отправить: «Готов выдвигаться.»"),
                OPT_SEND_READY);
        opts.addOption(CoopUiLang.tr("Send: \"Regroup on my position.\"", "Отправить: «Сбор у меня.»"),
                OPT_SEND_REGROUP);
        opts.addOption(CoopUiLang.tr("Send: \"Hold on — managing fleet/market.\"",
                "Отправить: «Подожди — разбираю флот/рынок.»"), OPT_SEND_WAIT);
        opts.addOption(CoopUiLang.tr("Send: \"Engaging hostile fleet!\"",
                "Отправить: «Атакую вражеский флот!»"), OPT_SEND_ENGAGE);
        opts.addOption(CoopUiLang.tr("Close comms", "Закрыть чат"), OPT_CLOSE);
    }

    /** The typed-message option is only meaningful with something to send. */
    private void updateTypedOptionState() {
        if (dialog == null || field == null) {
            return;
        }
        try {
            String text = field.getText();
            boolean hasText = text != null && !text.trim().isEmpty();
            dialog.getOptionPanel().setEnabled(OPT_SEND_TYPED, hasText);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopChatDialog.class, "Coop chat could not refresh its option state", ex);
        }
    }

    /**
     * The text field, in the dialog's visual area. {@code grabFocus} is what makes typing land in the
     * field rather than in the option panel's shortcut handling; a failure only costs the typed
     * half of the dialog, the canned messages still work.
     */
    private void installField() {
        field = null;
        try {
            VisualPanelAPI visual = dialog.getVisualPanel();
            if (visual == null) {
                return;
            }
            CustomPanelAPI panel = visual.showCustomPanel(PANEL_WIDTH, PANEL_HEIGHT, new NoInput());
            if (panel == null) {
                return;
            }
            TooltipMakerAPI element = panel.createUIElement(PANEL_WIDTH, PANEL_HEIGHT - 10f, false);
            if (element == null) {
                return;
            }
            element.addPara(CoopUiLang.tr("Message:", "Сообщение:"), 0f);
            TextFieldAPI made = element.addTextField(PANEL_WIDTH - 20f, 4f);
            if (made == null) {
                return;
            }
            made.setMaxChars(MAX_TYPED_CHARS);
            // Deliberately false: ESC is the dialog's own exit, and an undo-on-escape field would
            // swallow the first press.
            made.setUndoOnEscape(false);
            made.setHandleCtrlV(true);
            made.grabFocus();
            PositionAPI position = panel.addUIElement(element);
            if (position != null) {
                position.inTL(5f, 5f);
            }
            field = made;
        } catch (Throwable ex) {
            field = null;
            CoopLog.warn(CoopChatDialog.class, "Coop chat could not build its text field", ex);
        }
    }

    @Override
    public void optionMousedOver(String optionText, Object optionData) {
    }

    @Override
    public void advance(float amount) {
        // Typing does not raise an engine event, so the enabled state is refreshed from the frame
        // rather than from the click: cheap, and the option lights up as soon as a character lands.
        updateTypedOptionState();
    }

    @Override
    public void backFromEngagement(EngagementResultAPI battleResult) {
    }

    @Override
    public Object getContext() {
        return chatService;
    }

    @Override
    public Map<String, MemoryAPI> getMemoryMap() {
        return Map.of();
    }

    /** The panel draws nothing of its own; the element inside it draws itself. */
    private static final class NoInput implements CustomUIPanelPlugin {
        @Override
        public void positionChanged(PositionAPI position) {
        }

        @Override
        public void renderBelow(float alphaMult) {
        }

        @Override
        public void render(float alphaMult) {
        }

        @Override
        public void advance(float amount) {
        }

        @Override
        public void processInput(List<InputEventAPI> events) {
        }

        @Override
        public void buttonPressed(Object buttonId) {
        }
    }
}
