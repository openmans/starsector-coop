package coop.net;

import coop.chat.CoopChatService;
import coop.compat.CoopNexerelinAdapter;
import coop.trade.CoopTradeSession;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for Item 5 extended capabilities:
 * direct player-to-player atomic trade, in-game text chat, AES-256-GCM transport encryption,
 * CGNAT relay protocol, and optional Nexerelin diplomacy/invasion sync adapter.
 */
class CoopExtendedFeaturesTest {

    @Test
    void tradeSessionPreventsBaitAndSwitchAndCommitsAtomically() {
        CoopTradeSession trade = new CoopTradeSession("t-1", "host", "guest-1");
        assertTrue(trade.updateOffer("host", 50_000L,
                List.of(new CoopTradeSession.CargoItem("supplies", 100)), List.of()));
        assertTrue(trade.updateOffer("guest-1", 0L,
                List.of(new CoopTradeSession.CargoItem("heavy_machinery", 40)), List.of("ship-7")));

        assertTrue(trade.lock("host"));
        assertEquals(CoopTradeSession.State.OPEN, trade.state());

        // Modifying offer unlocks partner automatically
        assertTrue(trade.updateOffer("guest-1", 1_000L,
                List.of(new CoopTradeSession.CargoItem("heavy_machinery", 40)), List.of("ship-7")));
        assertFalse(trade.offerOf("host").locked());

        assertTrue(trade.lock("host"));
        assertTrue(trade.lock("guest-1"));
        assertEquals(CoopTradeSession.State.LOCKED, trade.state());

        List<String> transfers = new ArrayList<>();
        CoopTradeSession.Settlement settlement = (from, to, offer) -> {
            transfers.add(from + "->" + to + ":" + offer.credits() + ":" + offer.cargo().size()
                    + ":" + offer.shipMemberIds().size());
            return true;
        };

        assertTrue(trade.confirm("host", settlement));
        assertEquals(CoopTradeSession.State.LOCKED, trade.state());
        assertTrue(trade.confirm("guest-1", settlement));
        assertEquals(CoopTradeSession.State.COMMITTED, trade.state());
        assertEquals(List.of("host->guest-1:50000:1:0", "guest-1->host:1000:1:1"), transfers);

        String wire = CoopTradeSession.encodeOffer("t-1", "guest-1", "OFFER", trade.offerOf("guest-1"));
        CoopTradeSession.WireFrame decoded = CoopTradeSession.decodeOffer(wire);
        assertNotNull(decoded);
        assertEquals("t-1", decoded.tradeId());
        assertEquals(1_000L, decoded.offer().credits());
        assertEquals(List.of("ship-7"), decoded.offer().shipMemberIds());
    }

    @Test
    void tradeSessionRollsBackIfPartnerTransferFails() {
        CoopTradeSession trade = new CoopTradeSession("t-2", "host", "guest-1");
        trade.updateOffer("host", 25_000L, List.of(), List.of());
        trade.updateOffer("guest-1", 10_000L, List.of(), List.of());
        trade.lock("host");
        trade.lock("guest-1");

        List<String> log = new ArrayList<>();
        CoopTradeSession.Settlement failingPartner = (from, to, offer) -> {
            log.add(from + "->" + to + ":" + offer.credits());
            if ("guest-1".equals(from) && offer.credits() == 10_000L) {
                return false;
            }
            return true;
        };

        assertTrue(trade.confirm("host", failingPartner));
        assertFalse(trade.confirm("guest-1", failingPartner));
        assertEquals(CoopTradeSession.State.CANCELLED, trade.state());
        assertEquals("partner-transfer-failed", trade.cancelReason());
        // Verify rollback transfer refunded host's 25,000 credits
        assertEquals(List.of("host->guest-1:25000", "guest-1->host:10000", "guest-1->host:25000"), log);
    }

