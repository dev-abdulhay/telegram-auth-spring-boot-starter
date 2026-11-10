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

    /**
     * Records a renewal: sets {@code lastChargeId}, {@code expiresAt} and ACTIVE, only if
     * {@code newExpiry} is later than the stored expiry (an older charge recovered late is a no-op).
     *
     * @return true if the subscription was extended
     */
    boolean extend(String id, String chargeId, OffsetDateTime newExpiry);

    /**
     * Sets EXPIRED only if the subscription is still ACTIVE/CANCELED and expires before
     * {@code cutoff}, so a renewal recorded meanwhile wins.
     *
     * @return true if this call expired it
     */
    boolean expireIfBefore(String id, OffsetDateTime cutoff);

    /**
     * Changes the status of a live (ACTIVE/CANCELED) subscription without touching its expiry
     * or charge ids. An EXPIRED subscription is left as it is.
     *
     * @return true if the status was written
     */
    boolean updateLiveStatus(String id, StarSubscriptionStatus status);
}
