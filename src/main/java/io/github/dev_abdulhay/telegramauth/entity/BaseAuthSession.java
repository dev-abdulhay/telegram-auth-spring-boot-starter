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
 * <p><b>White-label hosts index {@code ip_address,bot_user_id,status} instead.</b>
 * A session created through a module that carries a bot id is rate-limited within
 * its own tenant, so that lookup filters on {@code (ip_address, bot_user_id,
 * status, expires_at)} and an {@code ip_address,status} index leaves the bot id
 * to be filtered row by row.
 *
 * <p>{@code token_hash} is already indexed by its unique constraint.
 */
@MappedSuperclass
public abstract class BaseAuthSession {

    /**
     * {@code AWAITING_CODE} is <b>not</b> terminal: the user confirmed the login
     * but still owes the browser-visible confirmation code. It holds its per-IP
     * rate-limit slot and is swept to {@code EXPIRED} just like {@code PENDING}.
     */
    public enum Status { PENDING, AWAITING_CODE, APPROVED, REJECTED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "telegram_user_id")
    private Long telegramUserId;

    /**
     * The managed bot this session belongs to, or {@code null} for a session
     * created by a statically configured module. Nullable on purpose: rows
     * written before white-label existed have no bot, so the column is additive
     * and needs no backfill.
     */
    @Column(name = "bot_user_id")
    private Long botUserId;

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
    public Long getBotUserId() { return botUserId; }
    public void setBotUserId(Long botUserId) { this.botUserId = botUserId; }
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
