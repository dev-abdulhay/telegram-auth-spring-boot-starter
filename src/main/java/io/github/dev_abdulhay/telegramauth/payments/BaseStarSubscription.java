package io.github.dev_abdulhay.telegramauth.payments;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;

/**
 * A stored Stars subscription. {@code @MappedSuperclass} — host apps subclass with
 * {@code @Entity @Table(name = "...")}. Index {@code user_id} and {@code (status, expires_at)}
 * on the concrete table.
 */
@MappedSuperclass
public abstract class BaseStarSubscription implements Persistable<String> {

    /** Fresh instances are INSERTed (never merged), so a concurrent duplicate fails on the primary key. */
    @Transient
    private boolean newEntity = true;

    @Id
    @Column(name = "id", length = 32, nullable = false)
    private String id;

    @Column(name = "bot_id")
    private Long botId;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "product_code", length = 64)
    private String productCode;

    @Column(name = "first_charge_id", length = 128, unique = true)
    private String firstChargeId;

    @Column(name = "last_charge_id", length = 128)
    private String lastChargeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private StarSubscriptionStatus status;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getBotId() { return botId; }
    public void setBotId(Long botId) { this.botId = botId; }
    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public String getFirstChargeId() { return firstChargeId; }
    public void setFirstChargeId(String firstChargeId) { this.firstChargeId = firstChargeId; }
    public String getLastChargeId() { return lastChargeId; }
    public void setLastChargeId(String lastChargeId) { this.lastChargeId = lastChargeId; }
    public StarSubscriptionStatus getStatus() { return status; }
    public void setStatus(StarSubscriptionStatus status) { this.status = status; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    @Override
    public boolean isNew() { return newEntity; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.newEntity = false; }
}
