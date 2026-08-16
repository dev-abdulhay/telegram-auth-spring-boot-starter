package io.github.dev_abdulhay.telegramauth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PreUpdate;

import java.time.OffsetDateTime;

/**
 * Base login session. {@code @MappedSuperclass} — host apps subclass with
 * {@code @Entity @Table(name = "...")} per user type.
 *
 * <p>Index {@code ip_address} on the concrete table. Every session creation runs
 * a per-IP pending count, so without one that lookup is a full table scan on the
 * hottest endpoint in the library:
 *
 * <pre>{@code
 * @Entity
 * @Table(name = "demo_auth_session",
 *        indexes = @Index(name = "ix_demo_session_ip_status", columnList = "ip_address,status"))
 * public class DemoSession extends BaseAuthSession { }
 * }</pre>
 *
 * <p>{@code token_hash} is already indexed by its unique constraint.
 */
@MappedSuperclass
public abstract class BaseAuthSession {

    public enum Status { PENDING, APPROVED, REJECTED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "telegram_user_id")
    private Long telegramUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    /**
     * JSON snapshot of the host's approve payload, written at approval time so
     * a poll that misses the live event can still deliver it.
     */
    @Column(name = "approve_payload", length = 4000)
    private String approvePayload;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public Long getTelegramUserId() { return telegramUserId; }
    public void setTelegramUserId(Long telegramUserId) { this.telegramUserId = telegramUserId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(OffsetDateTime approvedAt) { this.approvedAt = approvedAt; }
    public String getApprovePayload() { return approvePayload; }
    public void setApprovePayload(String approvePayload) { this.approvePayload = approvePayload; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
