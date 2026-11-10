package io.github.dev_abdulhay.telegramauth.payments;

import java.time.OffsetDateTime;

/** One Stars invoice and its payment state. Immutable; stores transition it atomically. */
public record StarOrder(String id, Long botId, long userId, String productCode, StarOrderKind kind,
                        int stars, long credits, StarOrderStatus status, String chargeId,
                        String subscriptionId, OffsetDateTime createdAt, OffsetDateTime expiresAt,
                        OffsetDateTime paidAt, OffsetDateTime fulfilledAt, OffsetDateTime refundedAt) {

    /** The invoice payload Telegram echoes back on pre-checkout and payment. */
    public String payload() {
        return "so:" + id;
    }

    public StarOrder withSubscriptionId(String sid) {
        return new StarOrder(id, botId, userId, productCode, kind, stars, credits, status, chargeId,
                sid, createdAt, expiresAt, paidAt, fulfilledAt, refundedAt);
    }
}
