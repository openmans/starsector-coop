package coop.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The encryption seam: what is sealed, what is refused, and the size arithmetic that keeps a sealed
 * datagram inside the wire budget.
 */
class CoopTransportCodecTest {

    @Test
    void plaintextPassesThroughUnchanged() {
        CoopTransportCodec codec = CoopTransportCodec.plaintext();

        assertFalse(codec.encrypted());
        assertEquals("{\"t\":\"PING\"}", codec.seal("{\"t\":\"PING\"}"));
        assertEquals("{\"t\":\"PING\"}", codec.open("{\"t\":\"PING\"}"));
    }

    @Test
    void plaintextTransportRefusesASealedFrame() {
        CoopTransportCodec plain = CoopTransportCodec.plaintext();
        String sealed = CoopTransportCodec.of(CoopCipher.MODE_AES_GCM, "hunter2").seal("hello");

        // Telling the peer "your encryption is off" is the honest failure; silently decoding the
        // frame would accept a session whose traffic we cannot read.
        assertThrows(IllegalArgumentException.class, () -> plain.open(sealed));
    }

    @Test
    void encryptedRoundTripsAndRefusesDowngrade() {
        CoopTransportCodec codec = CoopTransportCodec.of(CoopCipher.MODE_AES_GCM, "hunter2");

        assertTrue(codec.encrypted());
        String sealed = codec.seal("{\"t\":\"CHAT_MSG\"}");
        assertTrue(sealed.startsWith(CoopCipher.WIRE_PREFIX));
        assertFalse(sealed.contains("\n"), "a framed line must stay one line");
        assertEquals("{\"t\":\"CHAT_MSG\"}", codec.open(sealed));
        assertThrows(IllegalArgumentException.class, () -> codec.open("{\"t\":\"CHAT_MSG\"}"));
    }

    @Test
    void aDifferentSecretCannotOpenTheFrame() {
        String sealed = CoopTransportCodec.of(CoopCipher.MODE_AES_GCM, "hunter2").seal("state");
        CoopTransportCodec other = CoopTransportCodec.of(CoopCipher.MODE_AES_GCM, "hunter3");

        assertThrows(IllegalArgumentException.class, () -> other.open(sealed));
    }

    @Test
    void aesGcmWithoutAPasswordStaysPlaintext() {
        CoopTransportCodec codec = CoopTransportCodec.of(CoopCipher.MODE_AES_GCM, "");

        assertFalse(codec.encrypted(), "nothing to derive a key from: must not pretend to encrypt");
        assertEquals("x", codec.open(codec.seal("x")));
    }

    @Test
    void sealedLengthIsAStrictUpperBoundOnTheWireSize() {
        CoopTransportCodec codec = CoopTransportCodec.of(CoopCipher.MODE_AES_GCM, "hunter2");

        for (int plaintext : new int[]{0, 1, 100, 511, 800, 1200}) {
            String body = "x".repeat(plaintext);
            int actual = codec.seal(body).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            assertTrue(actual <= CoopTransportCodec.sealedLength(plaintext),
                    "sealed " + plaintext + " B took " + actual + " B, bound says "
                            + CoopTransportCodec.sealedLength(plaintext));
        }
    }

    @Test
    void theServiceBudgetKeepsSealedDatagramsUnderTheWireCap() {
        // The arithmetic the transport relies on: the budget it hands producers, sealed, still fits
        // the 1200-byte wire cap that keeps a datagram inside one MTU.
        int budget = 1200;
        while (budget > 0 && CoopTransportCodec.sealedLength(budget) > 1200) {
            budget -= 16;
        }

        assertTrue(budget >= 700, "an encrypted budget this small would starve the state stream: " + budget);
        assertTrue(CoopTransportCodec.sealedLength(budget) <= 1200);
        assertTrue(CoopTransportCodec.sealedLength(budget + 16) > 1200);
    }
}
