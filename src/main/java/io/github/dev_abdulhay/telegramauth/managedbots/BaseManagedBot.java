package io.github.dev_abdulhay.telegramauth.managedbots;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

import java.time.OffsetDateTime;

/**
 * A stored managed bot. {@code @MappedSuperclass} — host apps subclass with
 * {@code @Entity @Table(name = "...")}, exactly like {@code BaseAuthSession}.
 *
 * <p>Index {@code owner_user_id} on the concrete table; {@code bot_user_id} is
 * already indexed by its unique constraint.
 */
@MappedSuperclass
public abstract class BaseManagedBot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bot_user_id", nullable = false, unique = true)
    private Long botUserId;

    @Column(name = "username", length = 50)
    private String username;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    /** Ciphertext, never a raw token. Sized for Base64(IV || ciphertext || tag). */
    @Column(name = "encrypted_token", nullable = false, length = 512)
    private String encryptedToken;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getBotUserId() { return botUserId; }
    public void setBotUserId(Long botUserId) { this.botUserId = botUserId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getEncryptedToken() { return encryptedToken; }
    public void setEncryptedToken(String encryptedToken) { this.encryptedToken = encryptedToken; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    /** Masks the ciphertext so no log line can leak it. */
    @Override
    public String toString() {
        return getClass().getSimpleName() + "[botUserId=" + botUserId
                + ", username=" + username + ", ownerUserId=" + ownerUserId
                + ", encryptedToken=***]";
    }
}
