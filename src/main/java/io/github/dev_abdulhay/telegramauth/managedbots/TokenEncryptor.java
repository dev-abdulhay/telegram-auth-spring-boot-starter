package io.github.dev_abdulhay.telegramauth.managedbots;

/**
 * Protects managed-bot tokens at rest. Implement this to delegate to a KMS or
 * vault; declaring your own bean replaces the built-in AES-GCM default.
 */
public interface TokenEncryptor {

    String encrypt(String plaintext);

    String decrypt(String ciphertext);
}
