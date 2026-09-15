package io.github.dev_abdulhay.telegramauth.managedbots;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Managed-bots settings. A separate namespace from {@code telegram.auth} on
 * purpose: the two features are independent and either can run without the other.
 */
@ConfigurationProperties(prefix = "telegram.managed-bots")
public class TelegramManagedBotsProperties {

    /** Opt-in switch for the whole feature. */
    private boolean enabled = false;

    /**
     * Base64-encoded 32-byte AES key for token encryption at rest. Required when
     * the feature is on, unless the host supplies its own {@link TokenEncryptor}.
     */
    private String encryptionKey;

    /** Attempts for {@code getManagedBotToken} before giving up on an update. */
    private int tokenFetchRetries = 3;

    /** First retry delay, doubling on each further attempt. */
    private Duration tokenFetchBackoff = Duration.ofSeconds(1);

    /**
     * How long an {@code OPEN} intent stays claimable. A {@code CLAIMED} intent does
     * not expire: its owner is proven and the resolution screen needs it to survive.
     */
    private Duration intentTtl = Duration.ofMinutes(30);

    /** How long closed intents (expired, cancelled) are kept before the purge removes them. */
    private Duration intentRetention = Duration.ofDays(7);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEncryptionKey() { return encryptionKey; }
    public void setEncryptionKey(String encryptionKey) { this.encryptionKey = encryptionKey; }
    public int getTokenFetchRetries() { return tokenFetchRetries; }
    public void setTokenFetchRetries(int tokenFetchRetries) { this.tokenFetchRetries = tokenFetchRetries; }
    public Duration getTokenFetchBackoff() { return tokenFetchBackoff; }
    public void setTokenFetchBackoff(Duration tokenFetchBackoff) { this.tokenFetchBackoff = tokenFetchBackoff; }
    public Duration getIntentTtl() { return intentTtl; }
    public void setIntentTtl(Duration intentTtl) { this.intentTtl = intentTtl; }
    public Duration getIntentRetention() { return intentRetention; }
    public void setIntentRetention(Duration intentRetention) { this.intentRetention = intentRetention; }
}
