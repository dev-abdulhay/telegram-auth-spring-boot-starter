package io.github.dev_abdulhay.telegramauth.managedbots;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM with a fresh random IV per write, stored as
 * {@code Base64(IV || ciphertext || tag)}. GCM authenticates as well as
 * encrypts, so a tampered value fails to decrypt instead of returning garbage.
 */
public class AesGcmTokenEncryptor implements TokenEncryptor {

    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RNG = new SecureRandom();

    private final SecretKeySpec key;

    public AesGcmTokenEncryptor(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException("encryption key must not be blank");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("encryption key must be Base64-encoded", e);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                    "encryption key must decode to 32 bytes but was " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    @Override
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RNG.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("token encryption failed", e);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        try {
            byte[] all = Base64.getDecoder().decode(ciphertext);
            if (all.length <= IV_BYTES) {
                throw new IllegalArgumentException("ciphertext too short");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // never include the ciphertext or the cause's message in a user-facing string
            throw new IllegalStateException("token decryption failed", e);
        }
    }
}
