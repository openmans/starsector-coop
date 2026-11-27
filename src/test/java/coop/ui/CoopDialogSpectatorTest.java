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
    void syncPayloadRoundTripsDialogTradeAndCommViews() {
        CoopDialogSpectatorState.SyncPayload original = new CoopDialogSpectatorState.SyncPayload(
                "host-dlg-7",
                "host-id",
                "Commander Artur",
                "corvus",
                "TRADE",
                true,
                "Jangala",
                "125 000¢ · 420 / 600",
                List.of("The station comm officer greets your fleet."),
                List.of(
                        new CoopDialogSpectatorState.OptionView("Топливо (320) · 24¢ / 19¢", true),
                        new CoopDialogSpectatorState.OptionView("Припасы (150) · 98¢ / 82¢", true)),
                List.of(
                        new CoopDialogSpectatorState.TradeItemView("fuel", "Топливо", 320, 24, 19, "Открытый рынок"),
                        new CoopDialogSpectatorState.TradeItemView("supplies", "Припасы", 150, 98, 82, "Открытый рынок")),
                List.of(
                        new CoopDialogSpectatorState.CommEntryView("Артём", "Комендант станции", "Гегемония", "graphics/portraits/portrait_hegemony01.png")),
                1,
                0.375f,
                0.625f,
                1,
                "Alex");

        String encoded = CoopDialogSpectatorState.encodeSync(original);
        CoopDialogSpectatorState.SyncPayload decoded = CoopDialogSpectatorState.decodeSync(encoded);

        assertNotNull(decoded);
        assertEquals("host-dlg-7", decoded.dialogId());
        assertEquals("corvus", decoded.locationId());
        assertEquals("TRADE", decoded.viewMode());
        assertEquals("125 000¢ · 420 / 600", decoded.subHeader());
        assertEquals(2, decoded.tradeItems().size());
        assertEquals("fuel", decoded.tradeItems().get(0).commodityId());
        assertEquals(320, decoded.tradeItems().get(0).qty());
        assertEquals(24, decoded.tradeItems().get(0).buyPrice());
        assertEquals(1, decoded.commEntries().size());
        assertEquals("Артём", decoded.commEntries().get(0).name());
        assertEquals("graphics/portraits/portrait_hegemony01.png", decoded.commEntries().get(0).portraitSprite());
    }

    @Test
    void sameStarSystemGateBlocksDifferentSystemsAndHyperspace() {
        CoopDialogSpectatorState state = new CoopDialogSpectatorState();
        CoopDialogSpectatorState.SyncPayload inCorvus = new CoopDialogSpectatorState.SyncPayload(
                "dlg-1", "p1", "Host", "corvus", "DIALOG", true, "Jangala", "",
                List.of("Hello"),
                List.of(new CoopDialogSpectatorState.OptionView("Hi", true)),
                List.of(), List.of(),
                -1, 0.5f, 0.5f, -1, "");
        state.applyRemoteSync(inCorvus);

        // Same star system -> opens!
        assertTrue(state.shouldAutoOpenSpectator("corvus"));
        // Different star system -> NEVER opens!
        assertFalse(state.shouldAutoOpenSpectator("askonia"));
        // Local player in hyperspace (empty system id) -> NEVER opens!
        assertFalse(state.shouldAutoOpenSpectator(""));
        assertFalse(state.shouldAutoOpenSpectator(null));

        // Speaker in hyperspace (empty locationId) -> NEVER opens even if local is in a system!
        CoopDialogSpectatorState.SyncPayload inHyper = new CoopDialogSpectatorState.SyncPayload(
                "dlg-2", "p1", "Host", "", "DIALOG", true, "Hyperspace", "",
                List.of("Anomaly"), List.of(), List.of(), List.of(),
                -1, 0.5f, 0.5f, -1, "");
        state.applyRemoteSync(inHyper);
        assertFalse(state.shouldAutoOpenSpectator("corvus"));
        assertFalse(state.shouldAutoOpenSpectator(""));
    }

    @Test
    void extractNumberHighlightsFindsCreditsDaysUnitsAndPercents() {
        String ruText = "Доставьте 240 ед. припасов за 15 дней и получите 12 500¢ (+25% к цене).";
        List<String> ru = CoopDialogSpectatorState.extractNumberHighlights(ruText);
        assertTrue(ru.contains("240 ед."), "Expected units highlight, got " + ru);
        assertTrue(ru.contains("15 дней"), "Expected days highlight, got " + ru);
        assertTrue(ru.contains("12 500¢"), "Expected cost highlight, got " + ru);
        assertTrue(ru.contains("+25%"), "Expected percent highlight, got " + ru);

        String enText = "Deliver 150 units within 30 days for 50,000 credits.";
        List<String> en = CoopDialogSpectatorState.extractNumberHighlights(enText);
        assertTrue(en.contains("150 units"), "Expected EN units highlight, got " + en);
        assertTrue(en.contains("30 days"), "Expected EN days highlight, got " + en);
        assertTrue(en.contains("50,000 credits"), "Expected EN credits highlight, got " + en);
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
                "dlg-1", "p1", "Host", "corvus", "DIALOG", true, "Corvus", "",
                List.of("Hello"),
                List.of(new CoopDialogSpectatorState.OptionView("Hi", true)),
                List.of(), List.of(),
                -1, 0.5f, 0.5f, -1, "");
        state.applyRemoteSync(dlg1);
        assertTrue(state.shouldAutoOpenSpectator("corvus"));

        state.markDismissedByUser("dlg-1");
        assertFalse(state.shouldAutoOpenSpectator("corvus"));
        state.applyRemoteSync(dlg1);
        assertFalse(state.shouldAutoOpenSpectator("corvus"));

        state.applyRemoteSync(new CoopDialogSpectatorState.SyncPayload(
                "dlg-1", "p1", "Host", "corvus", "DIALOG", false, "", "",
                List.of(), List.of(), List.of(), List.of(), -1, 0.5f, 0.5f, -1, ""));
        assertNull(state.activeRemoteDialog());

        CoopDialogSpectatorState.SyncPayload dlg2 = new CoopDialogSpectatorState.SyncPayload(
                "dlg-2", "p1", "Host", "corvus", "DIALOG", true, "Kazeron", "",
                List.of("New encounter"),
                List.of(new CoopDialogSpectatorState.OptionView("Hail", true)),
                List.of(), List.of(),
                0, 0.2f, 0.8f, -1, "");
        state.applyRemoteSync(dlg2);
        assertTrue(state.shouldAutoOpenSpectator("corvus"));
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
