package coop.ui;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.util.Misc;
import coop.chat.CoopChatService;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * In-game co-op text chat dialog (Item 5), opened via {@code coop.chatKey} (default {@code F8})
 * or the Coop Session intel page.
 */
public final class CoopChatDialog implements InteractionDialogPlugin {

    private static final String OPT_SEND_READY = "chat_ready";
    private static final String OPT_SEND_REGROUP = "chat_regroup";
    private static final String OPT_SEND_WAIT = "chat_wait";
    private static final String OPT_SEND_ENGAGE = "chat_engage";
    private static final String OPT_CLOSE = "chat_close";

    private final CoopChatService chatService;
    private final Consumer<String> onSend;
    private InteractionDialogAPI dialog;

    public CoopChatDialog(CoopChatService chatService, Consumer<String> onSend) {
        this.chatService = chatService == null ? new CoopChatService() : chatService;
        this.onSend = onSend == null ? s -> {} : onSend;
    }

    @Override
    public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        render();
    }

    @Override
    public void optionSelected(String optionText, Object optionData) {
        String id = String.valueOf(optionData);
        switch (id) {
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

    private void sendAndRefresh(String text) {
        onSend.accept(text);
        render();
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

    @Override
    public void optionMousedOver(String optionText, Object optionData) {
    }

    @Override
    public void advance(float amount) {
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
}
