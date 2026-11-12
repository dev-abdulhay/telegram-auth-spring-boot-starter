package io.github.dev_abdulhay.telegramauth.payments;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

import java.time.OffsetDateTime;

/**
 * A stored Stars order. {@code @MappedSuperclass} — host apps subclass with
 * {@code @Entity @Table(name = "...")}.
 *
 * <p>The id is the 32-hex order id that travels in the invoice payload. Index
 * {@code (status, fulfilled_at)} and {@code (status, expires_at)} on the concrete table
 * for the reconciler and the purge.
 */
@MappedSuperclass
public abstract class BaseStarOrder {

    @Id
    @Column(name = "id", length = 32, nullable = false)
    private String id;

    @Column(name = "bot_id")
    private Long botId;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "product_code", length = 64)
    private String productCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 16, nullable = false)
    private StarOrderKind kind;

    @Column(name = "stars", nullable = false)
    private int stars;

    @Column(name = "credits", nullable = false)
    private long credits;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private StarOrderStatus status;

    @Column(name = "charge_id", length = 512, unique = true)
    private String chargeId;

    @Column(name = "subscription_id", length = 32)
    private String subscriptionId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "fulfilled_at")
    private OffsetDateTime fulfilledAt;

    @Column(name = "refunded_at")
    private OffsetDateTime refundedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getBotId() { return botId; }
    public void setBotId(Long botId) { this.botId = botId; }
    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public StarOrderKind getKind() { return kind; }
    public void setKind(StarOrderKind kind) { this.kind = kind; }
    public int getStars() { return stars; }
    public void setStars(int stars) { this.stars = stars; }
    public long getCredits() { return credits; }
    public void setCredits(long credits) { this.credits = credits; }
    public StarOrderStatus getStatus() { return status; }
    public void setStatus(StarOrderStatus status) { this.status = status; }
    public String getChargeId() { return chargeId; }
    public void setChargeId(String chargeId) { this.chargeId = chargeId; }
    public String getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(OffsetDateTime paidAt) { this.paidAt = paidAt; }
    public OffsetDateTime getFulfilledAt() { return fulfilledAt; }
    public void setFulfilledAt(OffsetDateTime fulfilledAt) { this.fulfilledAt = fulfilledAt; }
    public OffsetDateTime getRefundedAt() { return refundedAt; }
    public void setRefundedAt(OffsetDateTime refundedAt) { this.refundedAt = refundedAt; }
}
