package io.github.dev_abdulhay.telegramauth.payments;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for Stars subscriptions. {@code save} is an upsert by id; the payment flow creates
 * rows only with {@link #insertIfAbsent} and changes them only with the conditional methods below.
 */
public interface StarSubscriptionStore {

    void save(StarSubscription s);

    /**
     * Inserts the subscription unless a row with its id exists. An existing row is never
     * changed, even when it was written concurrently.
     *
     * @return true if this call inserted the row
     */
    boolean insertIfAbsent(StarSubscription s);

    Optional<StarSubscription> findById(String id);

    List<StarSubscription> findByUser(long userId);

    /** {@code ACTIVE} or {@code CANCELED} subscriptions with {@code expiresAt < cutoff}. */
    List<StarSubscription> findLiveExpiringBefore(OffsetDateTime cutoff);

    /**
     * Records a renewal: sets {@code lastChargeId} and {@code expiresAt}, only if {@code newExpiry}
     * is later than the stored expiry (an older charge recovered late is a no-op). An EXPIRED
     * subscription becomes ACTIVE again; a CANCELED one stays CANCELED.
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
