package io.github.dev_abdulhay.telegramauth.managedbots;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for managed bots. {@code save} is an upsert keyed on
 * {@link ManagedBot#botUserId()} — a re-delivered {@code managed_bot} update must
 * not create a second row.
 *
 * <p>Implementations receive the token already encrypted; they never encrypt or
 * decrypt themselves.
 */
public interface ManagedBotTokenStore {

    void save(ManagedBot bot);

    Optional<ManagedBot> findByBotUserId(long botUserId);

    List<ManagedBot> findByOwnerUserId(long ownerUserId);

    /** Every managed bot; the white-label runtime needs this to restore bots after a restart. */
    List<ManagedBot> findAll();

    void deleteByBotUserId(long botUserId);
}
