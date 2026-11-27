package coop.net;

/**
 * The transport's encryption seam: one object that every wire read and write goes through, so the
 * "which frames are sealed" question has exactly one answer for TCP lines and UDP datagrams alike.
 *
 * <p>Two failure modes are refused here rather than left to look like corruption, because both are
 * what a downgrade attack looks like from the receiving side:
 * <ul>
 *   <li>a plaintext frame arriving on an encrypted transport (someone stripped the seal), and</li>
 *   <li>a sealed frame arriving on a plaintext transport (someone turned encryption on for one side
 *       only, or a stale peer still has the old setting).</li>
 * </ul>
 * Either throws {@link IllegalArgumentException}; the transport already treats that as a bad frame
 * and counts a strike, which is the same path garbage input takes.
 *
 * <p>The plaintext instance passes everything through untouched, so {@code coop.encryption=off}
 * stays byte-identical to the pre-encryption protocol.
 */
public final class CoopTransportCodec {

    private static final CoopTransportCodec PLAINTEXT = new CoopTransportCodec(null);

    private final CoopCipher cipher;

    private CoopTransportCodec(CoopCipher cipher) {
        this.cipher = cipher;
    }

    /** The no-op codec: {@code coop.encryption=off}. */
    public static CoopTransportCodec plaintext() {
        return PLAINTEXT;
    }

    /**
     * Builds the codec the settings ask for. Aes-gcm without a password has nothing to derive a key
     * from, so it resolves to plaintext rather than to a cipher over an empty secret - the caller
     * logs that, and the honest behaviour is an unencrypted session that says so, not one that
     * looks encrypted and is not.
     */
    public static CoopTransportCodec of(String mode, String password) {
        CoopCipher cipher = CoopCipher.fromSecret(mode, password);
        return cipher == null ? PLAINTEXT : new CoopTransportCodec(cipher);
    }

    public boolean encrypted() {
        return cipher != null;
    }

    /** Seals one frame; plaintext when encryption is off. */
    public String seal(String plaintext) {
        return cipher == null ? plaintext : cipher.encrypt(plaintext);
    }

    /**
     * Opens one frame. Throws {@link IllegalArgumentException} when the seal is missing, unexpected
     * or fails its authentication tag.
     */
    public String open(String wire) {
        if (cipher == null) {
            if (wire != null && wire.startsWith(CoopCipher.WIRE_PREFIX)) {
                throw new IllegalArgumentException(
                        "sealed frame on a plaintext transport (one side has coop.encryption=aes-gcm)");
            }
            return wire;
        }
        if (wire == null || !wire.startsWith(CoopCipher.WIRE_PREFIX)) {
            throw new IllegalArgumentException(
                    "plaintext frame on an encrypted transport (downgrade refused)");
        }
        return cipher.decrypt(wire);
    }

    /** Sealed bytes for a UDP datagram payload. */
    public byte[] sealBytes(String plaintext) {
        return seal(plaintext).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Worst-case wire size of {@code plaintextBytes} once sealed: the {@code ENC1:} prefix, the
     * base64 IV, a colon and base64 of the ciphertext plus its 16-byte GCM tag. Used to keep a
     * datagram's <em>sealed</em> size inside the MTU-derived budget rather than its plaintext size.
     */
    public static int sealedLength(int plaintextBytes) {
        int ciphertext = plaintextBytes + 16;
        int ivBase64 = ((12 + 2) / 3) * 4;
        int ctBase64 = ((ciphertext + 2) / 3) * 4;
        return CoopCipher.WIRE_PREFIX.length() + ivBase64 + 1 + ctBase64;
    }
}
