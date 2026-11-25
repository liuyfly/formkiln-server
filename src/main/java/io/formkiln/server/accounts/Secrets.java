package io.formkiln.server.accounts;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Random tokens, token hashing and AES-256-GCM encryption for secrets at rest.
 *
 * <p>The key comes from {@code FORMKILN_ENCRYPTION_KEY}: 32 random bytes, Base64-encoded
 * (for example {@code openssl rand -base64 32}). Ciphertext layout: 12-byte IV, then ciphertext and tag.
 */
@Component
public class Secrets {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;

    public Secrets(@Value("${formkiln.encryption-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "FORMKILN_ENCRYPTION_KEY is not set. Generate one with: openssl rand -base64 32");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("FORMKILN_ENCRYPTION_KEY must be Base64", e);
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("FORMKILN_ENCRYPTION_KEY must decode to 32 bytes");
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    /** A URL-safe random token with 256 bits of entropy. */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /** SHA-256 of a high-entropy token; only this hash is stored. */
    public static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = randomBytes(IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public byte[] decrypt(byte[] ciphertext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, ciphertext, 0, IV_BYTES));
            return cipher.doFinal(ciphertext, IV_BYTES, ciphertext.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed (wrong FORMKILN_ENCRYPTION_KEY?)", e);
        }
    }
}
