package io.github.dev_abdulhay.telegramauth.payments;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Persistence for Stars subscriptions. {@code save} is an upsert by id. */
public interface StarSubscriptionStore {

    void save(StarSubscription s);

    Optional<StarSubscription> findById(String id);

    List<StarSubscription> findByUser(long userId);

    /** {@code ACTIVE} or {@code CANCELED} subscriptions with {@code expiresAt < cutoff}. */
    List<StarSubscription> findLiveExpiringBefore(OffsetDateTime cutoff);
}
