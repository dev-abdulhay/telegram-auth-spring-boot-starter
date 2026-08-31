package io.github.dev_abdulhay.telegramauth.managedbots;

/**
 * Lifecycle hooks for managed bots. Every method has a no-op default, so a host
 * implements only what it needs. Handlers run on the bot's update worker thread —
 * keep them short and hand long work to an executor.
 */
public interface ManagedBotEvents {

    /** A bot was created and its token is already stored. */
    default void onCreated(ManagedBot bot) { }

    /** An existing bot's token changed and the stored copy has been replaced. */
    default void onTokenRotated(ManagedBot bot) { }

    /** The token could not be fetched after every retry; nothing was stored. */
    default void onTokenFetchFailed(long botUserId, long ownerUserId, Exception cause) { }

    /** The bot was decommissioned: its token is revoked and the row is gone. */
    default void onDecommissioned(long botUserId) { }
}
