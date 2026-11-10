package io.github.dev_abdulhay.telegramauth.payments;

/**
 * Payment lifecycle callbacks. Delivery is at-least-once: a listener that throws leaves
 * the order unfulfilled and the reconciler calls it again, so implementations MUST be
 * idempotent (for example keyed by {@link StarOrder#chargeId()}).
 */
public interface StarPaymentEvents {

    /** A purchase, top-up or first subscription payment was recorded. */
    default void onPaid(StarOrder order) {
    }

    default void onRefunded(StarOrder order, RefundSource source) {
    }

    /** A recurring charge was recorded as its own {@link StarOrderKind#RENEWAL} order. */
    default void onSubscriptionRenewed(StarSubscription sub, StarOrder renewal) {
    }

    default void onSubscriptionExpired(StarSubscription sub) {
    }
}
