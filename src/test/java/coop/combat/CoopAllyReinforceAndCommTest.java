package coop.combat;

import coop.config.CoopOptionsRegistry;
import coop.ui.CoopDialogArbiter;
import coop.ui.CoopOptionsView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopAllyReinforceAndCommTest {

    @Test
    void allyJoinRangeOptionDefaultsTo3000AndClampsBounds() {
        CoopOptionsRegistry.Option opt = CoopOptionsRegistry.require(CoopOptionsRegistry.ALLY_JOIN_RANGE);
        assertEquals("3000", opt.defaultValue());
        assertEquals(700, opt.min());
        assertEquals(10000, opt.max());
        assertEquals("700", opt.coerce("200").value());
        assertEquals("10000", opt.coerce("25000").value());
        assertEquals("3250", CoopOptionsView.nextValue(CoopOptionsRegistry.ALLY_JOIN_RANGE, "3000", 1));
        assertEquals("2750", CoopOptionsView.nextValue(CoopOptionsRegistry.ALLY_JOIN_RANGE, "3000", -1));
    }

    @Test
    void rangeCheckAllowsUpToConfigured3000SuWithoutTouchingGlobalNpcRange() {
        assertTrue(CoopAllyReinforceCoordinator.isWithinJoinRange(0f, 3000));
        assertTrue(CoopAllyReinforceCoordinator.isWithinJoinRange(1850f, 3000));
        assertTrue(CoopAllyReinforceCoordinator.isWithinJoinRange(3000f, 3000));
        assertFalse(CoopAllyReinforceCoordinator.isWithinJoinRange(3001f, 3000));
        assertTrue(CoopAllyReinforceCoordinator.isWithinJoinRange(4500f, 5000));
        assertFalse(CoopAllyReinforceCoordinator.isWithinJoinRange(-10f, 3000));
        assertFalse(CoopAllyReinforceCoordinator.isWithinJoinRange(Float.NaN, 3000));
    }

    @Test
    void offerAndReplyRoundTripForTacticalWingmanAiAndDeclineChoices() {
        CoopAllyReinforceCoordinator coord = new CoopAllyReinforceCoordinator();
        CoopAllyReinforceCoordinator.JoinOffer offer = coord.createOffer(
                "host-1", "Captain Rex", "Pirate Armada", 2450.4f, true, 50_000L);
        assertEquals(2450, offer.distanceSu());
        assertTrue(offer.wingmanAvailable());
        assertTrue(coord.hasPendingOutgoingOffers());

        String encodedOffer = CoopAllyReinforceCoordinator.encodeOffer(offer);
        CoopAllyReinforceCoordinator.JoinOffer decodedOffer =
                CoopAllyReinforceCoordinator.decodeOffer(encodedOffer);
        assertEquals(offer, decodedOffer);

        for (CoopAllyReinforceCoordinator.JoinChoice choice
                : CoopAllyReinforceCoordinator.JoinChoice.values()) {
            CoopAllyReinforceCoordinator.JoinReply reply =
                    new CoopAllyReinforceCoordinator.JoinReply(offer.offerId(), "guest-1", choice);
            CoopAllyReinforceCoordinator.JoinReply decodedReply =
                    CoopAllyReinforceCoordinator.decodeReply(
                            CoopAllyReinforceCoordinator.encodeReply(reply));
            assertEquals(reply, decodedReply);
            assertEquals(choice != CoopAllyReinforceCoordinator.JoinChoice.DECLINE,
                    choice.joinsBattle());
        }

        // Auto-open tactical map latch works only for JOIN_TACTICAL
        coord.noteLocalChoice(CoopAllyReinforceCoordinator.JoinChoice.JOIN_TACTICAL);
        assertTrue(coord.consumeAutoOpenTacticalMap());
        assertFalse(coord.consumeAutoOpenTacticalMap(), "one-shot consume");

        coord.noteLocalChoice(CoopAllyReinforceCoordinator.JoinChoice.JOIN_LIVE_COMBAT);
        assertFalse(coord.consumeAutoOpenTacticalMap());
        assertTrue(coord.isLiveCombatRequested());

        coord.noteLocalChoice(CoopAllyReinforceCoordinator.JoinChoice.JOIN_AI);
        assertFalse(coord.consumeAutoOpenTacticalMap());
        assertFalse(coord.isLiveCombatRequested());

        assertNotNull(coord.consumeOutgoingOffer(offer.offerId()));
        assertNull(coord.consumeOutgoingOffer(offer.offerId()));
    }

    @Test
    void dialogArbiterPrioritizesAllyJoinAboveTacticalChatAndTrade() {
        assertTrue(CoopDialogArbiter.RECONNECT.outranks(CoopDialogArbiter.ALLY_JOIN));
        assertTrue(CoopDialogArbiter.LOBBY.outranks(CoopDialogArbiter.ALLY_JOIN));
        assertTrue(CoopDialogArbiter.ALLY_JOIN.outranks(CoopDialogArbiter.TACTICAL_MAP));
        assertTrue(CoopDialogArbiter.ALLY_JOIN.outranks(CoopDialogArbiter.CHAT));
        assertTrue(CoopDialogArbiter.ALLY_JOIN.outranks(CoopDialogArbiter.TRADE));
    }
}
