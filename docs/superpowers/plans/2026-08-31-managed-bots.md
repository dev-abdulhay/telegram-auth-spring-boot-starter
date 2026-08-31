# Managed Bots Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a managed-bots capability that creates bots on behalf of users, holds their tokens encrypted, exposes rotation/access-settings/decommission, and publishes lifecycle events.

**Architecture:** A new `managedbots` package depends only on `bot/` (HTTP client + module) and never on the auth code. A `ManagedBotService` orchestrates a Telegram client, a pluggable `ManagedBotTokenStore` (in-memory + JPA), an AES-GCM `TokenEncryptor` and a `ManagedBotEvents` listener. `managed_bot` updates arrive through a new single-slot handler on `TelegramBotModule`, are persisted, and only then published as events.

**Tech Stack:** Java 17, Spring Boot 3.3.5 (BOM), Spring Data JPA, Jackson, JUnit 5 + AssertJ, WireMock (test scope, new).

**Spec:** `docs/superpowers/specs/2026-08-31-managed-bots-design.md`

## Global Constraints

- Java 17 (`maven.compiler.source/target=17`). Records and `switch` expressions are fine; no preview features.
- Branch `feat/managed-bots` is cut from `fix/auth/flow/security-hardening` (v0.4.0) and must merge after it.
- Package for all new main code: `io.github.dev_abdulhay.telegramauth.managedbots`. Tests for it: `src/test/java/io/github/dev_abdulhay/telegramauth/managedbots/`.
- `managedbots` may import from `bot/` only. It must never import `flow/`, `service/`, `web/`, `entity/`, or `repository/`.
- No new runtime dependencies. WireMock is allowed in `test` scope only.
- Config prefix `telegram.managed-bots`; never add keys under `telegram.auth`.
- Tokens are never logged and never printed by `toString`.
- Every commit message follows Conventional Commits and contains no AI attribution trailers.
- Bot API facts are fixed by the spec: `getManagedBotToken(user_id)` → String, `replaceManagedBotToken(user_id)` → String, `getManagedBotAccessSettings(user_id)` → `BotAccessSettings`, `setManagedBotAccessSettings(user_id, is_access_restricted, added_user_ids?)` → True, max 10 added ids, `Update.managed_bot` carries `{user, bot}` only.
- Run the full suite with `mvn test` before every commit; it must stay green (99 tests before this plan starts).

---

### Task 1: Link builder

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotLink.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotLinkTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `static String ManagedBotLink.build(String managerUsername, String suggestedUsername, String suggestedName)` — returns `https://t.me/newbot/{manager}[/{username}][?name={encoded}]`, throws `IllegalArgumentException` on an invalid suggested username.

