package io.github.dev_abdulhay.telegramauth.managedbots;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for managed-bot intents. {@code save} is an upsert keyed on
 * {@link ManagedBotIntent#id()}.
 *
 * <p>Hosts create the table themselves, exactly as they do for managed bots.
 */
public interface ManagedBotIntentStore {

    void save(ManagedBotIntent intent);

    Optional<ManagedBotIntent> findById(String id);

    /** The intent a bot is linked to, if any. Drives "is this bot still unassigned?". */
    Optional<ManagedBotIntent> findByBotUserId(long botUserId);

    /** Only {@code CLAIMED} rows: an intent is a match candidate solely while it waits for its bot. */
    List<ManagedBotIntent> findClaimedByOwner(long ownerUserId);

    /**
     * Deletes intents that can no longer be claimed or completed: {@code CANCELLED},
     * {@code EXPIRED}, <b>and {@code OPEN} rows whose {@code expiresAt} already passed</b>
     * — expiry is evaluated lazily on read, so an intent nobody ever read is still
     * {@code OPEN} in the database and a status-only predicate would never reap it.
     * {@code CLAIMED} and {@code COMPLETED} are never deleted.
     *
     * @return how many rows were removed
     */
    int deleteClosedBefore(OffsetDateTime cutoff);
}
