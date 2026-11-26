package coop.ui;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import coop.net.CoopMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopDialogSpectatorTest {

    @AfterEach
    void restoreLanguage() {
        CoopUiLang.setRussian(true);
    }

    @Test
    void headerTitleFormatsInRussianAndEnglish() {
        CoopUiLang.setRussian(true);
        assertEquals("Диалог игрока «Артур»", CoopDialogSpectatorState.headerTitle("Артур"));

        CoopUiLang.setRussian(false);
        assertEquals("Dialog of \"Artur\"", CoopDialogSpectatorState.headerTitle("Artur"));
    }

    @Test
    void syncPayloadRoundTripsJsonWithCursorHoverAndVote() {
        CoopDialogSpectatorState.SyncPayload original = new CoopDialogSpectatorState.SyncPayload(
                "host-dlg-7",
                "host-id",
                "Commander Artur",
                true,
                "Jangala",
                List.of("The station comm officer greets your fleet.", "A bounty broker waves from the bar."),
                List.of(
                        new CoopDialogSpectatorState.OptionView("Open the comm directory", true),
                        new CoopDialogSpectatorState.OptionView("Visit the bar", true),
                        new CoopDialogSpectatorState.OptionView("Demand tribute", false)),
                1,
                0.375f,
                0.625f,
                1,
                "Alex");

        String encoded = CoopDialogSpectatorState.encodeSync(original);
        CoopDialogSpectatorState.SyncPayload decoded = CoopDialogSpectatorState.decodeSync(encoded);

        assertNotNull(decoded);
        assertEquals("host-dlg-7", decoded.dialogId());
        assertEquals("host-id", decoded.speakerId());
        assertEquals("Commander Artur", decoded.speakerName());
        assertTrue(decoded.open());
        assertEquals("Jangala", decoded.targetName());
        assertEquals(2, decoded.paragraphs().size());
        assertEquals(3, decoded.options().size());
        assertEquals("Visit the bar", decoded.options().get(1).text());
        assertFalse(decoded.options().get(2).enabled());
        assertEquals(1, decoded.hoveredOptionIndex());
        assertEquals(0.375f, decoded.cursorNormX(), 0.002f);
        assertEquals(0.625f, decoded.cursorNormY(), 0.002f);
        assertEquals(1, decoded.votedOptionIndex());
        assertEquals("Alex", decoded.voterName());
    }

    @Test
    void votePayloadRoundTripsThroughCoopMessages() {
        CoopDialogSpectatorState.VotePayload vote = new CoopDialogSpectatorState.VotePayload(
                "guest-dlg-3", "Artur", 2, "Accept the contract");
        String body = CoopDialogSpectatorState.encodeVote(vote);
        CoopMessages.Message msg = CoopMessages.dialogVote("sess-1", 42L, 1_000L, body);

        assertEquals(CoopMessages.Type.DIALOG_VOTE, msg.type());
        assertFalse(CoopMessages.isReliableOneShot(CoopMessages.Type.DIALOG_SYNC));
        assertFalse(CoopMessages.isReliableOneShot(CoopMessages.Type.DIALOG_VOTE));

        CoopDialogSpectatorState.VotePayload decoded =
                CoopDialogSpectatorState.decodeVote(CoopMessages.parseBody(msg));
        assertNotNull(decoded);
        assertEquals("guest-dlg-3", decoded.dialogId());
        assertEquals("Artur", decoded.voterName());
        assertEquals(2, decoded.optionIndex());
        assertEquals("Accept the contract", decoded.optionText());
    }

    @Test
    void autoPullRespectsManualDismissPerDialogIdAndResumesForNextDialog() {
        CoopDialogSpectatorState state = new CoopDialogSpectatorState();
        CoopDialogSpectatorState.SyncPayload dlg1 = new CoopDialogSpectatorState.SyncPayload(
                "dlg-1", "p1", "Host", true, "Corvus", List.of("Hello"),
                List.of(new CoopDialogSpectatorState.OptionView("Hi", true)),
                -1, 0.5f, 0.5f, -1, "");
        state.applyRemoteSync(dlg1);
        assertTrue(state.shouldAutoOpenSpectator());

        // User closes spectator manually -> never auto-reopens for dlg-1
        state.markDismissedByUser("dlg-1");
        assertFalse(state.shouldAutoOpenSpectator());
        state.applyRemoteSync(dlg1);
        assertFalse(state.shouldAutoOpenSpectator());

        // Speaker closes dlg-1 and opens dlg-2 -> auto-pulls again
        state.applyRemoteSync(new CoopDialogSpectatorState.SyncPayload(
                "dlg-1", "p1", "Host", false, "", List.of(), List.of(), -1, 0.5f, 0.5f, -1, ""));
        assertNull(state.activeRemoteDialog());

        CoopDialogSpectatorState.SyncPayload dlg2 = new CoopDialogSpectatorState.SyncPayload(
                "dlg-2", "p1", "Host", true, "Kazeron", List.of("New encounter"),
                List.of(new CoopDialogSpectatorState.OptionView("Hail", true)),
                0, 0.2f, 0.8f, -1, "");
        state.applyRemoteSync(dlg2);
        assertTrue(state.shouldAutoOpenSpectator());
    }

    @Test
    void speakerVoteBannerFormatsAndExpires() {
        CoopDialogSpectatorState state = new CoopDialogSpectatorState();
        CoopUiLang.setRussian(true);
        state.recordIncomingVote(new CoopDialogSpectatorState.VotePayload(
                "dlg-5", "Игорь", 0, "Открыть торговлю"), 10_000L);

        assertEquals(0, state.incomingVotedIndexFor("dlg-5"));
        assertEquals("Игорь", state.incomingVoterNameFor("dlg-5"));
        assertEquals("[Голос] Игорь советует: «Открыть торговлю»", state.speakerVoteBanner(15_000L));
        assertEquals("", state.speakerVoteBanner(25_000L));
    }

    @Test
    void pluginFilterBlocksInternalCoopUiDialogs() {
        CoopDialogSpectatorState state = new CoopDialogSpectatorState();
        CoopDialogSpectatorPlugin internal = new CoopDialogSpectatorPlugin(state, null, null);
        assertFalse(CoopDialogSpectatorState.isSpectatablePlugin(internal));
        assertFalse(CoopDialogSpectatorState.isSpectatablePlugin(null));

        // Anonymous test plugin lives in coop.ui, so check class-name rule explicitly:
        InteractionDialogPlugin dummyInCoopUi = new InteractionDialogPlugin() {
            @Override public void init(InteractionDialogAPI dialog) {}
            @Override public void optionSelected(String optionText, Object optionData) {}
            @Override public void optionMousedOver(String optionText, Object optionData) {}
            @Override public void advance(float amount) {}
            @Override public void backFromEngagement(EngagementResultAPI battleResult) {}
            @Override public Object getContext() { return null; }
            @Override public Map<String, MemoryAPI> getMemoryMap() { return null; }
        };
        assertFalse(CoopDialogSpectatorState.isSpectatablePlugin(dummyInCoopUi));
    }
}
