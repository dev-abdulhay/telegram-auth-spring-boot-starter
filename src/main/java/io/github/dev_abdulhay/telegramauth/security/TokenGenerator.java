package io.github.dev_abdulhay.telegramauth.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates opaque session tokens (32 bytes from {@link SecureRandom},
 * Base64URL-encoded) and computes their SHA-256 hash for DB storage.
 * The raw token is never persisted.
 */
public final class TokenGenerator {

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder URL_ENC = Base64.getUrlEncoder().withoutPadding();

    public String newToken() {
        byte[] buf = new byte[TOKEN_BYTES];
        RNG.nextBytes(buf);
        return URL_ENC.encodeToString(buf);
    }

    public String hash(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