    @Test
    void tradeSessionSupportsOneWayCreditGiftUnlockAndRevisionTracking() {
        CoopTradeSession trade = new CoopTradeSession("t-3", "host", "guest-1");
        long r0 = trade.revision();
        assertFalse(trade.hasAnythingOffered());
        assertEquals("guest-1", trade.otherPlayerId("host"));
        assertEquals("host", trade.otherPlayerId("guest-1"));

        // One-way money transfer (host sends 75,000 credits, guest sends nothing)
        assertTrue(trade.updateOffer("host", 75_000L, List.of(), List.of()));
        assertTrue(trade.revision() > r0);
        assertTrue(trade.hasAnythingOffered());

        long r1 = trade.revision();
        assertTrue(trade.lock("host"));
        assertTrue(trade.lock("guest-1"));
        assertEquals(CoopTradeSession.State.LOCKED, trade.state());
        assertTrue(trade.revision() > r1);

        // Host confirms, then guest unlocks to add a commodity -> clears host confirmation & returns to OPEN
        List<String> transfers = new ArrayList<>();
        CoopTradeSession.Settlement sink = (from, to, offer) -> {
            transfers.add(from + "->" + to + ":" + offer.credits() + ":" + offer.cargo().size());
            return true;
        };
        assertTrue(trade.confirm("host", sink));
        assertTrue(trade.offerOf("host").confirmed());

        assertTrue(trade.unlock("guest-1"));
        assertEquals(CoopTradeSession.State.OPEN, trade.state());
        assertFalse(trade.offerOf("guest-1").locked());
        assertFalse(trade.offerOf("host").confirmed());
        assertTrue(transfers.isEmpty());

        // Re-lock and confirm both sides -> atomic commit of one-way credit transfer
        assertTrue(trade.lock("guest-1"));
        assertEquals(CoopTradeSession.State.LOCKED, trade.state());
        assertTrue(trade.confirm("host", sink));
        assertTrue(trade.confirm("guest-1", sink));
        assertEquals(CoopTradeSession.State.COMMITTED, trade.state());
        assertEquals(List.of("host->guest-1:75000:0", "guest-1->host:0:0"), transfers);

        // OPEN and UNLOCK wire round-trip
        String openWire = CoopTradeSession.encodeOffer("t-3", "host", "OPEN", CoopTradeSession.Offer.EMPTY);
        CoopTradeSession.WireFrame openDecoded = CoopTradeSession.decodeOffer(openWire);
        assertNotNull(openDecoded);
        assertEquals("OPEN", openDecoded.action());
        assertEquals(0L, openDecoded.offer().credits());
    }

    @Test
    void chatServiceSanitizesBoundsHistoryAndRateLimits() {
        CoopChatService chat = new CoopChatService();
        CoopChatService.ChatMessage m1 = chat.accept("p1", "Alice", "Hello\nworld\t!", 1_000L);
        assertNotNull(m1);
        assertEquals("Hello world !", m1.text());

        // Wire round-trip
        CoopChatService.ChatMessage decoded = CoopChatService.decode(CoopChatService.encode(m1));
        assertEquals(m1, decoded);

        // Rate limit: 5 messages per 3000 ms window
        for (int i = 0; i < 4; i++) {
            assertNotNull(chat.accept("p1", "Alice", "msg " + i, 1_100L + i * 10L));
        }
        assertNull(chat.accept("p1", "Alice", "spam", 1_500L), "6th msg in window rejected");
        assertNotNull(chat.accept("p1", "Alice", "after window", 4_500L), "allowed after window");

        // Blank message rejected
        assertNull(chat.accept("p1", "Alice", "   \n\t  ", 5_000L));
    }

