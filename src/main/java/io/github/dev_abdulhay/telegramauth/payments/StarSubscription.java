package io.github.dev_abdulhay.telegramauth.payments;

import java.time.OffsetDateTime;

/** A recurring 30-day Stars subscription. */
public record StarSubscription(String id, Long botId, long userId, String productCode, String firstChargeId,
                               String lastChargeId, StarSubscriptionStatus status, OffsetDateTime expiresAt,
                               OffsetDateTime createdAt) {

    /** Records a renewal charge and extends the expiry; the subscription is {@code ACTIVE} again. */
    public StarSubscription renewed(String chargeId, OffsetDateTime newExpiry) {
        return new StarSubscription(id, botId, userId, productCode, firstChargeId, chargeId,
                StarSubscriptionStatus.ACTIVE, newExpiry, createdAt);
    }

    public StarSubscription withStatus(StarSubscriptionStatus s) {
        return new StarSubscription(id, botId, userId, productCode, firstChargeId, lastChargeId, s,
                expiresAt, createdAt);
    }
}
