package coop.net;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * Opt-in AES-256-GCM transport encryption for co-op wire frames (Item 5, {@code coop.encryption}).
 *
 * <p>Uses only standard JDK {@code javax.crypto} + {@code java.security} primitives so it runs
 * cleanly inside Starsector's script sandbox with zero {@code java.io} or reflection usage.
 *
 * <p>Wire format when active: {@code ENC1:<base64-12-byte-iv>:<base64-ciphertext-plus-16-byte-tag>}.
 * When {@code coop.encryption=off} (default), frames pass through unchanged so unencrypted peers
 * remain 100% compatible.
 */
public final class CoopCipher {

    public static final String MODE_OFF = "off";
    public static final String MODE_AES_GCM = "aes-gcm";
    public static final String WIRE_PREFIX = "ENC1:";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String KEY_DOMAIN = "starsector-coop-aes256-gcm-v1:";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec keySpec;

    private CoopCipher(SecretKeySpec keySpec) {
        this.keySpec = keySpec;
    }

    /** True when {@code mode} requests AES-256-GCM encryption. */
    public static boolean isEnabledMode(String mode) {
        return MODE_AES_GCM.equals(mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Builds a cipher derived from {@code sharedSecret} (typically {@code coop.password} + optional
     * salt). Returns {@code null} if encryption is disabled or the secret is blank.
     */
    public static CoopCipher fromSecret(String mode, String sharedSecret) {
        if (!isEnabledMode(mode) || sharedSecret == null || sharedSecret.isBlank()) {
            return null;
        }
        return new CoopCipher(deriveKey(sharedSecret));
    }

    /** Encrypts {@code plaintext} UTF-8 bytes with a fresh 96-bit random IV. */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return "";
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder enc = Base64.getEncoder();
            return WIRE_PREFIX + enc.encodeToString(iv) + ":" + enc.encodeToString(ct);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("AES-GCM encrypt failed", ex);
        }
    }

    /**
     * Decrypts a {@link #WIRE_PREFIX} frame and verifies the 128-bit GCM authentication tag.
     * Throws {@link IllegalArgumentException} on tampered or malformed ciphertext.
     */
    public String decrypt(String frame) {
        if (frame == null || !frame.startsWith(WIRE_PREFIX)) {
            throw new IllegalArgumentException("Not an encrypted ENC1 frame");
        }
        String rest = frame.substring(WIRE_PREFIX.length());
        int colon = rest.indexOf(':');
        if (colon <= 0 || colon >= rest.length() - 1) {
            throw new IllegalArgumentException("Malformed ENC1 envelope");
        }
        try {
            Base64.Decoder dec = Base64.getDecoder();
            byte[] iv = dec.decode(rest.substring(0, colon));
            byte[] ct = dec.decode(rest.substring(colon + 1));
            if (iv.length != IV_BYTES) {
                throw new IllegalArgumentException("Invalid GCM IV length: " + iv.length);
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("AES-GCM decrypt/auth failed", ex);
        }
    }

    public static boolean isEncryptedFrame(String frame) {
        return frame != null && frame.startsWith(WIRE_PREFIX);
    }

    private static SecretKeySpec deriveKey(String sharedSecret) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = sha256.digest((KEY_DOMAIN + sharedSecret).getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
