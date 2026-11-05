package io.github.dev_abdulhay.telegramauth.managedbots;

import java.util.List;

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

    /** A user opened an intent link and proved who they are. The intent now waits for its bot. */
    default void onIntentClaimed(ManagedBotIntent intent) { }

    /** A bot and an intent were linked — automatically, or by the host calling {@code assignToIntent}. */
    default void onIntentMatched(ManagedBot bot, ManagedBotIntent intent) { }

    /**
     * A created bot fits more than one of its creator's waiting intents, so nothing
     * was linked. Show the creator their intents and let a human decide.
     */
    default void onIntentUnmatched(ManagedBot bot, List<ManagedBotIntent> candidates) { }

    /**
     * The mirror image, on claim: this intent fits more than one of the creator's
     * unassigned bots. Nothing was linked.
     */
    default void onIntentAmbiguous(ManagedBotIntent intent, List<ManagedBot> candidates) { }
}
