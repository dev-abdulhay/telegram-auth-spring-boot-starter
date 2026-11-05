package io.github.dev_abdulhay.telegramauth.managedbots;

import java.time.OffsetDateTime;

/**
 * One request to create one managed bot. The row is what turns an anonymous
 * {@code managed_bot} update into "this is the bot org 42 asked for": the id
 * travels in the {@code /start} payload, and the first user to open that link
 * becomes the intent's proven owner.
 *
 * @param suggestedUsername a suggestion only — Telegram lets the user edit it in
 *                          the confirmation dialog, which is the whole reason
 *                          this type exists; may be {@code null}
 * @param hostRef           opaque host correlation key, never interpreted here
 * @param botUserId         set when the intent completes; unique among non-null values
 */
public record ManagedBotIntent(String id, String suggestedUsername, String suggestedName,
                               String hostRef, Long ownerUserId, ManagedBotIntentStatus status,
                               Long botUserId, OffsetDateTime createdAt, OffsetDateTime claimedAt,
                               OffsetDateTime completedAt, OffsetDateTime expiresAt) {

    /** Prefix of the {@code /start} payload that carries an intent id. Not configurable. */
    public static final String START_PREFIX = "mb_";

    public ManagedBotIntent claimedBy(long ownerUserId, OffsetDateTime at) {
        return new ManagedBotIntent(id, suggestedUsername, suggestedName, hostRef, ownerUserId,
                ManagedBotIntentStatus.CLAIMED, botUserId, createdAt, at, completedAt, expiresAt);
    }

    public ManagedBotIntent completedWith(long botUserId, OffsetDateTime at) {
        return new ManagedBotIntent(id, suggestedUsername, suggestedName, hostRef, ownerUserId,
                ManagedBotIntentStatus.COMPLETED, botUserId, createdAt, claimedAt, at, expiresAt);
    }

    public ManagedBotIntent cancelled() {
        return withStatus(ManagedBotIntentStatus.CANCELLED);
    }

    public ManagedBotIntent expired() {
        return withStatus(ManagedBotIntentStatus.EXPIRED);
    }

    private ManagedBotIntent withStatus(ManagedBotIntentStatus next) {
        return new ManagedBotIntent(id, suggestedUsername, suggestedName, hostRef, ownerUserId,
                next, botUserId, createdAt, claimedAt, completedAt, expiresAt);
    }
}
