package io.github.dev_abdulhay.telegramauth.managedbots;

import java.time.OffsetDateTime;

/**
 * One bot created on behalf of a user, as this library stores it.
 *
 * @param botUserId      Telegram user id of the bot itself; the id every managed-bot
 *                       API method takes as {@code user_id}
 * @param ownerUserId    Telegram user id of the person who created it
 * @param encryptedToken the bot token, encrypted by the configured {@link TokenEncryptor}
 */
public record ManagedBot(long botUserId, String username, String firstName, long ownerUserId,
                         String encryptedToken, OffsetDateTime createdAt, OffsetDateTime updatedAt) {

    /** Masks the token: a record's generated toString would print it into any log line. */
    @Override
    public String toString() {
        return "ManagedBot[botUserId=" + botUserId + ", username=" + username
                + ", firstName=" + firstName + ", ownerUserId=" + ownerUserId
                + ", encryptedToken=***, createdAt=" + createdAt + ", updatedAt=" + updatedAt + "]";
    }
}
