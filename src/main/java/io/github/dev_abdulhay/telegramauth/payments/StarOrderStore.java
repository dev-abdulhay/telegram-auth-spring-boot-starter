package io.github.dev_abdulhay.telegramauth.payments;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for Stars orders. The {@code mark*} methods are atomic compare-and-set
 * transitions: they return {@code true} only for the one caller that performed the change.
 */
public interface StarOrderStore {

    /** Upsert by id. */
    void save(StarOrder o);

    Optional<StarOrder> findById(String id);

    Optional<StarOrder> findByChargeId(String chargeId);

    /** {@code CREATED|EXPIRED -> PAID}; true only if this call made the transition. */
    boolean markPaid(String id, String chargeId, OffsetDateTime at);

    /** Inserts a renewal order that is already paid; false if its charge id is already stored. */
    boolean insertPaidIfChargeAbsent(StarOrder paid);

    /** True only if the order is {@code PAID} and not yet fulfilled. */
    boolean markFulfilled(String id, OffsetDateTime at);

    /** {@code PAID -> REFUNDED}; true only if this call made the transition. */
    boolean markRefunded(String id, OffsetDateTime at);

    /** Paid orders whose fulfilment was never recorded, oldest first. */
    List<StarOrder> findPaidUnfulfilled(int limit);

    /** {@code CREATED} orders with {@code expiresAt < now} become {@code EXPIRED}. */
    int expireCreatedBefore(OffsetDateTime now);

    /** Deletes {@code EXPIRED} orders with {@code expiresAt < cutoff}. */
    int deleteExpiredBefore(OffsetDateTime cutoff);
}