    @Test
    void aesGcmCipherEncryptsDecryptsAndRejectsTamperedFrames() {
        assertNull(CoopCipher.fromSecret("off", "secret"));
        assertNull(CoopCipher.fromSecret("aes-gcm", ""));

        CoopCipher cipher = CoopCipher.fromSecret("aes-gcm", "sector-secret-password");
        assertNotNull(cipher);

        CoopMessages.Message msg = CoopMessages.chatMsg("s-1", 42L, 123_456L, "p1|Alice|1000|Ready");
        String wire1 = CoopMessages.encode(msg, cipher);
        String wire2 = CoopMessages.encode(msg, cipher);
        assertTrue(CoopCipher.isEncryptedFrame(wire1));
        assertNotEquals(wire1, wire2, "fresh random 96-bit IV per frame");
        assertFalse(wire1.contains("Ready"), "plaintext must not appear on the wire");

        CoopMessages.Message decoded = CoopMessages.decode(wire1, cipher);
        assertEquals(msg.type(), decoded.type());
        assertEquals(msg.seq(), decoded.seq());
        assertEquals(CoopMessages.parseBody(msg), CoopMessages.parseBody(decoded));

        // Tampering or wrong key must fail authentication
        CoopCipher wrong = CoopCipher.fromSecret("aes-gcm", "wrong-password");
        assertThrows(IllegalArgumentException.class, () -> CoopMessages.decode(wire1, wrong));
        String tampered = wire1.substring(0, wire1.length() - 2)
                + (wire1.endsWith("AA") ? "BB" : "AA");
        assertThrows(IllegalArgumentException.class, () -> CoopMessages.decode(tampered, cipher));
    }

    @Test
    void relayProtocolParsesEndpointsAndCommands() {
        CoopRelayProtocol.Endpoint ep = CoopRelayProtocol.parseAddress("relay.example.com:7799");
        assertEquals(new CoopRelayProtocol.Endpoint("relay.example.com", 7799), ep);
        assertEquals(new CoopRelayProtocol.Endpoint("1.2.3.4", CoopRelayProtocol.DEFAULT_RELAY_PORT),
                CoopRelayProtocol.parseAddress("1.2.3.4"));
        assertNull(CoopRelayProtocol.parseAddress("bad:99999"));

        CoopRelayProtocol.Command reg = CoopRelayProtocol.parseCommand(
                CoopRelayProtocol.formatRegister("ROOM-42", "tok123"));
        assertEquals(new CoopRelayProtocol.Command("RELAY_REGISTER", "ROOM-42", "tok123"), reg);

        CoopRelayProtocol.Command join = CoopRelayProtocol.parseCommand(
                CoopRelayProtocol.formatJoin("ROOM-42", "tok123"));
        assertEquals(new CoopRelayProtocol.Command("RELAY_JOIN", "ROOM-42", "tok123"), join);
    }

    @Test
    void nexerelinAdapterDormantWhenAbsentAndAppliesEventsWhenPresent() {
        CoopNexerelinAdapter.NexEvent war = new CoopNexerelinAdapter.NexEvent(
                CoopNexerelinAdapter.EventKind.WAR_DECLARED, "hegemony", "persean", "", -1.0f, "War!");
        CoopNexerelinAdapter.NexEvent capture = new CoopNexerelinAdapter.NexEvent(
                CoopNexerelinAdapter.EventKind.MARKET_CAPTURED, "tritachyon", "", "jangala", 0f, "Captured");

        assertEquals(war, CoopNexerelinAdapter.decode(CoopNexerelinAdapter.encode(war)));
        assertEquals(capture, CoopNexerelinAdapter.decode(CoopNexerelinAdapter.encode(capture)));

        List<String> applied = new ArrayList<>();
        CoopNexerelinAdapter.NexWorld disabledWorld = new CoopNexerelinAdapter.NexWorld() {
            @Override public boolean isNexerelinEnabled() { return false; }
            @Override public boolean setFactionRelationship(String a, String b, float r) { return true; }
            @Override public boolean setMarketOwner(String m, String f) { return true; }
        };
        assertFalse(CoopNexerelinAdapter.apply(war, disabledWorld), "dormant without Nexerelin");

        CoopNexerelinAdapter.NexWorld enabledWorld = new CoopNexerelinAdapter.NexWorld() {
            @Override public boolean isNexerelinEnabled() { return true; }
            @Override public boolean setFactionRelationship(String a, String b, float r) {
                applied.add("rel:" + a + ":" + b + ":" + r);
                return true;
            }
            @Override public boolean setMarketOwner(String m, String f) {
                applied.add("own:" + m + ":" + f);
                return true;
            }
        };
        assertTrue(CoopNexerelinAdapter.apply(war, enabledWorld));
        assertTrue(CoopNexerelinAdapter.apply(capture, enabledWorld));
        assertEquals(List.of("rel:hegemony:persean:-1.0", "own:jangala:tritachyon"), applied);
    }
}
