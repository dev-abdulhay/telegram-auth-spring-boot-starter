package io.github.dev_abdulhay.telegramauth.payments.balance;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

import java.time.OffsetDateTime;

/**
 * A stored balance. {@code @MappedSuperclass} — host apps subclass with
 * {@code @Entity @Table(name = "...")}. The id is {@link BalanceAccountKey#asString()}.
 */
@MappedSuperclass
public abstract class BaseBalanceAccount {

    @Id
    @Column(name = "account_key", length = 48, nullable = false)
    private String accountKey;

    @Column(name = "bot_id")
    private Long botId;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "balance", nullable = false)
    private long balance;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public String getAccountKey() { return accountKey; }
    public void setAccountKey(String accountKey) { this.accountKey = accountKey; }
    public Long getBotId() { return botId; }
    public void setBotId(Long botId) { this.botId = botId; }
    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }
    public long getBalance() { return balance; }
    public void setBalance(long balance) { this.balance = balance; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
