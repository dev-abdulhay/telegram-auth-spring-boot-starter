package io.github.dev_abdulhay.telegramauth.payments.balance;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;

/**
 * One applied balance change. {@code @MappedSuperclass} — host apps subclass with
 * {@code @Entity @Table(name = "...")} and index {@code (account_key, created_at)}.
 * The primary key on {@code ref_key} is what makes every change apply at most once.
 */
@MappedSuperclass
public abstract class BaseLedgerEntry implements Persistable<String> {

    /** Fresh instances are INSERTed (never merged), so a concurrent duplicate fails on the primary key. */
    @Transient
    private boolean newEntity = true;

    @Id
    @Column(name = "ref_key", length = 160, nullable = false)
    private String refKey;

    @Column(name = "account_key", length = 48, nullable = false)
    private String accountKey;

    @Column(name = "delta", nullable = false)
    private long delta;

    @Column(name = "reason", length = 64)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public String getRefKey() { return refKey; }
    public void setRefKey(String refKey) { this.refKey = refKey; }
    public String getAccountKey() { return accountKey; }
    public void setAccountKey(String accountKey) { this.accountKey = accountKey; }
    public long getDelta() { return delta; }
    public void setDelta(long delta) { this.delta = delta; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    @Override
    public String getId() { return refKey; }

    @Override
    public boolean isNew() { return newEntity; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.newEntity = false; }
}