- [ ] **Step 1: Write the failing test**

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedBotLinkTest {

    @Test
    void buildsTheFullLinkWithUsernameAndEncodedName() {
        assertThat(ManagedBotLink.build("manager_bot", "tenant_shop_bot", "Shop Login"))
                .isEqualTo("https://t.me/newbot/manager_bot/tenant_shop_bot?name=Shop+Login");
    }

    @Test
    void omitsThePartsThatWereNotSuggested() {
        assertThat(ManagedBotLink.build("manager_bot", null, null))
                .isEqualTo("https://t.me/newbot/manager_bot");
        assertThat(ManagedBotLink.build("manager_bot", "tenant_shop_bot", null))
                .isEqualTo("https://t.me/newbot/manager_bot/tenant_shop_bot");
    }

    @Test
    void acceptsTheBotSuffixInAnyCase() {
        assertThat(ManagedBotLink.build("manager_bot", "TenantShopBOT", null))
                .isEqualTo("https://t.me/newbot/manager_bot/TenantShopBOT");
    }

    @Test
    void rejectsUsernamesTelegramWouldNeverAccept() {
        // too short (< 5), no bot suffix, illegal character, too long (> 32)
        assertThatThrownBy(() -> ManagedBotLink.build("manager_bot", "abot", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ManagedBotLink.build("manager_bot", "tenant_shop", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ManagedBotLink.build("manager_bot", "tenant-shop-bot", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ManagedBotLink.build("manager_bot", "t".repeat(30) + "bot", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresAManagerUsername() {
        assertThatThrownBy(() -> ManagedBotLink.build(" ", "tenant_shop_bot", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ManagedBotLinkTest`
Expected: compilation failure — `ManagedBotLink` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Builds the deep link that asks a user to create a bot managed by this bot.
 *
 * <p>The suggested username is only a <em>suggestion</em>: the user can change it
 * in Telegram's confirmation dialog, and the Bot API offers no way to check
 * whether a username is still free. Validation here is local and only rejects
 * what Telegram could never accept.
 */
public final class ManagedBotLink {

    private static final String BASE = "https://t.me/newbot/";

    private ManagedBotLink() {
    }

    public static String build(String managerUsername, String suggestedUsername, String suggestedName) {
        if (managerUsername == null || managerUsername.isBlank()) {
            throw new IllegalArgumentException("managerUsername must not be blank");
        }
        StringBuilder link = new StringBuilder(BASE).append(managerUsername.trim());
        if (suggestedUsername != null && !suggestedUsername.isBlank()) {
            link.append('/').append(validateUsername(suggestedUsername.trim()));
        }
        if (suggestedName != null && !suggestedName.isBlank()) {
            link.append("?name=").append(URLEncoder.encode(suggestedName, StandardCharsets.UTF_8));
        }
        return link.toString();
    }

    private static String validateUsername(String username) {
        if (username.length() < 5 || username.length() > 32) {
            throw new IllegalArgumentException(
                    "a bot username must be 5-32 characters but was " + username.length());
        }
        for (int i = 0; i < username.length(); i++) {
            char c = username.charAt(i);
            boolean allowed = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!allowed) {
                throw new IllegalArgumentException(
                        "a bot username may only contain A-Z, a-z, 0-9 and _ but was " + username);
            }
        }
        if (!username.toLowerCase().endsWith("bot")) {
            throw new IllegalArgumentException("a bot username must end with 'bot' but was " + username);
        }
        return username;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=ManagedBotLinkTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotLink.java \
        src/test/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotLinkTest.java
git commit -m "feat(managed-bots): add the creation deep-link builder"
```

---

### Task 2: Token encryption

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/TokenEncryptor.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/AesGcmTokenEncryptor.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/managedbots/AesGcmTokenEncryptorTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `interface TokenEncryptor { String encrypt(String plaintext); String decrypt(String ciphertext); }` and `AesGcmTokenEncryptor(String base64Key)` which throws `IllegalArgumentException` when the key is not 32 bytes.

- [ ] **Step 1: Write the failing test**

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmTokenEncryptorTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String TOKEN = "123456789:AAHfake-token-value_for-tests";

    @Test
    void roundTripsAToken() {
        AesGcmTokenEncryptor enc = new AesGcmTokenEncryptor(KEY);
        assertThat(enc.decrypt(enc.encrypt(TOKEN))).isEqualTo(TOKEN);
    }

    @Test
    void producesADifferentCiphertextEachTime() {
        AesGcmTokenEncryptor enc = new AesGcmTokenEncryptor(KEY);
        // a fixed IV would leak that two rows hold the same token
        assertThat(enc.encrypt(TOKEN)).isNotEqualTo(enc.encrypt(TOKEN));
    }

    @Test
    void refusesAKeyThatIsNotThirtyTwoBytes() {
        assertThatThrownBy(() -> new AesGcmTokenEncryptor(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
    }

    @Test
    void refusesTamperedCiphertext() {
        AesGcmTokenEncryptor enc = new AesGcmTokenEncryptor(KEY);
        String encrypted = enc.encrypt(TOKEN);
        String tampered = encrypted.substring(0, encrypted.length() - 2)
                + (encrypted.endsWith("A") ? "B=" : "A=");
        assertThatThrownBy(() -> enc.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=AesGcmTokenEncryptorTest`
Expected: compilation failure — `AesGcmTokenEncryptor` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

/**
 * Protects managed-bot tokens at rest. Implement this to delegate to a KMS or
 * vault; declaring your own bean replaces the built-in AES-GCM default.
 */
public interface TokenEncryptor {

    String encrypt(String plaintext);

    String decrypt(String ciphertext);
}
```

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM with a fresh random IV per write, stored as
 * {@code Base64(IV || ciphertext || tag)}. GCM authenticates as well as
 * encrypts, so a tampered value fails to decrypt instead of returning garbage.
 */
public class AesGcmTokenEncryptor implements TokenEncryptor {

    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RNG = new SecureRandom();

    private final SecretKeySpec key;

    public AesGcmTokenEncryptor(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException("encryption key must not be blank");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("encryption key must be Base64-encoded", e);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                    "encryption key must decode to 32 bytes but was " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    @Override
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RNG.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("token encryption failed", e);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        try {
            byte[] all = Base64.getDecoder().decode(ciphertext);
            if (all.length <= IV_BYTES) {
                throw new IllegalArgumentException("ciphertext too short");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // never include the ciphertext or the cause's message in a user-facing string
            throw new IllegalStateException("token decryption failed", e);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=AesGcmTokenEncryptorTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/TokenEncryptor.java \
        src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/AesGcmTokenEncryptor.java \
        src/test/java/io/github/dev_abdulhay/telegramauth/managedbots/AesGcmTokenEncryptorTest.java
git commit -m "feat(managed-bots): add AES-GCM token encryption"
```

---

### Task 3: Store contract and in-memory implementation

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBot.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotTokenStore.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/InMemoryManagedBotStore.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotStoreContract.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/managedbots/InMemoryManagedBotStoreTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `record ManagedBot(long botUserId, String username, String firstName, long ownerUserId, String encryptedToken, OffsetDateTime createdAt, OffsetDateTime updatedAt)` with a masking `toString`; `interface ManagedBotTokenStore { void save(ManagedBot); Optional<ManagedBot> findByBotUserId(long); List<ManagedBot> findByOwnerUserId(long); List<ManagedBot> findAll(); void deleteByBotUserId(long); }`; abstract test class `ManagedBotStoreContract` exposing `protected abstract ManagedBotTokenStore store()`.

- [ ] **Step 1: Write the failing test**

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract every {@link ManagedBotTokenStore} must satisfy. Subclass per implementation. */
abstract class ManagedBotStoreContract {

    protected abstract ManagedBotTokenStore store();

    protected static ManagedBot bot(long botUserId, long ownerUserId) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ManagedBot(botUserId, "tenant_" + botUserId + "_bot", "Tenant " + botUserId,
                ownerUserId, "enc-" + botUserId, now, now);
    }

    @Test
    void savesAndFindsByBotUserId() {
        store().save(bot(101L, 7L));
        assertThat(store().findByBotUserId(101L)).get()
                .extracting(ManagedBot::encryptedToken).isEqualTo("enc-101");
    }

    @Test
    void findByBotUserIdIsEmptyForAnUnknownBot() {
        assertThat(store().findByBotUserId(999L)).isEmpty();
    }

    @Test
    void savingTheSameBotAgainOverwritesInsteadOfDuplicating() {
        store().save(bot(101L, 7L));
        OffsetDateTime now = OffsetDateTime.now();
        store().save(new ManagedBot(101L, "renamed_bot", "Renamed", 7L, "enc-new", now, now));

        assertThat(store().findAll()).hasSize(1);
        assertThat(store().findByBotUserId(101L)).get()
                .extracting(ManagedBot::encryptedToken).isEqualTo("enc-new");
    }

    @Test
    void findsEveryBotOfOneOwner() {
        store().save(bot(101L, 7L));
        store().save(bot(102L, 7L));
        store().save(bot(103L, 8L));

        assertThat(store().findByOwnerUserId(7L))
                .extracting(ManagedBot::botUserId).containsExactlyInAnyOrder(101L, 102L);
    }

    @Test
    void deleteRemovesOnlyTheNamedBot() {
        store().save(bot(101L, 7L));
        store().save(bot(102L, 7L));

        store().deleteByBotUserId(101L);

        assertThat(store().findByBotUserId(101L)).isEmpty();
        assertThat(store().findByBotUserId(102L)).isPresent();
    }

    @Test
    void deletingAnUnknownBotIsNotAnError() {
        store().deleteByBotUserId(404L);
        assertThat(store().findAll()).isEmpty();
    }

    @Test
    void toStringNeverRevealsTheToken() {
        assertThat(bot(101L, 7L).toString()).doesNotContain("enc-101").contains("***");
    }
}
```

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

class InMemoryManagedBotStoreTest extends ManagedBotStoreContract {

    private final InMemoryManagedBotStore store = new InMemoryManagedBotStore();

    @Override
    protected ManagedBotTokenStore store() {
        return store;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=InMemoryManagedBotStoreTest`
Expected: compilation failure — `ManagedBot`, `ManagedBotTokenStore`, `InMemoryManagedBotStore` do not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

import java.time.OffsetDateTime;

/**
 * One bot created on behalf of a user, as this library stores it.
 *
 * @param botUserId      Telegram user id of the bot itself; the id every managed-bot
 *                       API method takes as {@code user_id}
 * @param ownerUserId    Telegram user id of the person who created it
 * @param encryptedToken the bot token, encrypted by the configured {@link TokenEncryptor}
 */
public record ManagedBot(long botUserId, String username, String firstName, long ownerUserId,
                         String encryptedToken, OffsetDateTime createdAt, OffsetDateTime updatedAt) {

    /** Masks the token: a record's generated toString would print it into any log line. */
    @Override
    public String toString() {
        return "ManagedBot[botUserId=" + botUserId + ", username=" + username
                + ", firstName=" + firstName + ", ownerUserId=" + ownerUserId
                + ", encryptedToken=***, createdAt=" + createdAt + ", updatedAt=" + updatedAt + "]";
    }
}
```

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for managed bots. {@code save} is an upsert keyed on
 * {@link ManagedBot#botUserId()} — a re-delivered {@code managed_bot} update must
 * not create a second row.
 *
 * <p>Implementations receive the token already encrypted; they never encrypt or
 * decrypt themselves.
 */
public interface ManagedBotTokenStore {

    void save(ManagedBot bot);

    Optional<ManagedBot> findByBotUserId(long botUserId);

    List<ManagedBot> findByOwnerUserId(long ownerUserId);

    /** Every managed bot; the white-label runtime needs this to restore bots after a restart. */
    List<ManagedBot> findAll();

    void deleteByBotUserId(long botUserId);
}
```

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Map-backed store for tests and hosts that do not use JPA. Not durable. */
public class InMemoryManagedBotStore implements ManagedBotTokenStore {

    private final ConcurrentHashMap<Long, ManagedBot> byBotUserId = new ConcurrentHashMap<>();

    @Override
    public void save(ManagedBot bot) {
        byBotUserId.put(bot.botUserId(), bot);
    }

    @Override
    public Optional<ManagedBot> findByBotUserId(long botUserId) {
        return Optional.ofNullable(byBotUserId.get(botUserId));
    }

    @Override
    public List<ManagedBot> findByOwnerUserId(long ownerUserId) {
        List<ManagedBot> out = new ArrayList<>();
        byBotUserId.values().forEach(b -> {
            if (b.ownerUserId() == ownerUserId) out.add(b);
        });
        return out;
    }

    @Override
    public List<ManagedBot> findAll() {
        return new ArrayList<>(byBotUserId.values());
    }

    @Override
    public void deleteByBotUserId(long botUserId) {
        byBotUserId.remove(botUserId);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=InMemoryManagedBotStoreTest`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBot.java \
        src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotTokenStore.java \
        src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/InMemoryManagedBotStore.java \
        src/test/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotStoreContract.java \
        src/test/java/io/github/dev_abdulhay/telegramauth/managedbots/InMemoryManagedBotStoreTest.java
git commit -m "feat(managed-bots): add the token store contract and in-memory implementation"
```

---

### Task 4: JPA store

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/BaseManagedBot.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/BaseManagedBotRepository.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/JpaManagedBotTokenStore.java`
- Create: `src/test/java/com/example/demo/DemoManagedBot.java`
- Create: `src/test/java/com/example/demo/DemoManagedBotRepository.java`
- Test: `src/test/java/com/example/demo/JpaManagedBotStoreTest.java`

**Interfaces:**
- Consumes: `ManagedBot`, `ManagedBotTokenStore` from Task 3.
- Produces: `abstract class BaseManagedBot` (`@MappedSuperclass`, JavaBean getters/setters); `interface BaseManagedBotRepository<M extends BaseManagedBot> extends JpaRepository<M, Long>` with `Optional<M> findByBotUserId(Long)`, `List<M> findByOwnerUserId(Long)`, `void deleteByBotUserId(Long)`; `class JpaManagedBotTokenStore<M extends BaseManagedBot> implements ManagedBotTokenStore` with constructor `(BaseManagedBotRepository<M> repo, Supplier<M> factory)`.

Note for the implementer: this repo's JPA tests live in `com.example.demo` with `@DataJpaTest` — see `JpaLayerTest` for the existing shape. Keep the demo entity there, not in the library package.

- [ ] **Step 1: Write the failing test**

```java
package com.example.demo;

import io.github.dev_abdulhay.telegramauth.managedbots.JpaManagedBotTokenStore;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBot;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class JpaManagedBotStoreTest {

    @Autowired
    private DemoManagedBotRepository repo;

    private ManagedBotTokenStore store;

    @BeforeEach
    void setUp() {
        store = new JpaManagedBotTokenStore<>(repo, DemoManagedBot::new);
    }

    private static ManagedBot bot(long botUserId, long ownerUserId) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ManagedBot(botUserId, "tenant_" + botUserId + "_bot", "Tenant " + botUserId,
                ownerUserId, "enc-" + botUserId, now, now);
    }

    @Test
    void savesAndReadsBackThroughTheDatabase() {
        store.save(bot(101L, 7L));

        assertThat(store.findByBotUserId(101L)).get()
                .extracting(ManagedBot::encryptedToken).isEqualTo("enc-101");
    }

    @Test
    void savingTheSameBotAgainUpdatesTheExistingRow() {
        store.save(bot(101L, 7L));
        OffsetDateTime now = OffsetDateTime.now();
        store.save(new ManagedBot(101L, "renamed_bot", "Renamed", 7L, "enc-new", now, now));

        assertThat(repo.count()).isEqualTo(1);
        assertThat(store.findByBotUserId(101L)).get()
                .extracting(ManagedBot::encryptedToken).isEqualTo("enc-new");
    }

    @Test
    void findsByOwnerAndDeletesByBotUserId() {
        store.save(bot(101L, 7L));
        store.save(bot(102L, 7L));
        store.save(bot(103L, 8L));

        assertThat(store.findByOwnerUserId(7L)).hasSize(2);

        store.deleteByBotUserId(101L);
        assertThat(store.findByBotUserId(101L)).isEmpty();
        assertThat(store.findAll()).hasSize(2);
    }
}
```

```java
package com.example.demo;

import io.github.dev_abdulhay.telegramauth.managedbots.BaseManagedBot;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "demo_managed_bot",
       indexes = @Index(name = "ix_demo_managed_bot_owner", columnList = "owner_user_id"))
public class DemoManagedBot extends BaseManagedBot {
}
```

```java
package com.example.demo;

import io.github.dev_abdulhay.telegramauth.managedbots.BaseManagedBotRepository;

public interface DemoManagedBotRepository extends BaseManagedBotRepository<DemoManagedBot> {
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=JpaManagedBotStoreTest`
Expected: compilation failure — `BaseManagedBot`, `BaseManagedBotRepository`, `JpaManagedBotTokenStore` do not exist.

- [ ] **Step 3: Write minimal implementation**

```java
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
```

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;
import java.util.Optional;

/**
 * Base repository for any {@link BaseManagedBot} subtype. Host repositories
 * extend this with their concrete entity: {@code interface TenantBotRepository
 * extends BaseManagedBotRepository<TenantBot> {}}.
 */
@NoRepositoryBean
public interface BaseManagedBotRepository<M extends BaseManagedBot> extends JpaRepository<M, Long> {

    Optional<M> findByBotUserId(Long botUserId);

    List<M> findByOwnerUserId(Long ownerUserId);

    void deleteByBotUserId(Long botUserId);
}
```

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * JPA-backed {@link ManagedBotTokenStore}. {@code save} updates the existing row
 * when the bot is already known, so a re-delivered update cannot duplicate it.
 *
 * @param <M> the host's concrete {@link BaseManagedBot} entity
 */
public class JpaManagedBotTokenStore<M extends BaseManagedBot> implements ManagedBotTokenStore {

    private final BaseManagedBotRepository<M> repo;
    private final Supplier<M> factory;

    public JpaManagedBotTokenStore(BaseManagedBotRepository<M> repo, Supplier<M> factory) {
        this.repo = repo;
        this.factory = factory;
    }

    @Override
    @Transactional
    public void save(ManagedBot bot) {
        M entity = repo.findByBotUserId(bot.botUserId()).orElseGet(factory);
        if (entity.getBotUserId() == null) {
            entity.setCreatedAt(bot.createdAt());
        }
        entity.setBotUserId(bot.botUserId());
        entity.setUsername(bot.username());
        entity.setFirstName(bot.firstName());
        entity.setOwnerUserId(bot.ownerUserId());
        entity.setEncryptedToken(bot.encryptedToken());
        entity.setUpdatedAt(bot.updatedAt());
        repo.save(entity);
    }

    @Override
    public Optional<ManagedBot> findByBotUserId(long botUserId) {
        return repo.findByBotUserId(botUserId).map(JpaManagedBotTokenStore::toRecord);
    }

    @Override
    public List<ManagedBot> findByOwnerUserId(long ownerUserId) {
        List<ManagedBot> out = new ArrayList<>();
        repo.findByOwnerUserId(ownerUserId).forEach(e -> out.add(toRecord(e)));
        return out;
    }

    @Override
    public List<ManagedBot> findAll() {
        List<ManagedBot> out = new ArrayList<>();
        repo.findAll().forEach(e -> out.add(toRecord(e)));
        return out;
    }

    @Override
    @Transactional
    public void deleteByBotUserId(long botUserId) {
        repo.deleteByBotUserId(botUserId);
    }

    private static ManagedBot toRecord(BaseManagedBot e) {
        return new ManagedBot(e.getBotUserId(), e.getUsername(), e.getFirstName(),
                e.getOwnerUserId(), e.getEncryptedToken(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=JpaManagedBotStoreTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/BaseManagedBot.java \
        src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/BaseManagedBotRepository.java \
        src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/JpaManagedBotTokenStore.java \
        src/test/java/com/example/demo/DemoManagedBot.java \
        src/test/java/com/example/demo/DemoManagedBotRepository.java \
        src/test/java/com/example/demo/JpaManagedBotStoreTest.java
git commit -m "feat(managed-bots): add the JPA token store"
```

---

### Task 5: Client methods for the managed-bot API

**Files:**
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBot.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/bot/ManagedBotApiTest.java`
- Modify: `pom.xml` (add WireMock, test scope)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: on `TelegramBot` — `String getManagedBotToken(long botUserId)`, `String replaceManagedBotToken(long botUserId)`, `JsonNode getManagedBotAccessSettings(long botUserId)`, `void setManagedBotAccessSettings(long botUserId, boolean restricted, List<Long> addedUserIds)`. All throw `TelegramApiException` (new, in `bot/`) when Telegram answers `ok:false` or a non-2xx status that is not 429; a 429 is waited out once using `retry_after` and then retried.

Note for the implementer: `TelegramBot#post` currently discards the body. Add a private `postForResult(String method, String body)` that reads the body, parses `{"ok":..., "result":...}`, honours 429 once, and returns the `result` node. The existing `post` stays untouched so the auth paths keep their fire-and-forget behaviour.

- [ ] **Step 1: Write the failing test**

```java
package io.github.dev_abdulhay.telegramauth.bot;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedBotApiTest {

    private WireMockServer server;
    private TelegramBot bot;

    @BeforeEach
    void start() {
        server = new WireMockServer(0);
        server.start();
        bot = new TelegramBot(HttpClient.newHttpClient(), "123:ABC", "http://localhost:" + server.port());
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void getManagedBotTokenReturnsTheTokenFromTheResult() {
        server.stubFor(post(urlPathEqualTo("/bot123:ABC/getManagedBotToken"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"result\":\"999:CHILD-TOKEN\"}")));

        assertThat(bot.getManagedBotToken(555L)).isEqualTo("999:CHILD-TOKEN");
        server.verify(postRequestedFor(urlPathEqualTo("/bot123:ABC/getManagedBotToken"))
                .withRequestBody(WireMock.containing("user_id=555")));
    }

    @Test
    void replaceManagedBotTokenReturnsTheNewToken() {
        server.stubFor(post(urlPathEqualTo("/bot123:ABC/replaceManagedBotToken"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"result\":\"999:ROTATED\"}")));

        assertThat(bot.replaceManagedBotToken(555L)).isEqualTo("999:ROTATED");
    }

    @Test
    void accessSettingsAreReadAndWritten() {
        server.stubFor(post(urlPathEqualTo("/bot123:ABC/getManagedBotAccessSettings"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"result\":{\"is_access_restricted\":true,"
                                + "\"added_users\":[{\"id\":42,\"username\":\"ann\",\"first_name\":\"Ann\"}]}}")));
        server.stubFor(post(urlPathEqualTo("/bot123:ABC/setManagedBotAccessSettings"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"result\":true}")));

        assertThat(bot.getManagedBotAccessSettings(555L).path("is_access_restricted").asBoolean()).isTrue();

        bot.setManagedBotAccessSettings(555L, true, List.of(42L, 43L));
        server.verify(postRequestedFor(urlPathEqualTo("/bot123:ABC/setManagedBotAccessSettings"))
                .withRequestBody(WireMock.containing("is_access_restricted=true"))
                .withRequestBody(WireMock.containing("added_user_ids=%5B42%2C43%5D")));
    }

    @Test
    void aTooManyRequestsResponseIsWaitedOutAndRetriedOnce() {
        server.stubFor(post(urlPathEqualTo("/bot123:ABC/getManagedBotToken"))
                .inScenario("429").whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(429).withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":1}}"))
                .willSetStateTo("second"));
        server.stubFor(post(urlPathEqualTo("/bot123:ABC/getManagedBotToken"))
                .inScenario("429").whenScenarioStateIs("second")
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"result\":\"999:AFTER-WAIT\"}")));

        assertThat(bot.getManagedBotToken(555L)).isEqualTo("999:AFTER-WAIT");
        assertThat(server.getAllServeEvents()).hasSize(2);
    }

    @Test
    void anOkFalseResponseBecomesAnException() {
        server.stubFor(post(urlPathEqualTo("/bot123:ABC/getManagedBotToken"))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":false,\"error_code\":400,\"description\":\"BOT_NOT_MANAGED\"}")));

        assertThatThrownBy(() -> bot.getManagedBotToken(555L))
                .isInstanceOf(TelegramApiException.class)
                .hasMessageContaining("BOT_NOT_MANAGED");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ManagedBotApiTest`
Expected: compilation failure — WireMock is not on the classpath and the four methods do not exist.

- [ ] **Step 3: Write minimal implementation**

Add to `pom.xml` `<dependencies>` (test scope; the Spring Boot BOM does not manage WireMock, so pin it):

```xml
<dependency>
    <groupId>org.wiremock</groupId>
    <artifactId>wiremock</artifactId>
    <version>3.9.1</version>
    <scope>test</scope>
</dependency>
```

Create `src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramApiException.java`:

```java
package io.github.dev_abdulhay.telegramauth.bot;

/** Thrown when the Bot API answers with {@code ok:false} or an unrecoverable HTTP status. */
public class TelegramApiException extends RuntimeException {

    private final int errorCode;

    public TelegramApiException(int errorCode, String description) {
        super("Telegram API error " + errorCode + ": " + description);
        this.errorCode = errorCode;
    }

    public int getErrorCode() {
        return errorCode;
    }
}
```

Add to `TelegramBot` (keep the existing `post` untouched):

```java
    /** Telegram's own ceiling for a rate-limit wait we are willing to sit through. */
    private static final int MAX_RETRY_AFTER_SECONDS = 60;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** @param botUserId Telegram user id of the managed bot, the API's {@code user_id} */
    public String getManagedBotToken(long botUserId) {
        return postForResult("getManagedBotToken", "user_id=" + botUserId).asText();
    }

    /** Revokes the managed bot's current token and returns the new one. */
    public String replaceManagedBotToken(long botUserId) {
        return postForResult("replaceManagedBotToken", "user_id=" + botUserId).asText();
    }

    public JsonNode getManagedBotAccessSettings(long botUserId) {
        return postForResult("getManagedBotAccessSettings", "user_id=" + botUserId);
    }

    /**
     * @param addedUserIds up to 10 users who may access the bot besides its owner;
     *                     ignored by Telegram when {@code restricted} is false
     */
    public void setManagedBotAccessSettings(long botUserId, boolean restricted, List<Long> addedUserIds) {
        StringBuilder body = new StringBuilder("user_id=").append(botUserId)
                .append("&is_access_restricted=").append(restricted);
        if (addedUserIds != null && !addedUserIds.isEmpty()) {
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < addedUserIds.size(); i++) {
                if (i > 0) json.append(',');
                json.append(addedUserIds.get(i));
            }
            json.append(']');
            body.append("&added_user_ids=")
                    .append(URLEncoder.encode(json.toString(), StandardCharsets.UTF_8));
        }
        postForResult("setManagedBotAccessSettings", body.toString());
    }

    /**
     * POSTs and returns the {@code result} node. A 429 is a wait signal rather than
     * a failure: we honour {@code retry_after} once and retry, which is separate
     * from any retry budget the caller keeps.
     */
    private JsonNode postForResult(String method, String body) {
        JsonNode response = send(method, body);
        Integer retryAfter = rateLimitDelay(response);
        if (retryAfter != null) {
            try {
                Thread.sleep(Math.min(retryAfter, MAX_RETRY_AFTER_SECONDS) * 1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new TelegramApiException(429, "interrupted while waiting out a rate limit");
            }
            response = send(method, body);
        }
        if (!response.path("ok").asBoolean(false)) {
            throw new TelegramApiException(response.path("error_code").asInt(0),
                    response.path("description").asText("unknown error"));
        }
        return response.path("result");
    }

    private static Integer rateLimitDelay(JsonNode response) {
        if (response.path("error_code").asInt(0) != 429) return null;
        int seconds = response.path("parameters").path("retry_after").asInt(1);
        return Math.max(seconds, 1);
    }

    private JsonNode send(String method, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/bot" + token + "/" + method))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(SEND_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return MAPPER.readTree(resp.body());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new TelegramApiException(0, method + " interrupted");
        } catch (TelegramApiException e) {
            throw e;
        } catch (Exception e) {
            throw new TelegramApiException(0, method + " failed: " + e.getClass().getSimpleName());
        }
    }
```

Add the imports `com.fasterxml.jackson.databind.JsonNode`, `com.fasterxml.jackson.databind.ObjectMapper` and `java.util.List` to `TelegramBot`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=ManagedBotApiTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add pom.xml \
        src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramApiException.java \
        src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBot.java \
        src/test/java/io/github/dev_abdulhay/telegramauth/bot/ManagedBotApiTest.java
git commit -m "feat(bot): add managed-bot API methods with rate-limit handling"
```

---

### Task 6: Module slot and dispatcher routing

**Files:**
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotModule.java`
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/bot/BotUpdateDispatcher.java`
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBot.java` (getUpdates)
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotRunner.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/bot/ManagedBotRoutingTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `TelegramBotModule#onManagedBot(Consumer<JsonNode>)` and `#getManagedBotHandler()`; `TelegramBot#getUpdates(long offset, int timeoutSeconds, List<String> allowedUpdates)` (the two-argument overload stays and delegates with `null`).

Note for the implementer: `managed_bot` is a top-level `Update` field, so route it first in `BotUpdateDispatcher#route`, before the `callback_query` branch. Register the slot with the existing `claimSlot` guard, exactly like `onCallbackQuery`. In `TelegramBotRunner#loop`, pass `List.of("message", "callback_query", "managed_bot")` when the module has a managed-bot handler, and `null` otherwise — sending `allowed_updates` unconditionally would change behaviour for hosts that rely on Telegram's default list.

- [ ] **Step 1: Write the failing test**

```java
package io.github.dev_abdulhay.telegramauth.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedBotRoutingTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static TelegramBotModule module() {
        return TelegramBotModule.builder("123:ABC", "manager_bot").build();
    }

    @Test
    void managedBotUpdatesReachTheManagedBotHandler() throws Exception {
        TelegramBotModule module = module();
        List<Long> seen = new ArrayList<>();
        module.onManagedBot(u -> seen.add(u.path("managed_bot").path("bot").path("id").asLong()));

        String json = "{\"ok\":true,\"result\":[{\"update_id\":1,\"managed_bot\":"
                + "{\"user\":{\"id\":7},\"bot\":{\"id\":555,\"username\":\"tenant_bot\"}}}]}";
        new BotUpdateDispatcher(module).dispatch(json);

        assertThat(seen).containsExactly(555L);
    }

    @Test
    void ordinaryUpdatesAreStillRoutedAsBefore() throws Exception {
        TelegramBotModule module = module();
        List<String> commands = new ArrayList<>();
        List<String> managed = new ArrayList<>();
        module.command("/start", u -> commands.add("start"));
        module.onManagedBot(u -> managed.add("managed"));

        String json = "{\"ok\":true,\"result\":[{\"update_id\":1,\"message\":"
                + "{\"text\":\"/start\",\"chat\":{\"id\":7},\"from\":{\"id\":7}}}]}";
        new BotUpdateDispatcher(module).dispatch(json);

        assertThat(commands).containsExactly("start");
        assertThat(managed).isEmpty();
    }

    @Test
    void theSlotRefusesASecondHandler() {
        TelegramBotModule module = module();
        module.onManagedBot(u -> { });

        assertThatThrownBy(() -> module.onManagedBot(u -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("managed_bot");
    }

    @Test
    void aManagedBotUpdateWithNoHandlerFallsBackToTheModuleFallback() throws Exception {
        TelegramBotModule module = module();
        List<JsonNode> fallback = new ArrayList<>();
        module.fallback(fallback::add);

        String json = "{\"ok\":true,\"result\":[{\"update_id\":1,\"managed_bot\":"
                + "{\"user\":{\"id\":7},\"bot\":{\"id\":555}}}]}";
        new BotUpdateDispatcher(module).dispatch(json);

        assertThat(fallback).hasSize(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ManagedBotRoutingTest`
Expected: compilation failure — `onManagedBot` does not exist.

- [ ] **Step 3: Write minimal implementation**

In `TelegramBotModule`, beside the other slots:

```java
    private volatile Consumer<JsonNode> managedBotHandler;

    /**
     * Handler for {@code managed_bot} updates — the creation, token change or
     * owner change of a bot this bot manages. Single-slot with the same
     * replace-guard as {@link #onCallbackQuery(Consumer)}.
     *
     * @throws IllegalStateException if a different handler is already registered
     */
    public void onManagedBot(Consumer<JsonNode> handler) {
        this.managedBotHandler = claimSlot("managed_bot", this.managedBotHandler, handler);
    }

    public Consumer<JsonNode> getManagedBotHandler() { return managedBotHandler; }
```

In `BotUpdateDispatcher#route`, as the first branch:

```java
        if (update.has("managed_bot")) {
            Consumer<JsonNode> handler = module.getManagedBotHandler();
            invoke(handler != null ? handler : module.getFallback(), update);
            return;
        }
```

In `TelegramBot`, replace `getUpdates` with an overload pair:

```java
    public String getUpdates(long offset, int timeoutSeconds) throws Exception {
        return getUpdates(offset, timeoutSeconds, null);
    }

    /**
     * @param allowedUpdates update types to receive, or {@code null} to let Telegram
     *                       apply its default list. The default list excludes
     *                       {@code managed_bot}, so a manager bot must pass it explicitly.
     */
    public String getUpdates(long offset, int timeoutSeconds, List<String> allowedUpdates) throws Exception {
        StringBuilder url = new StringBuilder(baseUrl).append("/bot").append(token)
                .append("/getUpdates?offset=").append(offset)
                .append("&timeout=").append(timeoutSeconds);
        if (allowedUpdates != null && !allowedUpdates.isEmpty()) {
            url.append("&allowed_updates=")
                    .append(URLEncoder.encode("[\"" + String.join("\",\"", allowedUpdates) + "\"]",
                            StandardCharsets.UTF_8));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds((long) timeoutSeconds + 5))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            log.warn("getUpdates non-2xx: {}", resp.statusCode());
        }
        return resp.body();
    }
```

In `TelegramBotRunner#loop`, replace the `getUpdates` call:

```java
            List<String> allowed = module.getManagedBotHandler() != null
                    ? List.of("message", "callback_query", "managed_bot")
                    : null;
            String json = module.getBot().getUpdates(offset.get(), timeoutS, allowed);
```

Hoist `allowed` above the `while` loop so it is computed once, and add the `java.util.List` import.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=ManagedBotRoutingTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Run the full suite**

Run: `mvn test`
Expected: BUILD SUCCESS. The dispatcher and runner changes touch shared code, so `BotUpdateDispatcherTest` and `WorkerBackpressureTest` must still pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotModule.java \
        src/main/java/io/github/dev_abdulhay/telegramauth/bot/BotUpdateDispatcher.java \
        src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBot.java \
        src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotRunner.java \
        src/test/java/io/github/dev_abdulhay/telegramauth/bot/ManagedBotRoutingTest.java
git commit -m "feat(bot): route managed_bot updates and allow explicit allowed_updates"
```

---

### Task 7: Service, events, and the update handler

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotEvents.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/BotAccess.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotUser.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotService.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotUpdateHandler.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotServiceTest.java`

**Interfaces:**
- Consumes: `ManagedBot`, `ManagedBotTokenStore`, `InMemoryManagedBotStore` (Task 3); `TokenEncryptor` (Task 2); `ManagedBotLink` (Task 1); `TelegramBot#getManagedBotToken/replaceManagedBotToken/getManagedBotAccessSettings/setManagedBotAccessSettings` and `TelegramApiException` (Task 5); `TelegramBotModule#onManagedBot` (Task 6).
- Produces: `ManagedBotService(TelegramBotModule module, ManagedBotTokenStore store, TokenEncryptor encryptor, ManagedBotEvents events, int tokenFetchRetries, Duration tokenFetchBackoff)` with the six public methods from the spec, plus `void handleUpdate(JsonNode update)`; `ManagedBotUpdateHandler` registers `service::handleUpdate` into the module's slot on construction.

Note for the implementer: `ManagedBotService` is a concrete class, not an interface — the spec's signature block describes its public surface. Retries apply only to `getManagedBotToken` inside `handleUpdate`; `rotateToken` called by the host propagates its exception directly.

- [ ] **Step 1: Write the failing test**

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dev_abdulhay.telegramauth.bot.TelegramApiException;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBot;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedBotServiceTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** Records calls and returns canned answers; no HTTP anywhere. */
    static class FakeBot extends TelegramBot {
        final List<String> calls = new ArrayList<>();
        String token = "999:CHILD";
        int failFetches;

        FakeBot() {
            super(HttpClient.newHttpClient(), "123:ABC");
        }

        @Override public String getManagedBotToken(long botUserId) {
            calls.add("get:" + botUserId);
            if (failFetches > 0) {
                failFetches--;
                throw new TelegramApiException(500, "transient");
            }
            return token;
        }

        @Override public String replaceManagedBotToken(long botUserId) {
            calls.add("replace:" + botUserId);
            return "999:ROTATED";
        }
    }

    static class RecordingEvents implements ManagedBotEvents {
        final List<String> events = new ArrayList<>();
        @Override public void onCreated(ManagedBot bot) { events.add("created:" + bot.botUserId()); }
        @Override public void onTokenRotated(ManagedBot bot) { events.add("rotated:" + bot.botUserId()); }
        @Override public void onTokenFetchFailed(long botUserId, long ownerUserId, Exception cause) {
            events.add("failed:" + botUserId);
        }
        @Override public void onDecommissioned(long botUserId) { events.add("decommissioned:" + botUserId); }
    }

    record Env(FakeBot bot, InMemoryManagedBotStore store, RecordingEvents events,
               ManagedBotService service) {}

    private static Env env() {
        FakeBot bot = new FakeBot();
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "manager_bot").bot(bot).build();
        InMemoryManagedBotStore store = new InMemoryManagedBotStore();
        RecordingEvents events = new RecordingEvents();
        TokenEncryptor enc = new TokenEncryptor() {
            @Override public String encrypt(String p) { return "ENC(" + p + ")"; }
            @Override public String decrypt(String c) { return c.substring(4, c.length() - 1); }
        };
        ManagedBotService service = new ManagedBotService(module, store, enc, events, 3, Duration.ZERO);
        return new Env(bot, store, events, service);
    }

    private static JsonNode managedBotUpdate(long botId, long ownerId) throws Exception {
        return M.readTree("{\"managed_bot\":{\"user\":{\"id\":" + ownerId + "},"
                + "\"bot\":{\"id\":" + botId + ",\"username\":\"tenant_bot\",\"first_name\":\"Tenant\"}}}");
    }

    @Test
    void createLinkUsesTheModuleUsername() {
        assertThat(env().service().createLink("tenant_shop_bot", "Shop"))
                .isEqualTo("https://t.me/newbot/manager_bot/tenant_shop_bot?name=Shop");
    }

    @Test
    void aNewManagedBotIsStoredEncryptedAndThenAnnounced() throws Exception {
        Env e = env();

        e.service().handleUpdate(managedBotUpdate(555L, 7L));

        assertThat(e.store().findByBotUserId(555L)).get()
                .extracting(ManagedBot::encryptedToken).isEqualTo("ENC(999:CHILD)");
        assertThat(e.service().findToken(555L)).contains("999:CHILD");
        assertThat(e.events().events).containsExactly("created:555");
    }

    @Test
    void aSecondUpdateForAKnownBotCountsAsARotationAndOverwrites() throws Exception {
        Env e = env();
        e.service().handleUpdate(managedBotUpdate(555L, 7L));
        e.bot().token = "999:NEW";

        e.service().handleUpdate(managedBotUpdate(555L, 7L));

        assertThat(e.store().findAll()).hasSize(1);
        assertThat(e.service().findToken(555L)).contains("999:NEW");
        assertThat(e.events().events).containsExactly("created:555", "rotated:555");
    }

    @Test
    void aFetchThatKeepsFailingStoresNothingAndReportsFailure() throws Exception {
        Env e = env();
        e.bot().failFetches = 5; // more than the 3 configured attempts

        e.service().handleUpdate(managedBotUpdate(555L, 7L));

        assertThat(e.store().findByBotUserId(555L)).isEmpty();
        assertThat(e.events().events).containsExactly("failed:555");
        assertThat(e.bot().calls).hasSize(3);
    }

    @Test
    void aTransientFailureIsRetriedAndThenSucceeds() throws Exception {
        Env e = env();
        e.bot().failFetches = 2;

        e.service().handleUpdate(managedBotUpdate(555L, 7L));

        assertThat(e.store().findByBotUserId(555L)).isPresent();
        assertThat(e.events().events).containsExactly("created:555");
    }

    @Test
    void rotateTokenStoresTheNewTokenAndAnnouncesIt() throws Exception {
        Env e = env();
        e.service().handleUpdate(managedBotUpdate(555L, 7L));
        e.events().events.clear();

        assertThat(e.service().rotateToken(555L)).isEqualTo("999:ROTATED");
        assertThat(e.service().findToken(555L)).contains("999:ROTATED");
        assertThat(e.events().events).containsExactly("rotated:555");
    }

    @Test
    void decommissionRevokesBeforeForgettingTheBot() throws Exception {
        Env e = env();
        e.service().handleUpdate(managedBotUpdate(555L, 7L));
        e.bot().calls.clear();

        e.service().decommission(555L);

        // revoke must happen while we still hold the row; the new token is discarded
        assertThat(e.bot().calls).containsExactly("replace:555");
        assertThat(e.store().findByBotUserId(555L)).isEmpty();
        assertThat(e.events().events).contains("decommissioned:555");
    }

    @Test
    void decommissionStillForgetsTheBotWhenRevocationFails() throws Exception {
        Env e = env();
        e.service().handleUpdate(managedBotUpdate(555L, 7L));
        FakeBot failing = new FakeBot() {
            @Override public String replaceManagedBotToken(long botUserId) {
                throw new TelegramApiException(400, "BOT_NOT_FOUND");
            }
        };
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "manager_bot").bot(failing).build();
        ManagedBotService service = new ManagedBotService(module, e.store(),
                new TokenEncryptor() {
                    @Override public String encrypt(String p) { return "ENC(" + p + ")"; }
                    @Override public String decrypt(String c) { return c.substring(4, c.length() - 1); }
                }, e.events(), 3, Duration.ZERO);

        service.decommission(555L);

        assertThat(e.store().findByBotUserId(555L)).isEmpty();
    }

    @Test
    void moreThanTenAddedUsersIsRejectedBeforeCallingTelegram() {
        Env e = env();
        List<Long> eleven = new ArrayList<>();
        for (long i = 1; i <= 11; i++) eleven.add(i);

        assertThatThrownBy(() -> e.service().setAccessSettings(555L, true, eleven))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10");
        assertThat(e.bot().calls).isEmpty();
    }

    @Test
    void findTokenIsEmptyForAnUnknownBot() {
        assertThat(env().service().findToken(404L)).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ManagedBotServiceTest`
Expected: compilation failure — `ManagedBotService`, `ManagedBotEvents` do not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

/**
 * Lifecycle hooks for managed bots. Every method has a no-op default, so a host
 * implements only what it needs. Handlers run on the bot's update worker thread —
 * keep them short and hand long work to an executor.
 */
public interface ManagedBotEvents {

    /** A bot was created and its token is already stored. */
    default void onCreated(ManagedBot bot) { }

    /** An existing bot's token changed and the stored copy has been replaced. */
    default void onTokenRotated(ManagedBot bot) { }

    /** The token could not be fetched after every retry; nothing was stored. */
    default void onTokenFetchFailed(long botUserId, long ownerUserId, Exception cause) { }

    /** The bot was decommissioned: its token is revoked and the row is gone. */
    default void onDecommissioned(long botUserId) { }
}
```

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

/** A user who has access to a restricted managed bot. */
public record ManagedBotUser(long userId, String username, String firstName) {}
```

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

import java.util.List;

/**
 * A managed bot's access settings as Telegram reports them.
 *
 * <p>Note the asymmetry with {@code setAccessSettings}: reads return whole users,
 * writes take ids.
 *
 * @param addedUsers users allowed besides the owner; empty when access is open
 */
public record BotAccess(boolean restricted, List<ManagedBotUser> addedUsers) {}
```

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Creates bots on behalf of users and keeps custody of their tokens.
 *
 * <p>Telegram offers no way to delete a managed bot, so {@link #decommission(long)}
 * revokes the token and forgets the bot locally; the bot itself keeps existing and
 * stays owned by the user, who removes it through BotFather.
 */
public class ManagedBotService {

    private static final Logger log = LoggerFactory.getLogger(ManagedBotService.class);
    /** Telegram's ceiling for {@code added_user_ids}. */
    private static final int MAX_ADDED_USERS = 10;

    private final TelegramBotModule module;
    private final ManagedBotTokenStore store;
    private final TokenEncryptor encryptor;
    private final ManagedBotEvents events;
    private final int tokenFetchRetries;
    private final Duration tokenFetchBackoff;

    public ManagedBotService(TelegramBotModule module, ManagedBotTokenStore store,
                             TokenEncryptor encryptor, ManagedBotEvents events,
                             int tokenFetchRetries, Duration tokenFetchBackoff) {
        this.module = module;
        this.store = store;
        this.encryptor = encryptor;
        this.events = events;
        this.tokenFetchRetries = Math.max(1, tokenFetchRetries);
        this.tokenFetchBackoff = tokenFetchBackoff == null ? Duration.ZERO : tokenFetchBackoff;
    }

    /**
     * Link that asks a user to create a bot managed by this one. The username is
     * only a suggestion: the user can change it in the confirmation dialog, and
     * the Bot API cannot tell us whether it is still free.
     */
    public String createLink(String suggestedUsername, String suggestedName) {
        return ManagedBotLink.build(module.getUsername(), suggestedUsername, suggestedName);
    }

    /** The stored token, decrypted. Reads locally — never calls Telegram. */
    public Optional<String> findToken(long botUserId) {
        return store.findByBotUserId(botUserId)
                .map(b -> encryptor.decrypt(b.encryptedToken()));
    }

    /** Revokes the current token, stores the replacement, and announces the rotation. */
    public String rotateToken(long botUserId) {
        ManagedBot existing = store.findByBotUserId(botUserId).orElseThrow(
                () -> new IllegalArgumentException("unknown managed bot " + botUserId));
        String fresh = module.getBot().replaceManagedBotToken(botUserId);
        ManagedBot saved = persist(existing.botUserId(), existing.username(), existing.firstName(),
                existing.ownerUserId(), fresh, existing.createdAt());
        events.onTokenRotated(saved);
        return fresh;
    }

    public BotAccess getAccessSettings(long botUserId) {
        JsonNode result = module.getBot().getManagedBotAccessSettings(botUserId);
        List<ManagedBotUser> added = new ArrayList<>();
        for (JsonNode u : result.path("added_users")) {
            added.add(new ManagedBotUser(u.path("id").asLong(),
                    u.path("username").asText(null), u.path("first_name").asText(null)));
        }
        return new BotAccess(result.path("is_access_restricted").asBoolean(false), added);
    }

    /**
     * @param addedUserIds at most 10 users besides the owner; Telegram ignores them
     *                     when {@code restricted} is false
     */
    public void setAccessSettings(long botUserId, boolean restricted, List<Long> addedUserIds) {
        if (addedUserIds != null && addedUserIds.size() > MAX_ADDED_USERS) {
            throw new IllegalArgumentException(
                    "Telegram accepts at most 10 added users but got " + addedUserIds.size());
        }
        module.getBot().setManagedBotAccessSettings(botUserId, restricted, addedUserIds);
    }

    /**
     * Revokes the token and forgets the bot. Revocation runs first: deleting the
     * row first would destroy the credentials the revocation needs and leave a bot
     * we can no longer reach. A failed revocation still clears local state.
     */
    public void decommission(long botUserId) {
        try {
            module.getBot().replaceManagedBotToken(botUserId);
        } catch (RuntimeException e) {
            log.warn("could not revoke the token of managed bot {}; forgetting it anyway", botUserId, e);
        }
        store.deleteByBotUserId(botUserId);
        events.onDecommissioned(botUserId);
    }

    /**
     * Processes one {@code managed_bot} update: fetch the token, store it, and only
     * then publish, so a listener that calls {@link #findToken(long)} always finds it.
     *
     * <p>The update says nothing about what changed, so the store decides: an unknown
     * bot is a creation, a known one a rotation. Re-fetching every time makes a
     * re-delivered update harmless.
     */
    public void handleUpdate(JsonNode update) {
        JsonNode managed = update.path("managed_bot");
        JsonNode botNode = managed.path("bot");
        long botUserId = botNode.path("id").asLong();
        long ownerUserId = managed.path("user").path("id").asLong();
        if (botUserId == 0) {
            log.warn("managed_bot update without a bot id, ignoring");
            return;
        }

        Optional<ManagedBot> known = store.findByBotUserId(botUserId);
        String token;
        try {
            token = fetchTokenWithRetries(botUserId);
        } catch (RuntimeException e) {
            log.warn("giving up on the token of managed bot {} after {} attempts",
                    botUserId, tokenFetchRetries, e);
            events.onTokenFetchFailed(botUserId, ownerUserId, e);
            return;
        }

        ManagedBot saved = persist(botUserId,
                botNode.path("username").asText(null),
                botNode.path("first_name").asText(null),
                ownerUserId, token,
                known.map(ManagedBot::createdAt).orElse(null));
        if (known.isEmpty()) {
            events.onCreated(saved);
        } else {
            events.onTokenRotated(saved);
        }
    }

    private String fetchTokenWithRetries(long botUserId) {
        RuntimeException last = null;
        Duration wait = tokenFetchBackoff;
        for (int attempt = 1; attempt <= tokenFetchRetries; attempt++) {
            try {
                return module.getBot().getManagedBotToken(botUserId);
            } catch (RuntimeException e) {
                last = e;
                if (attempt < tokenFetchRetries && !wait.isZero() && !wait.isNegative()) {
                    try {
                        Thread.sleep(wait.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    wait = wait.multipliedBy(2);
                }
            }
        }
        throw last;
    }

    private ManagedBot persist(long botUserId, String username, String firstName,
                               long ownerUserId, String rawToken, OffsetDateTime createdAt) {
        OffsetDateTime now = OffsetDateTime.now();
        ManagedBot bot = new ManagedBot(botUserId, username, firstName, ownerUserId,
                encryptor.encrypt(rawToken), createdAt == null ? now : createdAt, now);
        store.save(bot);
        return bot;
    }
}
```

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;

/**
 * Claims the module's {@code managed_bot} slot for a {@link ManagedBotService}.
 * Constructing this is all the wiring a host needs; the auto-configuration does
 * it when the feature is enabled.
 */
public class ManagedBotUpdateHandler {

    public ManagedBotUpdateHandler(TelegramBotModule module, ManagedBotService service) {
        module.onManagedBot(service::handleUpdate);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=ManagedBotServiceTest`
Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/ \
        src/test/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotServiceTest.java
git commit -m "feat(managed-bots): add the service, events and update handler"
```

---

### Task 8: Configuration and auto-configuration

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/TelegramManagedBotsProperties.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/TelegramManagedBotsAutoConfiguration.java`
- Modify: `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `src/test/java/com/example/demo/ManagedBotsAutoConfigTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1–7.
- Produces: `telegram.managed-bots.*` binding and beans `TokenEncryptor`, `ManagedBotService`, `ManagedBotUpdateHandler` (each `@ConditionalOnMissingBean`). No `ManagedBotTokenStore` bean is auto-registered — the host declares one, because only the host knows its entity type.

Note for the implementer: the existing imports file has exactly one line. Append the new class on a second line. Use `ApplicationContextRunner` for the test, following `AutoConfigWiringTest` in `com.example.demo`.

- [ ] **Step 1: Write the failing test**

```java
package com.example.demo;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.managedbots.InMemoryManagedBotStore;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotService;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotTokenStore;
import io.github.dev_abdulhay.telegramauth.managedbots.TelegramManagedBotsAutoConfiguration;
import io.github.dev_abdulhay.telegramauth.managedbots.TokenEncryptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class ManagedBotsAutoConfigTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Configuration
    static class HostBeans {
        @Bean TelegramBotModule module() {
            return TelegramBotModule.builder("123:ABC", "manager_bot").build();
        }
        @Bean ManagedBotTokenStore store() {
            return new InMemoryManagedBotStore();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TelegramManagedBotsAutoConfiguration.class))
            .withUserConfiguration(HostBeans.class);

    @Test
    void theFeatureIsOffByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(ManagedBotService.class));
    }

    @Test
    void enablingItWiresTheServiceAndClaimsTheUpdateSlot() {
        runner.withPropertyValues("telegram.managed-bots.enabled=true",
                        "telegram.managed-bots.encryption-key=" + KEY)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ManagedBotService.class);
                    assertThat(ctx).hasSingleBean(TokenEncryptor.class);
                    assertThat(ctx.getBean(TelegramBotModule.class).getManagedBotHandler()).isNotNull();
                });
    }

    @Test
    void enablingItWithoutAKeyFailsTheContext() {
        runner.withPropertyValues("telegram.managed-bots.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().hasMessageContaining("encryption-key"));
    }

    @Test
    void aHostSuppliedEncryptorReplacesTheDefaultAndNeedsNoKey() {
        runner.withPropertyValues("telegram.managed-bots.enabled=true")
                .withBean(TokenEncryptor.class, () -> new TokenEncryptor() {
                    @Override public String encrypt(String p) { return p; }
                    @Override public String decrypt(String c) { return c; }
                })
                .run(ctx -> assertThat(ctx).hasSingleBean(ManagedBotService.class));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ManagedBotsAutoConfigTest`
Expected: compilation failure — the auto-configuration and properties classes do not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Managed-bots settings. A separate namespace from {@code telegram.auth} on
 * purpose: the two features are independent and either can run without the other.
 */
@ConfigurationProperties(prefix = "telegram.managed-bots")
public class TelegramManagedBotsProperties {

    /** Opt-in switch for the whole feature. */
    private boolean enabled = false;

    /**
     * Base64-encoded 32-byte AES key for token encryption at rest. Required when
     * the feature is on, unless the host supplies its own {@link TokenEncryptor}.
     */
    private String encryptionKey;

    /** Attempts for {@code getManagedBotToken} before giving up on an update. */
    private int tokenFetchRetries = 3;

    /** First retry delay, doubling on each further attempt. */
    private Duration tokenFetchBackoff = Duration.ofSeconds(1);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEncryptionKey() { return encryptionKey; }
    public void setEncryptionKey(String encryptionKey) { this.encryptionKey = encryptionKey; }
    public int getTokenFetchRetries() { return tokenFetchRetries; }
    public void setTokenFetchRetries(int tokenFetchRetries) { this.tokenFetchRetries = tokenFetchRetries; }
    public Duration getTokenFetchBackoff() { return tokenFetchBackoff; }
    public void setTokenFetchBackoff(Duration tokenFetchBackoff) { this.tokenFetchBackoff = tokenFetchBackoff; }
}
```

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires the managed-bots feature when {@code telegram.managed-bots.enabled=true}.
 *
 * <p>The host supplies the {@link ManagedBotTokenStore} — only it knows whether
 * that is JPA (and with which entity) or in-memory.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "telegram.managed-bots", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TelegramManagedBotsProperties.class)
public class TelegramManagedBotsAutoConfiguration {

    /**
     * Fails the context when no key is configured rather than falling back to
     * storing tokens in the clear — a silent plaintext default is the kind of
     * thing that survives to production unnoticed.
     */
    @Bean
    @ConditionalOnMissingBean
    public TokenEncryptor managedBotTokenEncryptor(TelegramManagedBotsProperties properties) {
        String key = properties.getEncryptionKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "telegram.managed-bots.encryption-key is required when managed bots are enabled; "
                            + "set a Base64-encoded 32-byte key, or declare your own TokenEncryptor bean");
        }
        return new AesGcmTokenEncryptor(key);
    }

    @Bean
    @ConditionalOnMissingBean
    public ManagedBotEvents managedBotEvents() {
        return new ManagedBotEvents() { };
    }

    @Bean
    @ConditionalOnMissingBean
    public ManagedBotService managedBotService(TelegramBotModule module, ManagedBotTokenStore store,
                                               TokenEncryptor encryptor, ManagedBotEvents events,
                                               TelegramManagedBotsProperties properties) {
        return new ManagedBotService(module, store, encryptor, events,
                properties.getTokenFetchRetries(), properties.getTokenFetchBackoff());
    }

    @Bean
    @ConditionalOnMissingBean
    public ManagedBotUpdateHandler managedBotUpdateHandler(TelegramBotModule module, ManagedBotService service) {
        return new ManagedBotUpdateHandler(module, service);
    }
}
```

Append to `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
io.github.dev_abdulhay.telegramauth.managedbots.TelegramManagedBotsAutoConfiguration
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=ManagedBotsAutoConfigTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Run the full suite**

Run: `mvn test`
Expected: BUILD SUCCESS. The new auto-configuration must not affect hosts that leave the feature off.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/TelegramManagedBotsProperties.java \
        src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/TelegramManagedBotsAutoConfiguration.java \
        src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports \
        src/test/java/com/example/demo/ManagedBotsAutoConfigTest.java
git commit -m "feat(managed-bots): add opt-in auto-configuration"
```

---

### Task 9: End-to-end integration test

**Files:**
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotFlowIT.java`

**Interfaces:**
- Consumes: everything from Tasks 1–8.
- Produces: nothing — this task only proves the wiring against a mocked Bot API.

Note for the implementer: this is the create → token → event flow driven through a real `BotUpdateDispatcher` and a real `TelegramBot` pointed at WireMock, so it covers the pieces the unit tests stub out.

- [ ] **Step 1: Write the failing test**

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.dev_abdulhay.telegramauth.bot.BotUpdateDispatcher;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBot;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class ManagedBotFlowIT {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private WireMockServer server;
    private InMemoryManagedBotStore store;
    private List<String> events;
    private BotUpdateDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(0);
        server.start();

        TelegramBot bot = new TelegramBot(HttpClient.newHttpClient(), "123:ABC",
                "http://localhost:" + server.port());
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "manager_bot").bot(bot).build();
        store = new InMemoryManagedBotStore();
        events = new ArrayList<>();
        ManagedBotEvents listener = new ManagedBotEvents() {
            @Override public void onCreated(ManagedBot b) { events.add("created:" + b.botUserId()); }
            @Override public void onTokenRotated(ManagedBot b) { events.add("rotated:" + b.botUserId()); }
        };
        ManagedBotService service = new ManagedBotService(module, store,
                new AesGcmTokenEncryptor(KEY), listener, 3, Duration.ZERO);
        new ManagedBotUpdateHandler(module, service);
        dispatcher = new BotUpdateDispatcher(module);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private static String updatesJson() {
        return "{\"ok\":true,\"result\":[{\"update_id\":1,\"managed_bot\":{\"user\":{\"id\":7},"
                + "\"bot\":{\"id\":555,\"username\":\"tenant_bot\",\"first_name\":\"Tenant\"}}}]}";
    }

    @Test
    void aCreationUpdateEndsWithAnEncryptedTokenAndAnEvent() {
        server.stubFor(post(urlPathEqualTo("/bot123:ABC/getManagedBotToken"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"result\":\"555:CHILD-TOKEN\"}")));

        dispatcher.dispatch(updatesJson());

        assertThat(events).containsExactly("created:555");
        ManagedBot stored = store.findByBotUserId(555L).orElseThrow();
        assertThat(stored.encryptedToken()).doesNotContain("555:CHILD-TOKEN");
        assertThat(new AesGcmTokenEncryptor(KEY).decrypt(stored.encryptedToken()))
                .isEqualTo("555:CHILD-TOKEN");
    }

    @Test
    void aSecondUpdateRefetchesAndReportsARotation() {
        server.stubFor(post(urlPathEqualTo("/bot123:ABC/getManagedBotToken"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"result\":\"555:FIRST\"}")));
        dispatcher.dispatch(updatesJson());

        server.stubFor(post(urlPathEqualTo("/bot123:ABC/getManagedBotToken"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"result\":\"555:SECOND\"}")));
        dispatcher.dispatch(updatesJson());

        assertThat(events).containsExactly("created:555", "rotated:555");
        assertThat(store.findAll()).hasSize(1);
        assertThat(new AesGcmTokenEncryptor(KEY).decrypt(store.findByBotUserId(555L).orElseThrow().encryptedToken()))
                .isEqualTo("555:SECOND");
    }

    @Test
    void aPermanentApiFailureStoresNothing() {
        server.stubFor(post(urlPathEqualTo("/bot123:ABC/getManagedBotToken"))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":false,\"error_code\":400,\"description\":\"BOT_NOT_MANAGED\"}")));

        dispatcher.dispatch(updatesJson());

        assertThat(store.findAll()).isEmpty();
        assertThat(events).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ManagedBotFlowIT`
Expected: FAIL or compile error only if an earlier task is incomplete. If every earlier task is done this test should pass on the first run — that is expected for an integration test that composes finished units. If it fails, the failure is a real integration bug; fix the production code, not the test.

- [ ] **Step 3: Run the full suite**

Run: `mvn test`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotFlowIT.java
git commit -m "test(managed-bots): add the end-to-end create and rotate flow"
```

---

### Task 10: Documentation

**Files:**
- Modify: `README.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: the finished public API from Tasks 1–8.
- Produces: nothing in code.

Note for the implementer: the project's CLAUDE.md makes README updates mandatory for user-visible changes, and every snippet must be verified against the real classes — read them, do not guess. Place the new README section after the auth documentation, and add a `## [Unreleased]` block at the top of CHANGELOG.md (0.4.0 is the newest released entry).

- [ ] **Step 1: Write the README section**

Cover, in this order:
1. What the feature does, in two sentences, and that it is independent of the auth flow.
2. Prerequisites: *Bot Management Mode* enabled for the manager bot in the BotFather Mini App, and that the creating user needs `can_manage_bots`. State plainly that the library cannot enable either.
3. Config keys table: `telegram.managed-bots.enabled` (false), `.encryption-key` (required, Base64 32 bytes), `.token-fetch-retries` (3), `.token-fetch-backoff` (1s).
4. Minimal usage: declare a `ManagedBotTokenStore` bean (JPA entity subclassing `BaseManagedBot`, or `InMemoryManagedBotStore`), enable the flag, call `createLink(...)`, implement `ManagedBotEvents#onCreated` to react.
5. The delete caveat: Telegram exposes no deletion method; `decommission(...)` revokes the token and forgets the bot locally, while the bot keeps existing under the user's ownership and is removed through BotFather.
6. Security notes: tokens are stored encrypted, never logged, masked in `toString`; the host owns key custody; a user can revoke the token from BotFather at any time, so the application must tolerate a token going dead.

- [ ] **Step 2: Write the CHANGELOG entry**

Add `## [Unreleased]` with an `### Added` block naming: the `managedbots` package, `ManagedBotService`, `ManagedBotTokenStore` with both implementations, `TokenEncryptor`/`AesGcmTokenEncryptor`, `ManagedBotEvents`, `TelegramBotModule#onManagedBot`, the four `TelegramBot` API methods, `TelegramApiException`, `getUpdates(..., allowedUpdates)`, and the `telegram.managed-bots.*` properties. Note that `managed_bot` is not in Telegram's default `allowed_updates` list, which is why the runner sends the list explicitly once a managed-bot handler is registered.

- [ ] **Step 3: Verify every snippet against the code**

Run: `mvn test`
Then re-read each class named in the README and confirm the method names, parameter order and defaults match. Fix the docs, never the code, if they disagree.

- [ ] **Step 4: Commit**

```bash
git add README.md CHANGELOG.md
git commit -m "docs(managed-bots): document the managed-bots capability"
```

---

## Plan self-review

- **Spec coverage:** create link (T1), token custody and encryption (T2, T4), store contract with both implementations (T3, T4), the four API methods with 429 handling (T5), update routing and `allowed_updates` (T6), service + events + retries + decommission ordering (T7), opt-in config with the fail-fast key rule (T8), integration coverage of create/rotate/failure (T9), README and CHANGELOG (T10). No spec section is unimplemented.
- **Type consistency:** `ManagedBot`'s component order is fixed in T3 and reused verbatim in T4, T7 and T9. `ManagedBotService`'s constructor arity (6) matches between T7 and T8. `TokenEncryptor`'s two methods are identical in T2, T7, T8 and T9. `getManagedBotToken(long)` returns `String` everywhere it appears.
- **Deliberate omissions:** no `ManagedBotTokenStore` bean is auto-registered (the host's entity type is unknowable to the library), and runtime bot spawning stays out — it belongs to the white-label runtime spec.
