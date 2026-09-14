# Managed-bot intents + host-account linking — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship release 0.5.0 of `telegram-auth-spring-boot-starter`: managed-bot **intents** (correlate a created bot with the request that asked for it, by the creator's Telegram id) and **host-account linking** (carry an opaque `hostRef` from session creation into the host's approve handler).

**Architecture:** Intents are a second optional store next to the managed-bot token store. `ManagedBotService` gains the lifecycle (create → claim → complete) and a matching step that runs on bot creation and on first claim. `/start mb_<id>` is routed by a new prefix registry on `TelegramBotModule`, consulted by `BotUpdateDispatcher` *before* the command registry, with fall-through to the normal `/start` handler when the payload is not a known intent. Linking adds one nullable column to the auth session and one field to `AuthContext`; the library never interprets the value.

**Tech Stack:** Java 17, Spring Boot 3.3.5 (provided scope), JPA/Hibernate, Jackson, JUnit 5 + AssertJ, H2 (JPA tests), WireMock (HTTP tests). Maven: `./mvnw` is **not** present — use `mvn`.

**Spec:** `tasks/managed-bot/2026-09-14-managed-bot-intents-design.md` — read §1–§11 before starting. The plan argues from that spec; where they disagree, the spec wins and the plan is wrong (say so instead of guessing).

## Global Constraints

- **No new dependencies.** Not Testcontainers, not Mockito-inline, nothing. Tests use JUnit 5 + AssertJ + H2 + WireMock, already on the classpath.
- **No breaking change to any 0.4.0 public signature.** New behaviour arrives as overloads and `default` interface methods. The single unavoidable cost is the additive nullable `host_ref` column (Task 12).
- **Java 17** — records, enum switch with arrow syntax and `Stream.toList()` are fine; sealed-type switch patterns are not (preview in 17).
- **`OffsetDateTime` everywhere.** Never `Instant`; the whole library is `OffsetDateTime`.
- **No organisation/tenant/"admin" concept in the library.** `hostRef` is an opaque `String`, max 128 chars, in both the intent and the session.
- **Never log a token or ciphertext.** Mask like `ManagedBot.toString` does.
- **Handlers run on the bot's update worker thread** — keep them short; never do I/O on the polling thread.
- **Javadoc tone:** match the surrounding code — explain *why* a rule exists, not what the line does. Comments in English.
- **Commits:** Conventional Commits, one per task, English. **No AI attribution of any kind** — no `Co-Authored-By: Claude`, no "Generated with", no model references. This overrides any default git convention.
- **Package:** `io.github.dev_abdulhay.telegramauth.managedbots` unless a task says otherwise.
- **Run `mvn -q test` before every commit.** The 0.4.0 suite (233 tests) must stay green.

---

### Task 1: Intent model and the in-memory store

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotIntentStatus.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotIntent.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotIntentLink.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotIntentStore.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/InMemoryManagedBotIntentStore.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotIntentStoreContract.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/managedbots/InMemoryManagedBotIntentStoreTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `ManagedBotIntent` (record, 11 components, plus `START_PREFIX`, `claimedBy`, `completedWith`, `cancelled`, `expired`), `ManagedBotIntentStatus`, `ManagedBotIntentLink(String intentId, String url, OffsetDateTime expiresAt)`, `ManagedBotIntentStore` (`save`, `findById`, `findByBotUserId`, `findClaimedByOwner`, `deleteClosedBefore`), `InMemoryManagedBotIntentStore`, and the abstract test `ManagedBotIntentStoreContract` with `protected abstract ManagedBotIntentStore store();` plus `protected static ManagedBotIntent open(String id, long ...)` helpers.

- [ ] **Step 1: Write the failing contract test**

`ManagedBotIntentStoreContract.java` — mirror `ManagedBotStoreContract`'s shape exactly (public abstract class, protected factory helpers, one behaviour per `@Test`):

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract every {@link ManagedBotIntentStore} must satisfy. Subclass per implementation. */
public abstract class ManagedBotIntentStoreContract {

    protected abstract ManagedBotIntentStore store();

    protected static ManagedBotIntent open(String id, OffsetDateTime createdAt) {
        return new ManagedBotIntent(id, "tenant_shop_bot", "Shop", "bot:1", null,
                ManagedBotIntentStatus.OPEN, null, createdAt, null, null, createdAt.plusMinutes(30));
    }

    protected static ManagedBotIntent claimed(String id, long ownerUserId) {
        OffsetDateTime now = OffsetDateTime.now();
        return open(id, now).claimedBy(ownerUserId, now);
    }

    @Test
    void savesAndFindsById() {
        store().save(open("i1", OffsetDateTime.now()));
        assertThat(store().findById("i1")).get()
                .extracting(ManagedBotIntent::hostRef).isEqualTo("bot:1");
    }

    @Test
    void findByIdIsEmptyForAnUnknownIntent() {
        assertThat(store().findById("nope")).isEmpty();
    }

    @Test
    void savingTheSameIdAgainOverwritesInsteadOfDuplicating() {
        store().save(open("i1", OffsetDateTime.now()));
        store().save(claimed("i1", 7L));

        assertThat(store().findById("i1")).get()
                .extracting(ManagedBotIntent::status).isEqualTo(ManagedBotIntentStatus.CLAIMED);
        assertThat(store().findClaimedByOwner(7L)).hasSize(1);
    }

    @Test
    void findsOnlyClaimedIntentsOfOneOwner() {
        store().save(claimed("i1", 7L));
        store().save(claimed("i2", 7L));
        store().save(claimed("i3", 8L));
        store().save(open("i4", OffsetDateTime.now()));
        store().save(claimed("i5", 7L).completedWith(555L, OffsetDateTime.now()));

        assertThat(store().findClaimedByOwner(7L))
                .extracting(ManagedBotIntent::id).containsExactlyInAnyOrder("i1", "i2");
    }

    @Test
    void findsTheIntentABotIsLinkedTo() {
        store().save(claimed("i1", 7L).completedWith(555L, OffsetDateTime.now()));
        store().save(claimed("i2", 7L));

        assertThat(store().findByBotUserId(555L)).get()
                .extracting(ManagedBotIntent::id).isEqualTo("i1");
        assertThat(store().findByBotUserId(999L)).isEmpty();
    }

    @Test
    void purgeRemovesClosedAndStaleOpenRowsButKeepsClaimedAndCompleted() {
        OffsetDateTime old = OffsetDateTime.now().minusDays(30);
        store().save(open("stale-open", old));                                   // never read, still OPEN
        store().save(open("expired", old).expired());
        store().save(open("cancelled", old).cancelled());
        store().save(claimed("claimed", 7L));                                    // fresh, and CLAIMED never expires
        store().save(open("completed", old).claimedBy(7L, old).completedWith(555L, old));

        int removed = store().deleteClosedBefore(OffsetDateTime.now().minusDays(7));

        assertThat(removed).isEqualTo(3);
        assertThat(store().findById("stale-open")).isEmpty();
        assertThat(store().findById("expired")).isEmpty();
        assertThat(store().findById("cancelled")).isEmpty();
        assertThat(store().findById("claimed")).isPresent();
        assertThat(store().findById("completed")).isPresent();
    }

    @Test
    void purgeKeepsRowsNewerThanTheCutoff() {
        store().save(open("fresh", OffsetDateTime.now()).cancelled());

        assertThat(store().deleteClosedBefore(OffsetDateTime.now().minusDays(7))).isZero();
        assertThat(store().findById("fresh")).isPresent();
    }
}
```

And the subclass:

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

class InMemoryManagedBotIntentStoreTest extends ManagedBotIntentStoreContract {

    private final InMemoryManagedBotIntentStore store = new InMemoryManagedBotIntentStore();

    @Override
    protected ManagedBotIntentStore store() {
        return store;
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -Dtest=InMemoryManagedBotIntentStoreTest test`
Expected: compilation failure — `ManagedBotIntent`, `ManagedBotIntentStatus`, `ManagedBotIntentStore`, `InMemoryManagedBotIntentStore` do not exist.

- [ ] **Step 3: Write the model**

```java
public enum ManagedBotIntentStatus { OPEN, CLAIMED, COMPLETED, EXPIRED, CANCELLED }
```

```java
/**
 * One request to create one managed bot. The row is what turns an anonymous
 * {@code managed_bot} update into "this is the bot org 42 asked for": the id
 * travels in the {@code /start} payload, and the first user to open that link
 * becomes the intent's proven owner.
 *
 * @param suggestedUsername a suggestion only — Telegram lets the user edit it in
 *                          the confirmation dialog, which is the whole reason
 *                          this type exists; may be {@code null}
 * @param hostRef           opaque host correlation key, never interpreted here
 * @param botUserId         set when the intent completes; unique among non-null values
 */
public record ManagedBotIntent(String id, String suggestedUsername, String suggestedName,
                               String hostRef, Long ownerUserId, ManagedBotIntentStatus status,
                               Long botUserId, OffsetDateTime createdAt, OffsetDateTime claimedAt,
                               OffsetDateTime completedAt, OffsetDateTime expiresAt) {

    /** Prefix of the {@code /start} payload that carries an intent id. Not configurable. */
    public static final String START_PREFIX = "mb_";

    public ManagedBotIntent claimedBy(long ownerUserId, OffsetDateTime at) {
        return new ManagedBotIntent(id, suggestedUsername, suggestedName, hostRef, ownerUserId,
                ManagedBotIntentStatus.CLAIMED, botUserId, createdAt, at, completedAt, expiresAt);
    }

    public ManagedBotIntent completedWith(long botUserId, OffsetDateTime at) {
        return new ManagedBotIntent(id, suggestedUsername, suggestedName, hostRef, ownerUserId,
                ManagedBotIntentStatus.COMPLETED, botUserId, createdAt, claimedAt, at, expiresAt);
    }

    public ManagedBotIntent cancelled() {
        return withStatus(ManagedBotIntentStatus.CANCELLED);
    }

    public ManagedBotIntent expired() {
        return withStatus(ManagedBotIntentStatus.EXPIRED);
    }

    private ManagedBotIntent withStatus(ManagedBotIntentStatus next) {
        return new ManagedBotIntent(id, suggestedUsername, suggestedName, hostRef, ownerUserId,
                next, botUserId, createdAt, claimedAt, completedAt, expiresAt);
    }
}
```

```java
/**
 * What the host hands to the user: the deep link that claims {@code intentId}.
 * The link goes to the <em>manager</em> bot, not to {@code /newbot} — the bot
 * creation button comes later, from the manager bot's own reply.
 */
public record ManagedBotIntentLink(String intentId, String url, OffsetDateTime expiresAt) { }
```

- [ ] **Step 4: Write the store interface and the in-memory implementation**

```java
/**
 * Persistence for managed-bot intents. {@code save} is an upsert keyed on
 * {@link ManagedBotIntent#id()}.
 *
 * <p>Hosts create the table themselves, exactly as they do for managed bots.
 */
public interface ManagedBotIntentStore {

    void save(ManagedBotIntent intent);

    Optional<ManagedBotIntent> findById(String id);

    /** The intent a bot is linked to, if any. Drives "is this bot still unassigned?". */
    Optional<ManagedBotIntent> findByBotUserId(long botUserId);

    /** Only {@code CLAIMED} rows: an intent is a match candidate solely while it waits for its bot. */
    List<ManagedBotIntent> findClaimedByOwner(long ownerUserId);

    /**
     * Deletes intents that can no longer be claimed or completed: {@code CANCELLED},
     * {@code EXPIRED}, <b>and {@code OPEN} rows whose {@code expiresAt} already passed</b>
     * — expiry is evaluated lazily on read, so an intent nobody ever read is still
     * {@code OPEN} in the database and a status-only predicate would never reap it.
     * {@code CLAIMED} and {@code COMPLETED} are never deleted.
     *
     * @return how many rows were removed
     */
    int deleteClosedBefore(OffsetDateTime cutoff);
}
```

```java
/** Map-backed store for tests and hosts that do not use JPA. Not durable. */
public class InMemoryManagedBotIntentStore implements ManagedBotIntentStore {

    private final ConcurrentHashMap<String, ManagedBotIntent> byId = new ConcurrentHashMap<>();

    @Override
    public void save(ManagedBotIntent intent) {
        byId.put(intent.id(), intent);
    }

    @Override
    public Optional<ManagedBotIntent> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<ManagedBotIntent> findByBotUserId(long botUserId) {
        return byId.values().stream()
                .filter(i -> i.botUserId() != null && i.botUserId() == botUserId)
                .findFirst();
    }

    @Override
    public List<ManagedBotIntent> findClaimedByOwner(long ownerUserId) {
        return byId.values().stream()
                .filter(i -> i.status() == ManagedBotIntentStatus.CLAIMED)
                .filter(i -> i.ownerUserId() != null && i.ownerUserId() == ownerUserId)
                .toList();
    }

    @Override
    public int deleteClosedBefore(OffsetDateTime cutoff) {
        List<String> doomed = byId.values().stream()
                .filter(i -> i.status() != ManagedBotIntentStatus.CLAIMED
                        && i.status() != ManagedBotIntentStatus.COMPLETED)
                .filter(i -> i.expiresAt().isBefore(cutoff))
                .map(ManagedBotIntent::id)
                .toList();
        doomed.forEach(byId::remove);
        return doomed.size();
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -q -Dtest=InMemoryManagedBotIntentStoreTest test`
Expected: PASS, 7 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/dev_abdulhay/telegramauth/managedbots src/test/java/io/github/dev_abdulhay/telegramauth/managedbots
git commit -m "feat(managed-bots): add the managed-bot intent model and in-memory store"
```

---

### Task 2: JPA intent store

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/BaseManagedBotIntent.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/BaseManagedBotIntentRepository.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/JpaManagedBotIntentStore.java`
- Create: `src/test/java/com/example/demo/DemoManagedBotIntent.java`
- Create: `src/test/java/com/example/demo/DemoManagedBotIntentRepository.java`
- Test: `src/test/java/com/example/demo/JpaManagedBotIntentStoreTest.java`

**Interfaces:**
- Consumes: Task 1's `ManagedBotIntent`, `ManagedBotIntentStatus`, `ManagedBotIntentStore`, `ManagedBotIntentStoreContract`.
- Produces: `BaseManagedBotIntent` (`@MappedSuperclass`, assigned `String` id), `BaseManagedBotIntentRepository<I extends BaseManagedBotIntent>` with `findByBotUserId`, `findByOwnerUserIdAndStatus`, `findByStatusNotInAndExpiresAtBefore`, and `JpaManagedBotIntentStore<I>(repo, Supplier<I> factory)`.

- [ ] **Step 1: Write the failing test**

```java
package com.example.demo;

import io.github.dev_abdulhay.telegramauth.managedbots.JpaManagedBotIntentStore;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotIntent;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotIntentStore;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotIntentStoreContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same wiring as {@link JpaManagedBotStoreTest} and for the same reason: a bare
 * {@code @DataJpaTest} cannot see this library's own auto-configuration while the
 * {@code com.example.demo} package is component-scanned.
 */
@SpringBootTest(classes = DemoApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "telegram.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:managedbotintents;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
class JpaManagedBotIntentStoreTest extends ManagedBotIntentStoreContract {

    @Autowired
    private DemoManagedBotIntentRepository repo;

    private ManagedBotIntentStore store;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        store = new JpaManagedBotIntentStore<>(repo, DemoManagedBotIntent::new);
    }

    @Override
    protected ManagedBotIntentStore store() {
        return store;
    }

    @Test
    void savingTheSameIntentAgainUpdatesTheExistingRow() {
        OffsetDateTime now = OffsetDateTime.now();
        store.save(open("i1", now));
        store.save(open("i1", now).claimedBy(7L, now));

        assertThat(repo.count()).isEqualTo(1);
        assertThat(store.findById("i1")).get()
                .extracting(ManagedBotIntent::ownerUserId).isEqualTo(7L);
    }

    @Test
    void twoIntentsCannotClaimTheSameBot() {
        OffsetDateTime now = OffsetDateTime.now();
        store.save(open("i1", now).claimedBy(7L, now).completedWith(555L, now));

        assertThatThrownBy(() -> store.save(open("i2", now).claimedBy(7L, now).completedWith(555L, now)))
                .isInstanceOf(RuntimeException.class);
    }
}
```

Add the static import `org.assertj.core.api.Assertions.assertThatThrownBy`.

And the host-side fixtures:

```java
package com.example.demo;

import io.github.dev_abdulhay.telegramauth.managedbots.BaseManagedBotIntent;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "demo_managed_bot_intent",
       indexes = {
           @Index(name = "ix_demo_intent_owner_status", columnList = "owner_user_id, status"),
           @Index(name = "ix_demo_intent_status_expires", columnList = "status, expires_at")
       })
public class DemoManagedBotIntent extends BaseManagedBotIntent {
}
```

```java
package com.example.demo;

import io.github.dev_abdulhay.telegramauth.managedbots.BaseManagedBotIntentRepository;

public interface DemoManagedBotIntentRepository extends BaseManagedBotIntentRepository<DemoManagedBotIntent> {
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -Dtest=JpaManagedBotIntentStoreTest test`
Expected: compilation failure — `BaseManagedBotIntent` and friends do not exist.

- [ ] **Step 3: Write the mapped superclass**

Mirror `BaseManagedBot`, but the id is **assigned, not generated** — it is the value that travels in the `/start` payload:

```java
/**
 * A stored managed-bot intent. {@code @MappedSuperclass} — host apps subclass
 * with {@code @Entity @Table(name = "...")}, exactly like {@link BaseManagedBot}.
 *
 * <p>The primary key is the intent id itself: it is generated by
 * {@code ManagedBotService}, travels in a Telegram {@code /start} payload and is
 * looked up by that value, so a surrogate key would buy nothing.
 *
 * <p>Index {@code (owner_user_id, status)} for matching and {@code (status, expires_at)}
 * for the retention purge on the concrete table; {@code bot_user_id} needs a unique
 * index over its non-null values, which is what stops two intents from claiming one bot.
 */
@MappedSuperclass
public abstract class BaseManagedBotIntent {

    @Id
    @Column(name = "id", length = 32, nullable = false)
    private String id;

    @Column(name = "suggested_username", length = 50)
    private String suggestedUsername;

    @Column(name = "suggested_name", length = 100)
    private String suggestedName;

    @Column(name = "host_ref", length = 128)
    private String hostRef;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private ManagedBotIntentStatus status;

    @Column(name = "bot_user_id", unique = true)
    private Long botUserId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "claimed_at")
    private OffsetDateTime claimedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    // getters and setters for every field, in declaration order, one line each
}
```

- [ ] **Step 4: Write the repository**

```java
/**
 * Base repository for any {@link BaseManagedBotIntent} subtype. Host repositories
 * extend this with their concrete entity: {@code interface TenantIntentRepository
 * extends BaseManagedBotIntentRepository<TenantIntent> {}}.
 */
@NoRepositoryBean
public interface BaseManagedBotIntentRepository<I extends BaseManagedBotIntent>
        extends JpaRepository<I, String> {

    Optional<I> findByBotUserId(Long botUserId);

    List<I> findByOwnerUserIdAndStatus(Long ownerUserId, ManagedBotIntentStatus status);

    List<I> findByStatusNotInAndExpiresAtBefore(Collection<ManagedBotIntentStatus> statuses,
                                                OffsetDateTime cutoff);
}
```

- [ ] **Step 5: Write the JPA store**

`save` must use find-then-update, like `JpaManagedBotTokenStore`: the id is assigned, so Hibernate would otherwise treat every `save` as a merge of a detached row and silently reset `createdAt` handling. `deleteClosedBefore` returns the number of rows it removed.

```java
public class JpaManagedBotIntentStore<I extends BaseManagedBotIntent> implements ManagedBotIntentStore {

    private static final Collection<ManagedBotIntentStatus> KEEP =
            List.of(ManagedBotIntentStatus.CLAIMED, ManagedBotIntentStatus.COMPLETED);

    private final BaseManagedBotIntentRepository<I> repo;
    private final Supplier<I> factory;

    /**
     * @param factory makes a <b>blank, unsaved</b> entity — typically a constructor
     *                reference such as {@code TenantIntent::new}
     */
    public JpaManagedBotIntentStore(BaseManagedBotIntentRepository<I> repo, Supplier<I> factory) {
        this.repo = repo;
        this.factory = factory;
    }

    @Override
    @Transactional
    public void save(ManagedBotIntent intent) {
        I entity = repo.findById(intent.id()).orElseGet(factory);
        entity.setId(intent.id());
        entity.setSuggestedUsername(intent.suggestedUsername());
        entity.setSuggestedName(intent.suggestedName());
        entity.setHostRef(intent.hostRef());
        entity.setOwnerUserId(intent.ownerUserId());
        entity.setStatus(intent.status());
        entity.setBotUserId(intent.botUserId());
        entity.setCreatedAt(intent.createdAt());
        entity.setClaimedAt(intent.claimedAt());
        entity.setCompletedAt(intent.completedAt());
        entity.setExpiresAt(intent.expiresAt());
        repo.saveAndFlush(entity);   // flush here so a duplicate bot_user_id fails now, not at commit
    }

    @Override
    public Optional<ManagedBotIntent> findById(String id) {
        return repo.findById(id).map(JpaManagedBotIntentStore::toRecord);
    }

    @Override
    public Optional<ManagedBotIntent> findByBotUserId(long botUserId) {
        return repo.findByBotUserId(botUserId).map(JpaManagedBotIntentStore::toRecord);
    }

    @Override
    public List<ManagedBotIntent> findClaimedByOwner(long ownerUserId) {
        List<ManagedBotIntent> out = new ArrayList<>();
        repo.findByOwnerUserIdAndStatus(ownerUserId, ManagedBotIntentStatus.CLAIMED)
                .forEach(e -> out.add(toRecord(e)));
        return out;
    }

    @Override
    @Transactional
    public int deleteClosedBefore(OffsetDateTime cutoff) {
        List<I> doomed = repo.findByStatusNotInAndExpiresAtBefore(KEEP, cutoff);
        repo.deleteAll(doomed);
        return doomed.size();
    }

    private static ManagedBotIntent toRecord(BaseManagedBotIntent e) {
        return new ManagedBotIntent(e.getId(), e.getSuggestedUsername(), e.getSuggestedName(),
                e.getHostRef(), e.getOwnerUserId(), e.getStatus(), e.getBotUserId(),
                e.getCreatedAt(), e.getClaimedAt(), e.getCompletedAt(), e.getExpiresAt());
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -q -Dtest=JpaManagedBotIntentStoreTest test`
Expected: PASS (7 inherited contract tests + 2 local).
If the unique-constraint test fails because Hibernate defers the insert, confirm `saveAndFlush` is used; do not relax the test.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/dev_abdulhay/telegramauth/managedbots src/test/java/com/example/demo
git commit -m "feat(managed-bots): add the JPA managed-bot intent store"
```

---

### Task 3: Intent lifecycle on `ManagedBotService` — create, find, cancel

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotIntentException.java`
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotLink.java` (make `validateUsername` public static)
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotService.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotIntentServiceTest.java` (new file; leave the 391-line `ManagedBotServiceTest` alone)

**Interfaces:**
- Consumes: Task 1's types and store.
- Produces: `ManagedBotIntentException` (+ nested `Reason` enum with `INTENT_NOT_FOUND`, `INTENT_NOT_CLAIMED`, `INTENT_CLOSED`, `BOT_NOT_FOUND`, `OWNER_MISMATCH`, `BOT_ALREADY_ASSIGNED`, and `reason()`), the 9-argument `ManagedBotService` constructor, `createIntent(String, String, String)`, `findIntent(String)`, `cancelIntent(String)`, and the protected-to-the-class helpers `requireIntentStore()`, `expireIfDue(...)`, `purgeClosedIntents()`.

- [ ] **Step 1: Write the failing test**

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBot;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedBotIntentServiceTest {

    /** Counts purges so the once-a-minute throttle is observable. */
    static class CountingIntentStore extends InMemoryManagedBotIntentStore {
        int purges;
        @Override public int deleteClosedBefore(OffsetDateTime cutoff) {
            purges++;
            return super.deleteClosedBefore(cutoff);
        }
    }

    static class RecordingEvents implements ManagedBotEvents {
        final List<String> events = new ArrayList<>();
        @Override public void onIntentClaimed(ManagedBotIntent intent) { events.add("claimed:" + intent.id()); }
        @Override public void onIntentMatched(ManagedBot bot, ManagedBotIntent intent) {
            events.add("matched:" + bot.botUserId() + ":" + intent.id());
        }
        @Override public void onIntentUnmatched(ManagedBot bot, List<ManagedBotIntent> candidates) {
            events.add("unmatched:" + bot.botUserId() + ":" + candidates.size());
        }
        @Override public void onIntentAmbiguous(ManagedBotIntent intent, List<ManagedBot> candidates) {
            events.add("ambiguous:" + intent.id() + ":" + candidates.size());
        }
    }

    record Env(InMemoryManagedBotStore bots, CountingIntentStore intents,
               RecordingEvents events, ManagedBotService service) { }

    static Env env() {
        TelegramBot fake = new TelegramBot(HttpClient.newHttpClient(), "123:ABC") {
            @Override public String getManagedBotToken(long botUserId) { return "999:CHILD"; }
            @Override public void sendMessage(long chatId, String text, String replyMarkupJson) { }
        };
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "manager_bot").bot(fake).build();
        InMemoryManagedBotStore bots = new InMemoryManagedBotStore();
        CountingIntentStore intents = new CountingIntentStore();
        RecordingEvents events = new RecordingEvents();
        TokenEncryptor enc = new TokenEncryptor() {
            @Override public String encrypt(String p) { return "ENC(" + p + ")"; }
            @Override public String decrypt(String c) { return c.substring(4, c.length() - 1); }
        };
        ManagedBotService service = new ManagedBotService(module, bots, enc, events, 1, Duration.ZERO,
                intents, Duration.ofMinutes(30), Duration.ofDays(7));
        return new Env(bots, intents, events, service);
    }

    @Test
    void createIntentReturnsAManagerBotLinkCarryingTheIntentId() {
        ManagedBotIntentLink link = env().service().createIntent("tenant_shop_bot", "Shop", "bot:1");

        assertThat(link.intentId()).hasSize(22).matches("[A-Za-z0-9_-]+");
        assertThat(link.url()).isEqualTo("https://t.me/manager_bot?start=mb_" + link.intentId());
        assertThat(link.expiresAt()).isAfter(OffsetDateTime.now().plusMinutes(29));
    }

    @Test
    void createIntentStoresAnOpenIntentWithTheHostRef() {
        Env e = env();
        ManagedBotIntentLink link = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1");

        assertThat(e.service().findIntent(link.intentId())).get()
                .satisfies(i -> {
                    assertThat(i.status()).isEqualTo(ManagedBotIntentStatus.OPEN);
                    assertThat(i.hostRef()).isEqualTo("bot:1");
                    assertThat(i.suggestedUsername()).isEqualTo("tenant_shop_bot");
                    assertThat(i.ownerUserId()).isNull();
                });
    }

    @Test
    void createIntentRejectsAUsernameTelegramCouldNeverAccept() {
        assertThatThrownBy(() -> env().service().createIntent("nope", "Shop", "bot:1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBlankSuggestedUsernameIsAllowed() {
        Env e = env();
        ManagedBotIntentLink link = e.service().createIntent(null, null, null);

        assertThat(e.service().findIntent(link.intentId())).get()
                .extracting(ManagedBotIntent::suggestedUsername).isNull();
    }

    @Test
    void intentsAreOffWithoutAStore() {
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "manager_bot").build();
        ManagedBotService noIntents = new ManagedBotService(module, new InMemoryManagedBotStore(),
                new TokenEncryptor() {
                    @Override public String encrypt(String p) { return p; }
                    @Override public String decrypt(String c) { return c; }
                }, new ManagedBotEvents() { }, 1, Duration.ZERO);

        assertThatThrownBy(() -> noIntents.createIntent("tenant_shop_bot", "Shop", "bot:1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ManagedBotIntentStore");
    }

    @Test
    void anOpenIntentExpiresOnReadAndIsPersistedExpired() {
        Env e = env();
        ManagedBotIntentLink link = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1");
        ManagedBotIntent stored = e.intents().findById(link.intentId()).orElseThrow();
        e.intents().save(new ManagedBotIntent(stored.id(), stored.suggestedUsername(), stored.suggestedName(),
                stored.hostRef(), null, ManagedBotIntentStatus.OPEN, null,
                stored.createdAt().minusHours(2), null, null, stored.expiresAt().minusHours(2)));

        assertThat(e.service().findIntent(link.intentId())).get()
                .extracting(ManagedBotIntent::status).isEqualTo(ManagedBotIntentStatus.EXPIRED);
        assertThat(e.intents().findById(link.intentId())).get()
                .extracting(ManagedBotIntent::status).isEqualTo(ManagedBotIntentStatus.EXPIRED);
    }

    @Test
    void cancelWorksFromOpenAndFromClaimedButNotAfterThat() {
        Env e = env();
        String open = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();
        e.service().cancelIntent(open);
        assertThat(e.service().findIntent(open)).get()
                .extracting(ManagedBotIntent::status).isEqualTo(ManagedBotIntentStatus.CANCELLED);

        assertThatThrownBy(() -> e.service().cancelIntent(open))
                .isInstanceOf(ManagedBotIntentException.class)
                .extracting(t -> ((ManagedBotIntentException) t).reason())
                .isEqualTo(ManagedBotIntentException.Reason.INTENT_CLOSED);

        assertThatThrownBy(() -> e.service().cancelIntent("nope"))
                .isInstanceOf(ManagedBotIntentException.class)
                .extracting(t -> ((ManagedBotIntentException) t).reason())
                .isEqualTo(ManagedBotIntentException.Reason.INTENT_NOT_FOUND);
    }

    @Test
    void theRetentionPurgeRunsAtMostOnceAMinute() {
        Env e = env();
        e.service().createIntent("tenant_shop_bot", "Shop", "bot:1");
        e.service().createIntent("tenant_shop_bot", "Shop", "bot:2");
        e.service().createIntent("tenant_shop_bot", "Shop", "bot:3");

        assertThat(e.intents().purges).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -Dtest=ManagedBotIntentServiceTest test`
Expected: compilation failure — the 9-arg constructor, `createIntent`, `findIntent`, `cancelIntent` and `ManagedBotIntentException` do not exist.

- [ ] **Step 3: Write `ManagedBotIntentException`**

```java
/**
 * A managed-bot intent operation the host can reasonably branch on: the intent is
 * gone, the bot belongs to someone else, another assignment won the race.
 *
 * <p>Deliberately <em>not</em> used for misconfiguration — a missing
 * {@link ManagedBotIntentStore} bean is an {@link IllegalStateException}, the same
 * way a missing encryption key is, because no runtime branch can recover from it.
 */
public class ManagedBotIntentException extends RuntimeException {

    public enum Reason {
        INTENT_NOT_FOUND, INTENT_NOT_CLAIMED, INTENT_CLOSED,
        BOT_NOT_FOUND, OWNER_MISMATCH, BOT_ALREADY_ASSIGNED
    }

    private final Reason reason;

    public ManagedBotIntentException(Reason reason, String message) {
        this(reason, message, null);
    }

    public ManagedBotIntentException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
```

- [ ] **Step 4: Expose `ManagedBotLink`'s username validation**

Change `private static String validateUsername(String username)` to `public static String validateUsername(String username)` and give it javadoc:

```java
/**
 * Rejects what Telegram could never accept: 5-32 characters of {@code [A-Za-z0-9_]}
 * ending in {@code bot}. Whether the name is still <em>free</em> is unknowable from
 * the Bot API. Public so intent creation can fail fast on the host's thread instead
 * of on a bot update worker.
 *
 * @return the username unchanged, for chaining
 */
```

- [ ] **Step 5: Add the intent lifecycle to `ManagedBotService`**

Add fields and the constructor overload; the existing 6-arg constructor delegates so no 0.4.0 caller breaks:

```java
    /** Base64URL of 16 random bytes: 22 chars, well inside Telegram's 64-char start payload. */
    private static final int INTENT_ID_BYTES = 16;
    private static final int MAX_HOST_REF = 128;
    /** The purge is a database round trip; once a minute is plenty for a cleanup nobody waits on. */
    private static final Duration PURGE_INTERVAL = Duration.ofMinutes(1);
    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder ID_ENC = Base64.getUrlEncoder().withoutPadding();

    private final ManagedBotIntentStore intentStore;
    private final Duration intentTtl;
    private final Duration intentRetention;
    /** JVM-local, like the echo map: several instances purging once a minute each is harmless. */
    private volatile OffsetDateTime lastPurge;

    public ManagedBotService(TelegramBotModule module, ManagedBotTokenStore store,
                             TokenEncryptor encryptor, ManagedBotEvents events,
                             int tokenFetchRetries, Duration tokenFetchBackoff) {
        this(module, store, encryptor, events, tokenFetchRetries, tokenFetchBackoff,
                null, Duration.ofMinutes(30), Duration.ofDays(7));
    }

    /**
     * @param intentStore {@code null} turns intents off completely: no matching runs
     *                    and {@link #createIntent} refuses
     */
    public ManagedBotService(TelegramBotModule module, ManagedBotTokenStore store,
                             TokenEncryptor encryptor, ManagedBotEvents events,
                             int tokenFetchRetries, Duration tokenFetchBackoff,
                             ManagedBotIntentStore intentStore, Duration intentTtl,
                             Duration intentRetention) {
        // ... existing assignments ...
        this.intentStore = intentStore;
        this.intentTtl = intentTtl == null ? Duration.ofMinutes(30) : intentTtl;
        this.intentRetention = intentRetention == null ? Duration.ofDays(7) : intentRetention;
    }
```

And the methods:

```java
    /**
     * A request to create one bot, and the link that claims it. The user opens the
     * link, the manager bot learns who they are, and the bot they create afterwards
     * is matched back to this intent by that identity — which is the one thing an
     * edited username cannot break.
     *
     * @param suggestedUsername may be {@code null}; validated eagerly when present
     * @param hostRef           opaque, at most 128 characters, never interpreted here
     * @throws IllegalStateException    when no {@link ManagedBotIntentStore} is configured
     * @throws IllegalArgumentException for a username Telegram could not accept, or an oversized {@code hostRef}
     */
    public ManagedBotIntentLink createIntent(String suggestedUsername, String suggestedName, String hostRef) {
        ManagedBotIntentStore intents = requireIntentStore();
        String username = trimToNull(suggestedUsername);
        if (username != null) {
            ManagedBotLink.validateUsername(username);
        }
        if (hostRef != null && hostRef.length() > MAX_HOST_REF) {
            throw new IllegalArgumentException("hostRef must be at most " + MAX_HOST_REF
                    + " characters but was " + hostRef.length());
        }
        purgeClosedIntents();
        OffsetDateTime now = OffsetDateTime.now();
        ManagedBotIntent intent = new ManagedBotIntent(newIntentId(), username,
                trimToNull(suggestedName), hostRef, null, ManagedBotIntentStatus.OPEN, null,
                now, null, null, now.plus(intentTtl));
        intents.save(intent);
        return new ManagedBotIntentLink(intent.id(),
                "https://t.me/" + module.getUsername().trim() + "?start="
                        + ManagedBotIntent.START_PREFIX + intent.id(),
                intent.expiresAt());
    }

    /** The intent, with an overdue {@code OPEN} row reported — and stored — as {@code EXPIRED}. */
    public Optional<ManagedBotIntent> findIntent(String intentId) {
        return requireIntentStore().findById(intentId).map(this::expireIfDue);
    }

    /**
     * Ends an intent the host no longer wants. {@code CLAIMED} intents never expire
     * on their own, so this is the only way one leaves the matching pool.
     *
     * @throws ManagedBotIntentException {@code INTENT_NOT_FOUND}, or {@code INTENT_CLOSED}
     *         when it is already completed, cancelled or expired
     */
    public void cancelIntent(String intentId) {
        ManagedBotIntent intent = findIntent(intentId).orElseThrow(() -> new ManagedBotIntentException(
                ManagedBotIntentException.Reason.INTENT_NOT_FOUND, "unknown intent " + intentId));
        if (intent.status() != ManagedBotIntentStatus.OPEN
                && intent.status() != ManagedBotIntentStatus.CLAIMED) {
            throw new ManagedBotIntentException(ManagedBotIntentException.Reason.INTENT_CLOSED,
                    "intent " + intentId + " is " + intent.status());
        }
        intentStore.save(intent.cancelled());
    }

    private ManagedBotIntentStore requireIntentStore() {
        if (intentStore == null) {
            throw new IllegalStateException(
                    "managed-bot intents need a ManagedBotIntentStore bean");
        }
        return intentStore;
    }

    /**
     * Expiry is evaluated on read rather than by a scheduler: an intent nobody looks
     * at costs nothing, and the retention purge reaps the rows this never touches.
     */
    private ManagedBotIntent expireIfDue(ManagedBotIntent intent) {
        if (intent.status() == ManagedBotIntentStatus.OPEN
                && intent.expiresAt().isBefore(OffsetDateTime.now())) {
            ManagedBotIntent expired = intent.expired();
            intentStore.save(expired);
            return expired;
        }
        return intent;
    }

    private void purgeClosedIntents() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime last = lastPurge;
        if (last != null && last.isAfter(now.minus(PURGE_INTERVAL))) return;
        lastPurge = now;
        int removed = intentStore.deleteClosedBefore(now.minus(intentRetention));
        if (removed > 0) log.debug("purged {} closed managed-bot intents", removed);
    }

    private static String newIntentId() {
        byte[] buf = new byte[INTENT_ID_BYTES];
        RNG.nextBytes(buf);
        return ID_ENC.encodeToString(buf);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `mvn -q -Dtest='ManagedBotIntentServiceTest,ManagedBotServiceTest,ManagedBotLinkTest' test`
Expected: PASS. `ManagedBotServiceTest` must be untouched and still green.

- [ ] **Step 7: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat(managed-bots): create, read and cancel managed-bot intents"
```

---

### Task 4: Intent events and matching on bot creation

**Files:**
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotEvents.java`
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotService.java` (`storeAndAnnounce` and new private helpers)
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotIntentServiceTest.java` (extend)

**Interfaces:**
- Consumes: Task 3's service, `ManagedBotIntentServiceTest.Env`.
- Produces: `ManagedBotEvents.onIntentClaimed(ManagedBotIntent)`, `onIntentMatched(ManagedBot, ManagedBotIntent)`, `onIntentUnmatched(ManagedBot, List<ManagedBotIntent>)`, `onIntentAmbiguous(ManagedBotIntent, List<ManagedBot>)` — all `default` no-ops; private `matchOnCreation(ManagedBot)` returning a `Runnable` event to publish (or `null`), `publish(String, Runnable)`, `candidatesFor(ManagedBot)`, `pickIntent(List, String)`.

- [ ] **Step 1: Write the failing tests**

Append to `ManagedBotIntentServiceTest`. Helper first (put it next to `env()`):

```java
    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode managedBotUpdate(long botId, long ownerId, String username) throws Exception {
        return M.readTree("{\"managed_bot\":{\"user\":{\"id\":" + ownerId + "},"
                + "\"bot\":{\"id\":" + botId + ",\"username\":\"" + username + "\",\"first_name\":\"T\"}}}");
    }

    /** Claims an intent the way the flow does, so matching tests start from a CLAIMED row. */
    private static String claimedIntent(Env e, long ownerUserId, String suggestedUsername) {
        String id = e.service().createIntent(suggestedUsername, "Shop", "bot:1").intentId();
        ManagedBotIntent stored = e.intents().findById(id).orElseThrow();
        e.intents().save(stored.claimedBy(ownerUserId, OffsetDateTime.now()));
        return id;
    }
```

Tests:

```java
    @Test
    void aCreatedBotCompletesTheOnlyClaimedIntentOfItsOwner() throws Exception {
        Env e = env();
        String id = claimedIntent(e, 7L, "tenant_shop_bot");

        e.service().handleUpdate(managedBotUpdate(555L, 7L, "edited_name_bot"));

        assertThat(e.intents().findById(id)).get().satisfies(i -> {
            assertThat(i.status()).isEqualTo(ManagedBotIntentStatus.COMPLETED);
            assertThat(i.botUserId()).isEqualTo(555L);
            assertThat(i.completedAt()).isNotNull();
        });
        assertThat(e.events().events).containsExactly("matched:555:" + id);
    }

    @Test
    void theUsernameBreaksATieBetweenTwoClaimedIntents() throws Exception {
        Env e = env();
        String wanted = claimedIntent(e, 7L, "tenant_shop_bot");
        claimedIntent(e, 7L, "tenant_cafe_bot");

        e.service().handleUpdate(managedBotUpdate(555L, 7L, "TENANT_SHOP_BOT"));

        assertThat(e.events().events).containsExactly("matched:555:" + wanted);
    }

    @Test
    void twoIndistinguishableIntentsAreHandedToTheHostInsteadOfGuessed() throws Exception {
        Env e = env();
        claimedIntent(e, 7L, "tenant_shop_bot");
        claimedIntent(e, 7L, "tenant_cafe_bot");

        e.service().handleUpdate(managedBotUpdate(555L, 7L, "something_else_bot"));

        assertThat(e.events().events).containsExactly("unmatched:555:2");
        assertThat(e.intents().findByBotUserId(555L)).isEmpty();
    }

    @Test
    void aBotOlderThanTheIntentIsNeverAnAutoMatchCandidate() throws Exception {
        Env e = env();
        OffsetDateTime longAgo = OffsetDateTime.now().minusDays(3);
        e.bots().save(new ManagedBot(555L, "old_bot", "Old", 7L, "ENC(x)", longAgo, longAgo));
        String id = claimedIntent(e, 7L, "tenant_shop_bot");

        e.service().handleUpdate(managedBotUpdate(556L, 7L, "tenant_shop_bot"));   // a different, new bot
        assertThat(e.intents().findById(id)).get()
                .extracting(ManagedBotIntent::botUserId).isEqualTo(556L);
    }

    @Test
    void anotherOwnersIntentIsNeverMatched() throws Exception {
        Env e = env();
        String id = claimedIntent(e, 8L, "tenant_shop_bot");

        e.service().handleUpdate(managedBotUpdate(555L, 7L, "tenant_shop_bot"));

        assertThat(e.intents().findById(id)).get()
                .extracting(ManagedBotIntent::status).isEqualTo(ManagedBotIntentStatus.CLAIMED);
        assertThat(e.events().events).isEmpty();
    }

    @Test
    void aRotationNeverMatchesAndAReDeliveredUpdateNeverMatchesTwice() throws Exception {
        Env e = env();
        String id = claimedIntent(e, 7L, "tenant_shop_bot");

        e.service().handleUpdate(managedBotUpdate(555L, 7L, "tenant_shop_bot"));
        e.service().handleUpdate(managedBotUpdate(555L, 7L, "tenant_shop_bot"));   // re-delivery = rotation

        assertThat(e.events().events).containsExactly("matched:555:" + id);
    }

    @Test
    void withNoCandidatesNothingHappensAtAll() throws Exception {
        Env e = env();

        e.service().handleUpdate(managedBotUpdate(555L, 7L, "tenant_shop_bot"));

        assertThat(e.events().events).isEmpty();
        assertThat(e.bots().findByBotUserId(555L)).isPresent();
    }

    @Test
    void recoveryThroughFetchAndStoreAlsoMatches() {
        Env e = env();
        String id = claimedIntent(e, 7L, "tenant_shop_bot");

        e.service().fetchAndStore(555L, 7L);

        assertThat(e.intents().findById(id)).get()
                .extracting(ManagedBotIntent::status).isEqualTo(ManagedBotIntentStatus.COMPLETED);
    }

    @Test
    void aListenerThrowingInOnCreatedDoesNotCostTheIntentEvent() throws Exception {
        Env base = env();
        List<String> seen = new ArrayList<>();
        ManagedBotEvents throwing = new ManagedBotEvents() {
            @Override public void onCreated(ManagedBot bot) { throw new IllegalStateException("host bug"); }
            @Override public void onIntentMatched(ManagedBot bot, ManagedBotIntent intent) {
                seen.add("matched:" + intent.id());
            }
        };
        ManagedBotService service = new ManagedBotService(
                TelegramBotModule.builder("123:ABC", "manager_bot").bot(new TelegramBot(
                        HttpClient.newHttpClient(), "123:ABC") {
                    @Override public String getManagedBotToken(long botUserId) { return "999:CHILD"; }
                }).build(),
                base.bots(), new TokenEncryptor() {
                    @Override public String encrypt(String p) { return p; }
                    @Override public String decrypt(String c) { return c; }
                }, throwing, 1, Duration.ZERO, base.intents(), Duration.ofMinutes(30), Duration.ofDays(7));
        String id = base.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();
        base.intents().save(base.intents().findById(id).orElseThrow().claimedBy(7L, OffsetDateTime.now()));

        service.handleUpdate(managedBotUpdate(555L, 7L, "tenant_shop_bot"));

        assertThat(seen).containsExactly("matched:" + id);
        assertThat(base.intents().findById(id)).get()
                .extracting(ManagedBotIntent::status).isEqualTo(ManagedBotIntentStatus.COMPLETED);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -Dtest=ManagedBotIntentServiceTest test`
Expected: compilation failure (`onIntentMatched` etc. are not on `ManagedBotEvents`), then assertion failures once they compile.

- [ ] **Step 3: Add the four default events**

```java
    /** A user opened an intent link and proved who they are. The intent now waits for its bot. */
    default void onIntentClaimed(ManagedBotIntent intent) { }

    /** A bot and an intent were linked — automatically, or by the host calling {@code assignToIntent}. */
    default void onIntentMatched(ManagedBot bot, ManagedBotIntent intent) { }

    /**
     * A created bot fits more than one of its creator's waiting intents, so nothing
     * was linked. Show the creator their intents and let a human decide.
     */
    default void onIntentUnmatched(ManagedBot bot, List<ManagedBotIntent> candidates) { }

    /**
     * The mirror image, on claim: this intent fits more than one of the creator's
     * unassigned bots. Nothing was linked.
     */
    default void onIntentAmbiguous(ManagedBotIntent intent, List<ManagedBot> candidates) { }
```

- [ ] **Step 4: Wire matching into `storeAndAnnounce`**

State is persisted before anything is published, and every publish is guarded — a host listener that throws must not cost the next event:

```java
    private ManagedBot storeAndAnnounce(long botUserId, long ownerUserId,
                                        String username, String firstName, String rawToken) {
        Optional<ManagedBot> known = store.findByBotUserId(botUserId);
        ManagedBot saved = persist(botUserId,
                username != null ? username : known.map(ManagedBot::username).orElse(null),
                firstName != null ? firstName : known.map(ManagedBot::firstName).orElse(null),
                ownerUserId, rawToken,
                known.map(ManagedBot::createdAt).orElse(null));
        if (known.isEmpty()) {
            // Matching first: it only writes rows. Publishing comes after, so a listener
            // that throws cannot leave an intent half-linked or swallow the intent event.
            Runnable intentEvent = matchOnCreation(saved);
            publish("onCreated", () -> events.onCreated(saved));
            if (intentEvent != null) intentEvent.run();
        } else {
            publish("onTokenRotated", () -> events.onTokenRotated(saved));
        }
        return saved;
    }

    /**
     * Links this freshly created bot to one of its creator's waiting intents.
     *
     * @return the event to publish once {@code onCreated} has run, or {@code null}
     *         when there was nothing to decide
     */
    private Runnable matchOnCreation(ManagedBot bot) {
        if (intentStore == null) return null;
        if (intentStore.findByBotUserId(bot.botUserId()).isPresent()) return null;
        List<ManagedBotIntent> candidates = candidatesFor(bot);
        if (candidates.isEmpty()) return null;
        ManagedBotIntent chosen = pickIntent(candidates, bot.username());
        if (chosen == null) {
            return () -> publish("onIntentUnmatched", () -> events.onIntentUnmatched(bot, candidates));
        }
        ManagedBotIntent done = chosen.completedWith(bot.botUserId(), OffsetDateTime.now());
        intentStore.save(done);
        return () -> publish("onIntentMatched", () -> events.onIntentMatched(bot, done));
    }

    /**
     * An intent cannot predate the bot it asked for, so anything created before the
     * intent existed belongs to some other purpose and is never auto-matched. The
     * manual resolution screen still lists it — see {@link #findUnassignedBots}.
     */
    private List<ManagedBotIntent> candidatesFor(ManagedBot bot) {
        return intentStore.findClaimedByOwner(bot.ownerUserId()).stream()
                .filter(i -> !bot.createdAt().isBefore(i.createdAt()))
                .toList();
    }

    /** @return the single sensible candidate, or {@code null} when a human has to choose */
    private static ManagedBotIntent pickIntent(List<ManagedBotIntent> candidates, String username) {
        if (username != null) {
            List<ManagedBotIntent> byUsername = candidates.stream()
                    .filter(i -> i.suggestedUsername() != null
                            && i.suggestedUsername().equalsIgnoreCase(username))
                    .toList();
            if (byUsername.size() == 1) return byUsername.get(0);
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /**
     * Runs a host callback without letting it derail the rest. The white-label bridge
     * guards every callback already; this brings the direct path in line, which is what
     * makes "matching state is persisted, then events are published" a promise.
     */
    private void publish(String event, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            log.warn("managed-bot listener failed on {}", event, t);
        }
    }
```

Also wrap the two existing publishes in `rotateToken` and `decommission` with `publish(...)`, for the same reason.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -q test`
Expected: PASS. If an existing test asserted that a throwing `onCreated` propagates out of `handleUpdate`, update that test to assert the warning path instead and note the behaviour change for the CHANGELOG (Task 13).

- [ ] **Step 6: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat(managed-bots): match a created bot to its creator's waiting intent"
```

---

### Task 5: Claiming an intent, and matching on claim

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/IntentClaim.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/IntentClaimResult.java`
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotService.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotIntentServiceTest.java` (extend)

**Interfaces:**
- Consumes: Tasks 3–4.
- Produces: `IntentClaim` enum (`CLAIMED`, `RECLAIMED`, `COMPLETED`, `OTHER_OWNER`, `CLOSED`, `UNKNOWN`), `IntentClaimResult(IntentClaim outcome, ManagedBotIntent intent)`, `ManagedBotService.claimIntent(String, long)`, private `matchOnClaim(ManagedBotIntent)` and `unassignedBotsFor(ManagedBotIntent, boolean)`.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void theFirstClaimRecordsTheOwnerAndAnnouncesIt() {
        Env e = env();
        String id = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();

        IntentClaimResult r = e.service().claimIntent(id, 7L);

        assertThat(r.outcome()).isEqualTo(IntentClaim.CLAIMED);
        assertThat(r.intent().ownerUserId()).isEqualTo(7L);
        assertThat(r.intent().claimedAt()).isNotNull();
        assertThat(e.events().events).containsExactly("claimed:" + id);
    }

    @Test
    void theSameUserTappingAgainIsIdempotent() {
        Env e = env();
        String id = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();
        e.service().claimIntent(id, 7L);

        assertThat(e.service().claimIntent(id, 7L).outcome()).isEqualTo(IntentClaim.RECLAIMED);
        assertThat(e.events().events).containsExactly("claimed:" + id);
    }

    @Test
    void aForwardedLinkCannotBeStolenBySomeoneElse() {
        Env e = env();
        String id = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();
        e.service().claimIntent(id, 7L);

        IntentClaimResult r = e.service().claimIntent(id, 8L);

        assertThat(r.outcome()).isEqualTo(IntentClaim.OTHER_OWNER);
        assertThat(e.service().findIntent(id)).get()
                .extracting(ManagedBotIntent::ownerUserId).isEqualTo(7L);
    }

    @Test
    void anUnknownPayloadIsNotOursAndAClosedOneIs() {
        Env e = env();
        String id = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();
        e.service().cancelIntent(id);

        assertThat(e.service().claimIntent("not-an-intent", 7L).outcome()).isEqualTo(IntentClaim.UNKNOWN);
        assertThat(e.service().claimIntent(id, 7L).outcome()).isEqualTo(IntentClaim.CLOSED);
    }

    @Test
    void claimingCompletesTheIntentWhenTheBotWasCreatedFirst() {
        Env e = env();
        String id = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();
        OffsetDateTime now = OffsetDateTime.now();
        e.bots().save(new ManagedBot(555L, "tenant_shop_bot", "Shop", 7L, "ENC(x)", now, now));

        IntentClaimResult r = e.service().claimIntent(id, 7L);

        assertThat(r.intent().status()).isEqualTo(ManagedBotIntentStatus.COMPLETED);
        assertThat(r.intent().botUserId()).isEqualTo(555L);
        assertThat(e.events().events).containsExactly("claimed:" + id, "matched:555:" + id);
    }

    @Test
    void claimingWithTwoUnassignedBotsAsksTheHostToDecide() {
        Env e = env();
        String id = e.service().createIntent(null, "Shop", "bot:1").intentId();
        OffsetDateTime now = OffsetDateTime.now();
        e.bots().save(new ManagedBot(555L, "one_bot", "One", 7L, "ENC(x)", now, now));
        e.bots().save(new ManagedBot(556L, "two_bot", "Two", 7L, "ENC(x)", now, now));

        IntentClaimResult r = e.service().claimIntent(id, 7L);

        assertThat(r.intent().status()).isEqualTo(ManagedBotIntentStatus.CLAIMED);
        assertThat(e.events().events).containsExactly("claimed:" + id, "ambiguous:" + id + ":2");
    }

    @Test
    void claimingIgnoresBotsOlderThanTheIntent() {
        Env e = env();
        String id = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();
        OffsetDateTime longAgo = OffsetDateTime.now().minusDays(3);
        e.bots().save(new ManagedBot(555L, "tenant_shop_bot", "Shop", 7L, "ENC(x)", longAgo, longAgo));

        IntentClaimResult r = e.service().claimIntent(id, 7L);

        assertThat(r.intent().status()).isEqualTo(ManagedBotIntentStatus.CLAIMED);
        assertThat(e.events().events).containsExactly("claimed:" + id);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -Dtest=ManagedBotIntentServiceTest test`
Expected: compilation failure — `IntentClaim`, `IntentClaimResult`, `claimIntent` do not exist.

- [ ] **Step 3: Write the result types**

```java
/**
 * How a {@code /start mb_<id>} tap was resolved.
 *
 * <p>{@link #UNKNOWN} is the one that matters for routing: the payload is not an
 * intent this store knows, so the update is <b>not</b> ours and must fall through
 * to whatever else handles {@code /start} — a login token can begin with
 * {@code mb_} by chance.
 */
public enum IntentClaim { CLAIMED, RECLAIMED, COMPLETED, OTHER_OWNER, CLOSED, UNKNOWN }
```

```java
/** @param intent {@code null} only for {@link IntentClaim#UNKNOWN} */
public record IntentClaimResult(IntentClaim outcome, ManagedBotIntent intent) { }
```

- [ ] **Step 4: Implement `claimIntent` and claim-time matching**

```java
    /**
     * Resolves one {@code /start mb_<id>} tap. Public because a host that replaced
     * {@code /start} with its own handler still needs this decision.
     *
     * <p>The first claim also runs the matching step: the bot may already exist when
     * the link is opened — a host still handing out {@code createLink}, or a
     * {@code fetchAndStore} recovery that landed first.
     */
    public IntentClaimResult claimIntent(String intentId, long ownerUserId) {
        ManagedBotIntentStore intents = requireIntentStore();
        ManagedBotIntent intent = intents.findById(intentId).map(this::expireIfDue).orElse(null);
        if (intent == null) return new IntentClaimResult(IntentClaim.UNKNOWN, null);
        boolean sameOwner = intent.ownerUserId() != null && intent.ownerUserId() == ownerUserId;
        switch (intent.status()) {
            case EXPIRED, CANCELLED:
                return new IntentClaimResult(IntentClaim.CLOSED, intent);
            case COMPLETED:
                return new IntentClaimResult(
                        sameOwner ? IntentClaim.COMPLETED : IntentClaim.OTHER_OWNER, intent);
            case CLAIMED:
                return new IntentClaimResult(
                        sameOwner ? IntentClaim.RECLAIMED : IntentClaim.OTHER_OWNER, intent);
            case OPEN:
            default:
                ManagedBotIntent claimed = intent.claimedBy(ownerUserId, OffsetDateTime.now());
                intents.save(claimed);
                publish("onIntentClaimed", () -> events.onIntentClaimed(claimed));
                return new IntentClaimResult(IntentClaim.CLAIMED, matchOnClaim(claimed));
        }
    }

    /** @return the intent as it now stands — {@code COMPLETED} when a waiting bot fit it */
    private ManagedBotIntent matchOnClaim(ManagedBotIntent intent) {
        List<ManagedBot> candidates = unassignedBotsFor(intent, true);
        if (candidates.isEmpty()) return intent;
        ManagedBot chosen = pickBot(candidates, intent.suggestedUsername());
        if (chosen == null) {
            publish("onIntentAmbiguous", () -> events.onIntentAmbiguous(intent, candidates));
            return intent;
        }
        ManagedBotIntent done = intent.completedWith(chosen.botUserId(), OffsetDateTime.now());
        intentStore.save(done);
        publish("onIntentMatched", () -> events.onIntentMatched(chosen, done));
        return done;
    }

    /**
     * The creator's bots that no intent claims.
     *
     * @param sinceIntent {@code true} drops bots older than the intent — right for
     *                    automatic matching, wrong for the manual screen, which is
     *                    exactly where an older bot has to be reachable
     */
    private List<ManagedBot> unassignedBotsFor(ManagedBotIntent intent, boolean sinceIntent) {
        if (intent.ownerUserId() == null) return List.of();
        return store.findByOwnerUserId(intent.ownerUserId()).stream()
                .filter(b -> intentStore.findByBotUserId(b.botUserId()).isEmpty())
                .filter(b -> !sinceIntent || !b.createdAt().isBefore(intent.createdAt()))
                .toList();
    }

    /** {@link #pickIntent} from the other side. */
    private static ManagedBot pickBot(List<ManagedBot> candidates, String suggestedUsername) {
        if (suggestedUsername != null) {
            List<ManagedBot> byUsername = candidates.stream()
                    .filter(b -> b.username() != null && suggestedUsername.equalsIgnoreCase(b.username()))
                    .toList();
            if (byUsername.size() == 1) return byUsername.get(0);
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }
```

Note on the `switch`: Java 17 allows arrow-form enum switches; use whichever form reads better, but every branch must return.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -q test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat(managed-bots): claim an intent and match a bot that already exists"
```

---

### Task 6: Manual resolution — `findUnassignedBots`, `assignToIntent`, `decommissionUnassigned`

**Files:**
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotService.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotIntentServiceTest.java` (extend)

**Interfaces:**
- Consumes: Tasks 3–5.
- Produces: `List<ManagedBot> findUnassignedBots(String intentId)`, `ManagedBotIntent assignToIntent(String intentId, long botUserId)`, `void decommissionUnassigned(long botUserId)`.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void theResolutionScreenListsEveryUnassignedBotOfTheOwnerIncludingOldOnes() {
        Env e = env();
        OffsetDateTime longAgo = OffsetDateTime.now().minusDays(3);
        OffsetDateTime now = OffsetDateTime.now();
        e.bots().save(new ManagedBot(555L, "old_bot", "Old", 7L, "ENC(x)", longAgo, longAgo));
        e.bots().save(new ManagedBot(556L, "new_bot", "New", 7L, "ENC(x)", now, now));
        e.bots().save(new ManagedBot(557L, "other_owner_bot", "Other", 8L, "ENC(x)", now, now));
        String id = claimedIntent(e, 7L, null);
        e.intents().save(e.intents().findById(id).orElseThrow());   // still CLAIMED

        assertThat(e.service().findUnassignedBots(id))
                .extracting(ManagedBot::botUserId).containsExactlyInAnyOrder(555L, 556L);
    }

    @Test
    void anAssignedBotDropsOutOfTheListAndANonClaimedIntentListsNothing() {
        Env e = env();
        OffsetDateTime now = OffsetDateTime.now();
        e.bots().save(new ManagedBot(555L, "one_bot", "One", 7L, "ENC(x)", now, now));
        String taken = claimedIntent(e, 7L, null);
        e.service().assignToIntent(taken, 555L);

        String fresh = claimedIntent(e, 7L, null);
        assertThat(e.service().findUnassignedBots(fresh)).isEmpty();

        String open = e.service().createIntent(null, null, null).intentId();
        assertThat(e.service().findUnassignedBots(open)).isEmpty();
    }

    @Test
    void assignCompletesTheIntentAndAnnouncesTheMatch() {
        Env e = env();
        OffsetDateTime now = OffsetDateTime.now();
        e.bots().save(new ManagedBot(555L, "one_bot", "One", 7L, "ENC(x)", now, now));
        String id = claimedIntent(e, 7L, null);

        ManagedBotIntent done = e.service().assignToIntent(id, 555L);

        assertThat(done.status()).isEqualTo(ManagedBotIntentStatus.COMPLETED);
        assertThat(done.botUserId()).isEqualTo(555L);
        assertThat(e.events().events).contains("matched:555:" + id);
    }

    @Test
    void assignRefusesEveryWayItCan() {
        Env e = env();
        OffsetDateTime now = OffsetDateTime.now();
        e.bots().save(new ManagedBot(555L, "one_bot", "One", 7L, "ENC(x)", now, now));
        e.bots().save(new ManagedBot(557L, "other_bot", "Other", 8L, "ENC(x)", now, now));
        String claimed = claimedIntent(e, 7L, null);
        String open = e.service().createIntent(null, null, null).intentId();

        assertReason(() -> e.service().assignToIntent("nope", 555L),
                ManagedBotIntentException.Reason.INTENT_NOT_FOUND);
        assertReason(() -> e.service().assignToIntent(open, 555L),
                ManagedBotIntentException.Reason.INTENT_NOT_CLAIMED);
        assertReason(() -> e.service().assignToIntent(claimed, 999L),
                ManagedBotIntentException.Reason.BOT_NOT_FOUND);
        assertReason(() -> e.service().assignToIntent(claimed, 557L),
                ManagedBotIntentException.Reason.OWNER_MISMATCH);

        e.service().assignToIntent(claimed, 555L);
        String second = claimedIntent(e, 7L, null);
        assertReason(() -> e.service().assignToIntent(second, 555L),
                ManagedBotIntentException.Reason.BOT_ALREADY_ASSIGNED);
    }

    @Test
    void decommissionUnassignedRefusesABotThatIsAlreadyLinked() {
        Env e = env();
        OffsetDateTime now = OffsetDateTime.now();
        e.bots().save(new ManagedBot(555L, "one_bot", "One", 7L, "ENC(x)", now, now));
        e.bots().save(new ManagedBot(556L, "two_bot", "Two", 7L, "ENC(x)", now, now));
        String id = claimedIntent(e, 7L, null);
        e.service().assignToIntent(id, 555L);

        assertReason(() -> e.service().decommissionUnassigned(555L),
                ManagedBotIntentException.Reason.BOT_ALREADY_ASSIGNED);
        assertThat(e.bots().findByBotUserId(555L)).isPresent();

        e.service().decommissionUnassigned(556L);
        assertThat(e.bots().findByBotUserId(556L)).isEmpty();
    }

    private static void assertReason(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                     ManagedBotIntentException.Reason expected) {
        assertThatThrownBy(call)
                .isInstanceOf(ManagedBotIntentException.class)
                .extracting(t -> ((ManagedBotIntentException) t).reason())
                .isEqualTo(expected);
    }
```

The fake bot in `env()` needs `replaceManagedBotToken` for the decommission path — add to the `TelegramBot` subclass in `env()`:

```java
            @Override public String replaceManagedBotToken(long botUserId) { return "999:ROTATED"; }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -Dtest=ManagedBotIntentServiceTest test`
Expected: compilation failure — the three methods do not exist.

- [ ] **Step 3: Implement the three methods**

```java
    /**
     * The creator's bots that no intent claims, for the screen where a human says
     * which bot was meant for what. Unfiltered by age on purpose: a bot created
     * before intents existed is exactly the case this screen has to repair.
     *
     * @return empty when the intent is unknown or not {@code CLAIMED}
     */
    public List<ManagedBot> findUnassignedBots(String intentId) {
        ManagedBotIntent intent = findIntent(intentId).orElse(null);
        if (intent == null || intent.status() != ManagedBotIntentStatus.CLAIMED) return List.of();
        return unassignedBotsFor(intent, false);
    }

    /**
     * Links a bot to an intent by hand, after {@code onIntentUnmatched} or
     * {@code onIntentAmbiguous} sent the decision to a human.
     *
     * @throws ManagedBotIntentException with the reason that applies; a concurrent
     *         assignment surfaces as {@code BOT_ALREADY_ASSIGNED} through the unique
     *         {@code bot_user_id} constraint
     */
    public ManagedBotIntent assignToIntent(String intentId, long botUserId) {
        ManagedBotIntentStore intents = requireIntentStore();
        ManagedBotIntent intent = findIntent(intentId).orElseThrow(() -> new ManagedBotIntentException(
                ManagedBotIntentException.Reason.INTENT_NOT_FOUND, "unknown intent " + intentId));
        if (intent.status() != ManagedBotIntentStatus.CLAIMED) {
            throw new ManagedBotIntentException(ManagedBotIntentException.Reason.INTENT_NOT_CLAIMED,
                    "intent " + intentId + " is " + intent.status());
        }
        ManagedBot bot = store.findByBotUserId(botUserId).orElseThrow(() -> new ManagedBotIntentException(
                ManagedBotIntentException.Reason.BOT_NOT_FOUND, "unknown managed bot " + botUserId));
        if (intent.ownerUserId() == null || bot.ownerUserId() != intent.ownerUserId()) {
            throw new ManagedBotIntentException(ManagedBotIntentException.Reason.OWNER_MISMATCH,
                    "managed bot " + botUserId + " was not created by the intent's owner");
        }
        if (intents.findByBotUserId(botUserId).isPresent()) {
            throw new ManagedBotIntentException(ManagedBotIntentException.Reason.BOT_ALREADY_ASSIGNED,
                    "managed bot " + botUserId + " is already assigned to an intent");
        }
        ManagedBotIntent done = intent.completedWith(botUserId, OffsetDateTime.now());
        try {
            intents.save(done);
        } catch (RuntimeException e) {
            // The unique bot_user_id index is the real arbiter when two assignments race.
            throw new ManagedBotIntentException(ManagedBotIntentException.Reason.BOT_ALREADY_ASSIGNED,
                    "managed bot " + botUserId + " was assigned concurrently", e);
        }
        publish("onIntentMatched", () -> events.onIntentMatched(bot, done));
        return done;
    }

    /**
     * Removes a bot the user does not want from the resolution screen: same revoke
     * and forget as {@link #decommission(long)}, but it refuses a bot some intent
     * already claims, so a misclick cannot disconnect a live tenant.
     *
     * <p>The bot keeps existing on Telegram; only its owner can delete it, in BotFather.
     */
    public void decommissionUnassigned(long botUserId) {
        if (intentStore != null && intentStore.findByBotUserId(botUserId).isPresent()) {
            throw new ManagedBotIntentException(ManagedBotIntentException.Reason.BOT_ALREADY_ASSIGNED,
                    "managed bot " + botUserId + " is assigned to an intent");
        }
        decommission(botUserId);
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat(managed-bots): assign or retire an unassigned bot from the host"
```

---

### Task 7: `/start` payload routing

**Files:**
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotModule.java`
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/bot/BotUpdateDispatcher.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotModuleTest.java` (extend)
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/bot/BotUpdateDispatcherTest.java` (extend)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `TelegramBotModule.startPayload(String prefix, Predicate<JsonNode> handler)` and `Map<String, Predicate<JsonNode>> getStartPayloadRoutes()`.

- [ ] **Step 1: Write the failing tests**

In `TelegramBotModuleTest`:

```java
    @Test
    void aStartPayloadPrefixCanBeClaimedOnceAndOnlyOnce() {
        TelegramBotModule m = TelegramBotModule.builder("123:ABC", "demo_bot").build();
        Predicate<JsonNode> handler = u -> true;
        m.startPayload("mb_", handler);
        m.startPayload("mb_", handler);   // same handler: idempotent

        assertThat(m.getStartPayloadRoutes()).containsOnlyKeys("mb_");
        assertThatThrownBy(() -> m.startPayload("mb_", u -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mb_");
    }

    @Test
    void aStartPayloadPrefixMustBeUsableInATelegramDeepLink() {
        TelegramBotModule m = TelegramBotModule.builder("123:ABC", "demo_bot").build();

        assertThatThrownBy(() -> m.startPayload("  ", u -> true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> m.startPayload("mb:", u -> true))
                .isInstanceOf(IllegalArgumentException.class);
    }
```

In `BotUpdateDispatcherTest`:

```java
    @Test
    void aClaimedStartPayloadNeverReachesTheStartCommand() {
        TelegramBotModule m = module();
        AtomicReference<JsonNode> login = new AtomicReference<>();
        AtomicReference<String> claimed = new AtomicReference<>();
        m.command("/start", login::set);
        m.startPayload("mb_", u -> {
            claimed.set(u.path("message").path("text").asText());
            return true;
        });
        BotUpdateDispatcher d = new BotUpdateDispatcher(m);

        String json = "{\"ok\":true,\"result\":[{\"update_id\":11,"
                + "\"message\":{\"text\":\"/start mb_abc123\",\"chat\":{\"id\":5}}}]}";
        assertThat(d.dispatch(json)).isEqualTo(11);
        assertThat(claimed.get()).isEqualTo("/start mb_abc123");
        assertThat(login.get()).isNull();
    }

    @Test
    void anUnclaimedStartPayloadFallsThroughToTheStartCommand() {
        TelegramBotModule m = module();
        AtomicReference<JsonNode> login = new AtomicReference<>();
        m.command("/start", login::set);
        m.startPayload("mb_", u -> false);          // "not an intent I know"
        BotUpdateDispatcher d = new BotUpdateDispatcher(m);

        // A Base64URL login token really can start with mb_ — roughly one in 262144.
        String json = "{\"ok\":true,\"result\":[{\"update_id\":12,"
                + "\"message\":{\"text\":\"/start mb_aLoginTokenThatLooksLikeAnIntent\",\"chat\":{\"id\":5}}}]}";
        assertThat(d.dispatch(json)).isEqualTo(12);
        assertThat(login.get()).isNotNull();
    }

    @Test
    void startPayloadRoutingSurvivesTheBotSuffixAndIgnoresAPlainStart() {
        TelegramBotModule m = module();
        AtomicReference<JsonNode> login = new AtomicReference<>();
        AtomicReference<Integer> claims = new AtomicReference<>(0);
        m.command("/start", login::set);
        m.startPayload("mb_", u -> { claims.set(claims.get() + 1); return true; });
        BotUpdateDispatcher d = new BotUpdateDispatcher(m);

        d.dispatch("{\"ok\":true,\"result\":[{\"update_id\":13,"
                + "\"message\":{\"text\":\"/start@demo_bot mb_abc\",\"chat\":{\"id\":5}}}]}");
        d.dispatch("{\"ok\":true,\"result\":[{\"update_id\":14,"
                + "\"message\":{\"text\":\"/start\",\"chat\":{\"id\":5}}}]}");

        assertThat(claims.get()).isEqualTo(1);
        assertThat(login.get()).isNotNull();        // the bare /start reached the command
    }

    @Test
    void aClaimedPayloadWithNoStartCommandRegisteredIsSimplyDropped() {
        TelegramBotModule m = module();
        m.startPayload("mb_", u -> false);
        BotUpdateDispatcher d = new BotUpdateDispatcher(m);

        assertThat(d.dispatch("{\"ok\":true,\"result\":[{\"update_id\":15,"
                + "\"message\":{\"text\":\"/start mb_abc\",\"chat\":{\"id\":5}}}]}")).isEqualTo(15);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -Dtest='TelegramBotModuleTest,BotUpdateDispatcherTest' test`
Expected: compilation failure — `startPayload` does not exist.

- [ ] **Step 3: Add the registry to `TelegramBotModule`**

```java
    private final Map<String, Predicate<JsonNode>> startPayloadRoutes = new ConcurrentHashMap<>();

    /**
     * Routes {@code /start <payload>} updates whose payload begins with {@code prefix}
     * to {@code handler}, <em>before</em> the command registry sees them.
     *
     * <p>The handler returns whether it owned the update. {@code false} sends the
     * update on to the normal {@code /start} handler, and that fall-through is not a
     * nicety: login tokens are Base64URL, so one in a few hundred thousand of them
     * begins with any given three-character prefix. A handler that swallowed those
     * would break a real login silently and unreproducibly.
     *
     * @param prefix limited to Telegram's start-payload alphabet {@code [A-Za-z0-9_-]}
     * @throws IllegalStateException if a different handler already holds this prefix
     */
    public void startPayload(String prefix, Predicate<JsonNode> handler) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("a /start payload prefix must not be blank");
        }
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            boolean allowed = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!allowed) {
                throw new IllegalArgumentException("a /start payload prefix may only contain "
                        + "A-Z, a-z, 0-9, _ and - but was " + prefix);
            }
        }
        Predicate<JsonNode> current = startPayloadRoutes.get(prefix);
        if (current != null && current != handler) {
            throw new IllegalStateException("a /start payload handler for prefix '" + prefix
                    + "' is already registered on this module");
        }
        startPayloadRoutes.put(prefix, handler);
    }

    public Map<String, Predicate<JsonNode>> getStartPayloadRoutes() {
        return Collections.unmodifiableMap(startPayloadRoutes);
    }
```

- [ ] **Step 4: Consult the registry in `BotUpdateDispatcher`**

Replace the command branch of `route`:

```java
        JsonNode message = update.path("message");
        String text = message.path("text").asText("");
        if (text.startsWith("/")) {
            String command = parseCommand(text);
            Consumer<JsonNode> handler = module.getCommands().get(command);
            Predicate<JsonNode> route = START.equals(command) ? startRouteFor(text) : null;
            if (route != null) {
                // One composed handler, one executor hop: the predicate does the store
                // lookup on the worker thread, and the fall-through decision is made
                // where its answer is known.
                Consumer<JsonNode> next = handler;
                invoke(u -> { if (!route.test(u) && next != null) next.accept(u); }, update);
                return;
            }
            if (handler != null) {
                invoke(handler, update);
                return;
            }
        }
```

with the constant `private static final String START = "/start";` and:

```java
    /** @return the handler whose prefix matches this {@code /start} payload — longest wins — or {@code null} */
    private Predicate<JsonNode> startRouteFor(String text) {
        int space = text.indexOf(' ');
        if (space < 0) return null;
        String payload = text.substring(space + 1).trim();
        if (payload.isEmpty()) return null;
        Predicate<JsonNode> best = null;
        int bestLength = -1;
        for (Map.Entry<String, Predicate<JsonNode>> e : module.getStartPayloadRoutes().entrySet()) {
            if (payload.startsWith(e.getKey()) && e.getKey().length() > bestLength) {
                best = e.getValue();
                bestLength = e.getKey().length();
            }
        }
        return best;
    }
```

Update the class javadoc's routing order sentence to mention the start-payload routes.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -q test`
Expected: PASS, including the existing routing tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/dev_abdulhay/telegramauth/bot src/test/java/io/github/dev_abdulhay/telegramauth/bot
git commit -m "feat(bot): route /start payloads by prefix with fall-through to the command"
```

---

### Task 8: `ManagedBotIntentFlow` and its bot texts

**Files:**
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/flow/FlowMessages.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotIntentFlow.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotIntentFlowTest.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/flow/FlowMessagesTest.java` (create if absent)

**Interfaces:**
- Consumes: Tasks 5 and 7 — `ManagedBotService.claimIntent`, `TelegramBotModule.startPayload`.
- Produces: `FlowMessages.Key.INTENT_PROMPT`, `BTN_CREATE_BOT`, `INTENT_OTHER_OWNER`, `INTENT_ALREADY_DONE`; `ManagedBotIntentFlow(TelegramBotModule, ManagedBotService)` with `protected boolean onStart(JsonNode)` and `protected String msg(FlowMessages.Key, String)`.

- [ ] **Step 1: Write the failing tests**

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBot;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ManagedBotIntentFlowTest {

    private static final ObjectMapper M = new ObjectMapper();

    static class RecordingBot extends TelegramBot {
        final List<String> sent = new ArrayList<>();
        RecordingBot() { super(HttpClient.newHttpClient(), "123:ABC"); }
        @Override public void sendMessage(long chatId, String text) { sent.add(chatId + ":" + text); }
        @Override public void sendMessage(long chatId, String text, String markup) {
            sent.add(chatId + ":" + text + ":" + markup);
        }
    }

    record Env(RecordingBot bot, InMemoryManagedBotIntentStore intents,
               ManagedBotService service, ManagedBotIntentFlow flow, TelegramBotModule module) { }

    private static Env env() {
        RecordingBot bot = new RecordingBot();
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "manager_bot").bot(bot).build();
        InMemoryManagedBotIntentStore intents = new InMemoryManagedBotIntentStore();
        ManagedBotService service = new ManagedBotService(module, new InMemoryManagedBotStore(),
                new TokenEncryptor() {
                    @Override public String encrypt(String p) { return p; }
                    @Override public String decrypt(String c) { return c; }
                }, new ManagedBotEvents() { }, 1, Duration.ZERO,
                intents, Duration.ofMinutes(30), Duration.ofDays(7));
        return new Env(bot, intents, service, new ManagedBotIntentFlow(module, service), module);
    }

    private static JsonNode start(String payload, long userId, long chatId) throws Exception {
        return M.readTree("{\"message\":{\"text\":\"/start " + payload + "\","
                + "\"from\":{\"id\":" + userId + ",\"language_code\":\"uz\"},"
                + "\"chat\":{\"id\":" + chatId + "}}}");
    }

    @Test
    void theFlowClaimsTheStartPayloadPrefixOnConstruction() {
        assertThat(env().module().getStartPayloadRoutes()).containsOnlyKeys("mb_");
    }

    @Test
    void claimingSendsThePromptWithABotCreationButton() throws Exception {
        Env e = env();
        String id = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();

        assertThat(e.flow().onStart(start("mb_" + id, 7L, 7L))).isTrue();

        assertThat(e.intents().findById(id)).get()
                .extracting(ManagedBotIntent::ownerUserId).isEqualTo(7L);
        assertThat(e.bot().sent).hasSize(1);
        assertThat(e.bot().sent.get(0))
                .contains("https://t.me/newbot/manager_bot/tenant_shop_bot?name=Shop")
                .contains("inline_keyboard");
    }

    @Test
    void anUnknownPayloadIsNotOursSoNothingIsSaidAndTheUpdateFallsThrough() throws Exception {
        Env e = env();

        assertThat(e.flow().onStart(start("mb_notAnIntentId", 7L, 7L))).isFalse();
        assertThat(e.bot().sent).isEmpty();
    }

    @Test
    void aCancelledIntentAnswersWithTheInvalidLinkText() throws Exception {
        Env e = env();
        String id = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();
        e.service().cancelIntent(id);

        assertThat(e.flow().onStart(start("mb_" + id, 7L, 7L))).isTrue();
        assertThat(e.bot().sent.get(0)).contains("yaroqsiz");
    }

    @Test
    void aSecondUserIsToldTheLinkIsNotTheirs() throws Exception {
        Env e = env();
        String id = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();
        e.flow().onStart(start("mb_" + id, 7L, 7L));
        e.bot().sent.clear();

        assertThat(e.flow().onStart(start("mb_" + id, 8L, 8L))).isTrue();
        assertThat(e.bot().sent.get(0)).contains("boshqa foydalanuvchiga");
    }

    @Test
    void aCompletedIntentSaysTheBotAlreadyExists() throws Exception {
        Env e = env();
        String id = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();
        OffsetDateTime now = OffsetDateTime.now();
        e.intents().save(e.intents().findById(id).orElseThrow()
                .claimedBy(7L, now).completedWith(555L, now));

        assertThat(e.flow().onStart(start("mb_" + id, 7L, 7L))).isTrue();
        assertThat(e.bot().sent.get(0)).contains("allaqachon");
    }

    @Test
    void anIntentPayloadInAGroupChatIsSwallowedAndNeverAnswered() throws Exception {
        Env e = env();
        String id = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();

        assertThat(e.flow().onStart(start("mb_" + id, 7L, -100500L))).isTrue();
        assertThat(e.bot().sent).isEmpty();
        assertThat(e.intents().findById(id)).get()
                .extracting(ManagedBotIntent::status).isEqualTo(ManagedBotIntentStatus.OPEN);
    }
}
```

`FlowMessagesTest`:

```java
package io.github.dev_abdulhay.telegramauth.flow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlowMessagesTest {

    @Test
    void everyKeyHasTextInAllThreeLanguages() {
        for (FlowMessages.Key key : FlowMessages.Key.values()) {
            for (String lang : new String[] {"uz", "ru", "en"}) {
                assertThat(FlowMessages.text(key, lang))
                        .as("%s/%s", key, lang).isNotBlank();
            }
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -Dtest='ManagedBotIntentFlowTest,FlowMessagesTest' test`
Expected: compilation failure — `ManagedBotIntentFlow` and the four keys do not exist.

- [ ] **Step 3: Add the message keys**

In `FlowMessages.Key`, after `SESSION_EXPIRED`:

```java
        /** Shown when an intent link is claimed and its bot still has to be created. */
        INTENT_PROMPT,
        /** Label of the inline URL button that opens Telegram's bot-creation dialog. */
        BTN_CREATE_BOT,
        INTENT_OTHER_OWNER,
        INTENT_ALREADY_DONE
```

In `TEXTS`, four more entries:

```java
            Map.entry(Key.INTENT_PROMPT, Map.of(
                    "uz", "Botingizni yaratish uchun quyidagi tugmani bosing. Telegram'da bot nomini o'zgartirishingiz mumkin.",
                    "ru", "Нажмите кнопку ниже, чтобы создать бота. В Telegram имя бота можно изменить.",
                    "en", "Tap the button below to create your bot. You can change the bot's username in Telegram.")),
            Map.entry(Key.BTN_CREATE_BOT, Map.of(
                    "uz", "Bot yaratish",
                    "ru", "Создать бота",
                    "en", "Create bot")),
            Map.entry(Key.INTENT_OTHER_OWNER, Map.of(
                    "uz", "Bu havola boshqa foydalanuvchiga tegishli.",
                    "ru", "Эта ссылка принадлежит другому пользователю.",
                    "en", "This link belongs to a different user.")),
            Map.entry(Key.INTENT_ALREADY_DONE, Map.of(
                    "uz", "Bu havola bo'yicha bot allaqachon yaratilgan.",
                    "ru", "Бот по этой ссылке уже создан.",
                    "en", "The bot for this link has already been created."))
```

There is no key for "invalid or expired": `INVALID_LINK` already says exactly that.

- [ ] **Step 4: Write the flow**

```java
/**
 * Handles {@code /start mb_<intentId>} on the manager bot: claims the intent for
 * whoever opened it and answers with the button that creates the bot.
 *
 * <p>Self-registering, like {@code DefaultAuthFlow} — constructing it is all the
 * wiring a host needs. Registration goes through
 * {@link TelegramBotModule#startPayload}, not the command registry, so the login
 * flow keeps {@code /start} and a payload this flow does not recognise falls
 * straight through to it.
 *
 * <p>Override {@link #msg(FlowMessages.Key, String)} to change the wording.
 */
public class ManagedBotIntentFlow {

    private static final Logger log = LoggerFactory.getLogger(ManagedBotIntentFlow.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String START = "/start ";

    private final TelegramBotModule module;
    private final ManagedBotService service;

    public ManagedBotIntentFlow(TelegramBotModule module, ManagedBotService service) {
        this.module = module;
        this.service = service;
        module.startPayload(ManagedBotIntent.START_PREFIX, this::onStart);
    }

    /** @return whether this update was ours; {@code false} sends it on to the login flow */
    protected boolean onStart(JsonNode update) {
        JsonNode message = update.path("message");
        JsonNode from = message.path("from");
        long userId = from.path("id").asLong();
        if (message.path("chat").path("id").asLong() != userId) {
            // Groups and channels: the chat id is not a user id there, and an intent
            // must never be claimed from a chat where anyone could tap the link.
            log.debug("/start intent payload outside a private chat ignored");
            return true;
        }
        String text = message.path("text").asText("");
        String payload = text.length() > START.length() ? text.substring(START.length()).trim() : "";
        if (!payload.startsWith(ManagedBotIntent.START_PREFIX)) return false;
        String intentId = payload.substring(ManagedBotIntent.START_PREFIX.length());
        String lang = FlowMessages.resolveLang(from.path("language_code").asText(null));

        IntentClaimResult result = service.claimIntent(intentId, userId);
        switch (result.outcome()) {
            case UNKNOWN:
                return false;
            case CLOSED:
                send(userId, msg(FlowMessages.Key.INVALID_LINK, lang));
                return true;
            case OTHER_OWNER:
                send(userId, msg(FlowMessages.Key.INTENT_OTHER_OWNER, lang));
                return true;
            case COMPLETED:
                send(userId, msg(FlowMessages.Key.INTENT_ALREADY_DONE, lang));
                return true;
            default:   // CLAIMED, RECLAIMED
                if (result.intent().status() == ManagedBotIntentStatus.COMPLETED) {
                    // The claim itself found the bot: asking for one now would be absurd.
                    send(userId, msg(FlowMessages.Key.INTENT_ALREADY_DONE, lang));
                } else {
                    sendPrompt(userId, result.intent(), lang);
                }
                return true;
        }
    }

    private void sendPrompt(long userId, ManagedBotIntent intent, String lang) {
        String url = ManagedBotLink.build(module.getUsername(),
                intent.suggestedUsername(), intent.suggestedName());
        String markup = toJson(Map.of("inline_keyboard", List.of(List.of(
                Map.of("text", msg(FlowMessages.Key.BTN_CREATE_BOT, lang), "url", url)))));
        module.getBot().sendMessage(userId, msg(FlowMessages.Key.INTENT_PROMPT, lang), markup);
    }

    private void send(long userId, String text) {
        module.getBot().sendMessage(userId, text);
    }

    /** Override point for custom wording; defaults to the built-in 3-language table. */
    protected String msg(FlowMessages.Key key, String lang) {
        return FlowMessages.text(key, lang);
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialize a reply markup", e);
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -q test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat(managed-bots): claim intents from /start on the manager bot"
```

---

### Task 9: Auto-configuration and properties

**Files:**
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/TelegramManagedBotsProperties.java`
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/managedbots/TelegramManagedBotsAutoConfiguration.java`
- Delete: `src/main/resources/messages_tgauth.properties`, `messages_tgauth_ru.properties`, `messages_tgauth_en.properties`
- Test: `src/test/java/com/example/demo/ManagedBotIntentsAutoConfigTest.java`

**Interfaces:**
- Consumes: Tasks 1–8.
- Produces: `TelegramManagedBotsProperties.getIntentTtl()/setIntentTtl`, `getIntentRetention()/setIntentRetention`; a `ManagedBotIntentFlow` bean, conditional on a `ManagedBotIntentStore` bean.

- [ ] **Step 1: Write the failing test**

```java
package com.example.demo;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.managedbots.InMemoryManagedBotIntentStore;
import io.github.dev_abdulhay.telegramauth.managedbots.InMemoryManagedBotStore;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotIntentFlow;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotIntentStore;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotService;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotTokenStore;
import io.github.dev_abdulhay.telegramauth.managedbots.TelegramManagedBotsAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedBotIntentsAutoConfigTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @TestConfiguration
    static class WithoutIntents {
        @Bean TelegramBotModule module() {
            return TelegramBotModule.builder("123:ABC", "manager_bot").build();
        }
        @Bean ManagedBotTokenStore store() { return new InMemoryManagedBotStore(); }
    }

    @TestConfiguration
    static class WithIntents {
        @Bean TelegramBotModule module() {
            return TelegramBotModule.builder("123:ABC", "manager_bot").build();
        }
        @Bean ManagedBotTokenStore store() { return new InMemoryManagedBotStore(); }
        @Bean ManagedBotIntentStore intents() { return new InMemoryManagedBotIntentStore(); }
    }

    private ApplicationContextRunner runner(Class<?> hostBeans) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TelegramManagedBotsAutoConfiguration.class))
                .withUserConfiguration(hostBeans)
                .withPropertyValues("telegram.managed-bots.enabled=true",
                        "telegram.managed-bots.encryption-key=" + KEY);
    }

    @Test
    void withoutAnIntentStoreTheFeatureIsSimplyAbsent() {
        runner(WithoutIntents.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(ManagedBotService.class);
            assertThat(ctx).doesNotHaveBean(ManagedBotIntentFlow.class);
            assertThatThrownBy(() -> ctx.getBean(ManagedBotService.class)
                    .createIntent("tenant_shop_bot", "Shop", "bot:1"))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void anIntentStoreBeanTurnsIntentsOnAndClaimsTheStartPayloadRoute() {
        runner(WithIntents.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(ManagedBotIntentFlow.class);
            assertThat(ctx.getBean(TelegramBotModule.class).getStartPayloadRoutes())
                    .containsOnlyKeys("mb_");
            assertThat(ctx.getBean(ManagedBotService.class)
                    .createIntent("tenant_shop_bot", "Shop", "bot:1").url())
                    .startsWith("https://t.me/manager_bot?start=mb_");
        });
    }

    @Test
    void theIntentTtlAndRetentionAreBindable() {
        runner(WithIntents.class)
                .withPropertyValues("telegram.managed-bots.intent-ttl=5m",
                        "telegram.managed-bots.intent-retention=2d")
                .run(ctx -> assertThat(ctx.getBean(ManagedBotService.class)
                        .createIntent("tenant_shop_bot", "Shop", "bot:1").expiresAt())
                        .isBefore(java.time.OffsetDateTime.now().plusMinutes(6)));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -Dtest=ManagedBotIntentsAutoConfigTest test`
Expected: failures — no `ManagedBotIntentFlow` bean, `intent-ttl` unbound.

- [ ] **Step 3: Add the properties**

```java
    /**
     * How long an {@code OPEN} intent stays claimable. A {@code CLAIMED} intent does
     * not expire: its owner is proven and the resolution screen needs it to survive.
     */
    private Duration intentTtl = Duration.ofMinutes(30);

    /** How long closed intents (expired, cancelled) are kept before the purge removes them. */
    private Duration intentRetention = Duration.ofDays(7);

    public Duration getIntentTtl() { return intentTtl; }
    public void setIntentTtl(Duration intentTtl) { this.intentTtl = intentTtl; }
    public Duration getIntentRetention() { return intentRetention; }
    public void setIntentRetention(Duration intentRetention) { this.intentRetention = intentRetention; }
```

- [ ] **Step 4: Wire the beans**

```java
    /**
     * The intent store is optional and host-supplied: an {@link ObjectProvider} keeps
     * the whole feature opt-in without a second auto-configuration class.
     */
    @Bean
    @ConditionalOnMissingBean
    public ManagedBotService managedBotService(TelegramBotModule module, ManagedBotTokenStore store,
                                               TokenEncryptor encryptor, ManagedBotEvents events,
                                               TelegramManagedBotsProperties properties,
                                               ObjectProvider<ManagedBotIntentStore> intentStore) {
        return new ManagedBotService(module, store, encryptor, events,
                properties.getTokenFetchRetries(), properties.getTokenFetchBackoff(),
                intentStore.getIfAvailable(), properties.getIntentTtl(), properties.getIntentRetention());
    }

    /** Claims the {@code mb_} start-payload route. Absent when the host configures no intent store. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ManagedBotIntentStore.class)
    public ManagedBotIntentFlow managedBotIntentFlow(TelegramBotModule module, ManagedBotService service) {
        return new ManagedBotIntentFlow(module, service);
    }
```

- [ ] **Step 5: Delete the dead message bundles**

```bash
git rm src/main/resources/messages_tgauth.properties \
       src/main/resources/messages_tgauth_ru.properties \
       src/main/resources/messages_tgauth_en.properties
```

Nothing reads them: there is no `MessageSource` or `ResourceBundle` anywhere in `src/`, and Spring's default basename is `messages`, not `messages_tgauth`. Bot texts live in `FlowMessages`.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `mvn -q test`
Expected: PASS, whole suite.

- [ ] **Step 7: Commit**

```bash
git add -A src/main src/test
git commit -m "feat(managed-bots): auto-configure intents when a store bean is present"
```

---

### Task 10: White-label bridge forwards the intent events

**Files:**
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/whitelabel/TenantBotEventBridge.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/whitelabel/TenantBotEventBridgeTest.java` (extend)

**Interfaces:**
- Consumes: Task 4's four `ManagedBotEvents` methods.
- Produces: nothing new; the bridge simply forwards.

- [ ] **Step 1: Write the failing test**

Follow the file's existing fixtures (it already builds a bridge with a recording host delegate). Add:

```java
    @Test
    void theFourIntentEventsReachTheHostExactlyOnce() {
        // build the bridge the same way the existing tests in this class do,
        // with a recording host delegate and the bridge itself among the candidates
        ManagedBot bot = new ManagedBot(555L, "tenant_bot", "Tenant", 7L, "enc", NOW, NOW);
        ManagedBotIntent intent = new ManagedBotIntent("i1", "tenant_bot", "Tenant", "bot:1", 7L,
                ManagedBotIntentStatus.CLAIMED, null, NOW, NOW, null, NOW.plusMinutes(30));

        bridge.onIntentClaimed(intent);
        bridge.onIntentMatched(bot, intent);
        bridge.onIntentUnmatched(bot, List.of(intent));
        bridge.onIntentAmbiguous(intent, List.of(bot));

        assertThat(host.events).containsExactly(
                "claimed:i1", "matched:555:i1", "unmatched:555:1", "ambiguous:i1:1");
    }

    @Test
    void aHostDelegateThrowingOnAnIntentEventDoesNotEscape() {
        // same construction, host delegate throws on onIntentClaimed
        ManagedBotIntent intent = /* as above */;

        bridge.onIntentClaimed(intent);   // must not throw

        assertThat(registryCalls).isEmpty();   // no registry work belongs to intent events
    }
```

Adapt names to the fixtures already in the file rather than inventing new ones.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -Dtest=TenantBotEventBridgeTest test`
Expected: the host delegate records nothing — the bridge inherits the no-op defaults.

- [ ] **Step 3: Forward the events**

```java
    /**
     * Intent events drive no registry work — a bot starts on {@code onCreated}, not on
     * the bookkeeping around it — so these only forward. Without them a white-label
     * host would never see intents at all: this bridge is the {@code ManagedBotEvents}
     * bean the service is wired with.
     */
    @Override
    public void onIntentClaimed(ManagedBotIntent intent) {
        forward("onIntentClaimed", 0L, d -> d.onIntentClaimed(intent));
    }

    @Override
    public void onIntentMatched(ManagedBot bot, ManagedBotIntent intent) {
        forward("onIntentMatched", bot.botUserId(), d -> d.onIntentMatched(bot, intent));
    }

    @Override
    public void onIntentUnmatched(ManagedBot bot, List<ManagedBotIntent> candidates) {
        forward("onIntentUnmatched", bot.botUserId(), d -> d.onIntentUnmatched(bot, candidates));
    }

    @Override
    public void onIntentAmbiguous(ManagedBotIntent intent, List<ManagedBot> candidates) {
        forward("onIntentAmbiguous", 0L, d -> d.onIntentAmbiguous(intent, candidates));
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/dev_abdulhay/telegramauth/whitelabel src/test/java/io/github/dev_abdulhay/telegramauth/whitelabel
git commit -m "feat(white-label): forward managed-bot intent events to host listeners"
```

---

### Task 11: End-to-end intent flow test

**Files:**
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotIntentEndToEndTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1–10. Produces nothing.

This task is a gate, not a feature: it proves the pieces compose through the dispatcher, exactly as a deployment would run them.

- [ ] **Step 1: Write the test**

```java
package io.github.dev_abdulhay.telegramauth.managedbots;

// imports as in ManagedBotIntentFlowTest, plus BotUpdateDispatcher

class ManagedBotIntentEndToEndTest {

    @Test
    void fromIntentLinkToAMatchedBotThroughTheDispatcher() throws Exception {
        // 1. a module with: the managed_bot handler, the intent flow, and a /start login handler
        // 2. createIntent -> url
        // 3. dispatch a getUpdates batch carrying "/start mb_<id>" -> the bot replies with the button
        // 4. dispatch a managed_bot update for a bot with an EDITED username
        // 5. assert: the intent is COMPLETED with that bot, the host saw onIntentClaimed then
        //    onIntentMatched, and the /start login handler never ran
    }

    @Test
    void aLoginTokenBeginningWithTheIntentPrefixStillLogsIn() throws Exception {
        // dispatch "/start mb_ThisIsALoginTokenNotAnIntent" through the same wiring and assert
        // the login handler received it and the bot said nothing
    }
}
```

Write both tests out in full using the fixtures from `ManagedBotIntentFlowTest` (`RecordingBot`) and `ManagedBotServiceTest` (`managedBotUpdate` JSON shape), wiring the dispatcher as `BotUpdateDispatcherTest` does. The dispatcher runs handlers on `Runnable::run` by default, so assertions need no waiting.

- [ ] **Step 2: Run it**

Run: `mvn -q -Dtest=ManagedBotIntentEndToEndTest test`
Expected: PASS. A failure here means a wiring gap, not a test bug — fix the wiring.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/io/github/dev_abdulhay/telegramauth/managedbots/ManagedBotIntentEndToEndTest.java
git commit -m "test(managed-bots): cover the intent flow end to end through the dispatcher"
```

---

### Task 12: Host-account linking — `hostRef` on the session

**Files:**
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/entity/BaseAuthSession.java`
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/service/AbstractSessionService.java`
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/api/dto/AuthContext.java`
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/web/AbstractTelegramAuthController.java`
- Test: `src/test/java/com/example/demo/SessionServiceTest.java` (extend)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `BaseAuthSession.getHostRef()/setHostRef(String)`, `AbstractSessionService.create(String, String, String)`, `AuthContext(String, String, String)` + `getHostRef()`, `AbstractTelegramAuthController.hostRef(HttpServletRequest)`.

- [ ] **Step 1: Write the failing tests**

Append to `SessionServiceTest`:

```java
    @Test
    void aSessionCarriesTheHostRefIntoTheApproveHandler() {
        AtomicReference<String> seen = new AtomicReference<>();
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "demo_bot")
                .bot(new TelegramBot(HttpClient.newHttpClient(), "x") {
                    @Override public void sendMessage(long chatId, String text) { }
                })
                .approveHandler((info, ctx) -> {
                    seen.set(ctx.getHostRef());
                    return new AuthApproveResult(Map.of());
                })
                .build();
        DemoSessionService svc = new DemoSessionService(new StubSessionRepo(), new TokenGenerator(), module);
        var created = svc.create("1.2.3.4", "JUnit", "link:admin:7");

        DemoUser u = new DemoUser();
        u.setTelegramId(99L);
        svc.approve(svc.hash(created.rawToken()), u);

        assertThat(created.entity().getHostRef()).isEqualTo("link:admin:7");
        assertThat(seen.get()).isEqualTo("link:admin:7");
    }

    @Test
    void anOrdinaryLoginHasNoHostRef() {
        AtomicReference<String> seen = new AtomicReference<>("unset");
        // build the module as above, then:
        var created = svc.create("1.2.3.4", "JUnit");

        assertThat(created.entity().getHostRef()).isNull();
        // after approve:
        assertThat(seen.get()).isNull();
    }

    @Test
    void anOversizedHostRefIsRejectedBeforeItReachesTheDatabase() {
        DemoSessionService svc = new DemoSessionService(new StubSessionRepo(), new TokenGenerator(), module());

        assertThatThrownBy(() -> svc.create("1.2.3.4", "JUnit", "x".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTwoArgumentAuthContextStillWorks() {
        AuthContext ctx = new AuthContext("1.2.3.4", "JUnit");

        assertThat(ctx.getHostRef()).isNull();
        assertThat(ctx.getIpAddress()).isEqualTo("1.2.3.4");
    }
```

Write the second test out fully rather than leaving the comments — they are shown here only to keep the plan readable.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -Dtest=SessionServiceTest test`
Expected: compilation failure — `getHostRef` and the three-argument `create` do not exist.

- [ ] **Step 3: Add the column**

In `BaseAuthSession`, next to `userAgent`:

```java
    /**
     * Opaque host correlation key, set by host code at session creation and handed
     * back in {@code AuthContext} on approval — "this session is admin 7 linking
     * their Telegram", not just "someone is logging in".
     *
     * <p>Never accepted from the client: it names <em>whose</em> account the approval
     * may touch.
     */
    @Column(name = "host_ref", length = 128)
    private String hostRef;

    public String getHostRef() { return hostRef; }
    public void setHostRef(String hostRef) { this.hostRef = hostRef; }
```

- [ ] **Step 4: Carry it through the service**

```java
    public CreatedSession create(String ipAddress, String userAgent) {
        return create(ipAddress, userAgent, null);
    }

    /**
     * @param hostRef opaque, at most 128 characters, never interpreted here. Set it
     *                from server-side state only — it decides what an approval is
     *                allowed to mean.
     * @throws IllegalArgumentException when {@code hostRef} is longer than 128 characters
     */
    @Transactional
    public CreatedSession create(String ipAddress, String userAgent, String hostRef) {
        if (hostRef != null && hostRef.length() > MAX_HOST_REF) {
            throw new IllegalArgumentException("hostRef must be at most " + MAX_HOST_REF
                    + " characters but was " + hostRef.length());
        }
        // ... existing body ...
        s.setHostRef(hostRef);
        // ...
    }
```

with `private static final int MAX_HOST_REF = 128;`, and in `approve`:

```java
        AuthContext ctx = new AuthContext(s.getIpAddress(), s.getUserAgent(), s.getHostRef());
```

- [ ] **Step 5: Extend `AuthContext`**

Add the field, the three-argument constructor, keep the two-argument one delegating with `null`, add `getHostRef()`, and fix the class javadoc — it currently promises an `AuthContextEnricher` type that exists nowhere in the repository:

```java
/**
 * Mutable context object passed into the host approve handler. Carries the
 * request-side details captured at session creation — IP, user agent and the
 * host's own {@code hostRef} — plus a free-form attribute map a host can decorate
 * before its handler reads it.
 */
```

- [ ] **Step 6: Give the controller an override point**

```java
    @PostMapping("/session")
    public CreateSessionResponse create(@RequestBody(required = false) CreateSessionRequest body,
                                        HttpServletRequest req) {
        String ip = clientIp(req);
        String ua = req.getHeader("User-Agent");
        AbstractSessionService.CreatedSession created = sessionService.create(ip, ua, hostRef(req));
        // ... unchanged ...
    }

    /**
     * What this session is for, in the host's own terms — returned to the host's
     * approve handler as {@code AuthContext#getHostRef()}. {@code null} means an
     * ordinary login.
     *
     * <p>Derive it from server-side state only (an authenticated platform session,
     * a signed cookie). It is deliberately <b>not</b> read from the request body:
     * a client that could set it could point an approval at someone else's account.
     */
    protected String hostRef(HttpServletRequest request) {
        return null;
    }
```

`CreateSessionRequest` stays exactly as it is.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `mvn -q test`
Expected: PASS, whole suite. `JpaLayerTest` exercises the session table on H2 with `ddl-auto=create-drop`, so the new column needs no test fixture change.

- [ ] **Step 8: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat(auth): carry an opaque hostRef from session creation into the approve handler"
```

---

### Task 13: Documentation and release metadata for 0.5.0

**Files:**
- Modify: `pom.xml` (version `0.4.0` → `0.5.0`)
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Modify: `tasks/tech-doc/TECH_DOC.md`

**Interfaces:**
- Consumes: every earlier task — the documentation must describe what was actually built. Verify each snippet against the code before writing it; the project's CLAUDE.md forbids guessed signatures.

- [ ] **Step 1: Bump the version**

`pom.xml` line 10: `<version>0.5.0</version>`. Run `mvn -q -DskipTests package` to confirm the build still resolves.

- [ ] **Step 2: Write the README's Intents section**

Insert `### Intents` into `## Managed bots`, after `### Minimal usage`. Cover, with snippets copied from the real code:
1. The problem in two sentences (an edited username breaks username-based correlation).
2. The host-supplied store: `ManagedBotIntentStore` bean, `BaseManagedBotIntent` entity, `JpaManagedBotIntentStore` wiring, and the PostgreSQL DDL from the spec §8 — including both indexes and the partial unique index on `bot_user_id`.
3. The flow: `createIntent` → link → `/start mb_<id>` → `onIntentClaimed` → bot created → `onIntentMatched`.
4. The four events, with signatures.
5. The resolution screen: `findUnassignedBots`, `assignToIntent`, `decommissionUnassigned` — **with the owner-scoped caveat** (the list holds every unassigned bot of that Telegram user, including ones created for something else; gate the screen behind the host's own permission check).
6. `telegram.managed-bots.intent-ttl` and `intent-retention` in the configuration reference table.

- [ ] **Step 3: Write the README's linking section**

A new `## Linking a Telegram account to a host account` section after `## Managed bots`, covering spec §11: the `hostRef` contract with `create(ip, ua, hostRef)` and `ctx.getHostRef()`, the **server-side-only** rule, the `hostRef(HttpServletRequest)` override, the self-service auto-link rule and the bearer-link caveat, the refuse-a-rebind rule, and the manager-bot vs tenant-bot composition.

- [ ] **Step 4: Update `## Install`, `## Upgrading`, roadmap**

- `## Install`: `<version>0.5.0</version>` in both the Maven and Gradle snippets.
- New `## Upgrading to 0.5.0` section immediately before `## Upgrading to 0.4.0`, whose first item is the forced migration:

  ```sql
  ALTER TABLE auth_session ADD COLUMN host_ref VARCHAR(128);
  ```

  stating that every host must apply it whether or not they use linking, because Hibernate schema validation fails otherwise. Then: the optional intent table, the new `ManagedBotEvents` defaults (no action needed), and the behaviour change that managed-bot listener exceptions are now logged instead of propagating.
- `## Status & roadmap`: add `- [x] Managed-bot intents ... ` and `- [x] Host-account linking ...` lines.

- [ ] **Step 5: Write the CHANGELOG entry**

Keep-a-Changelog `## [0.5.0] - <today>` with:
- **Added** — intents (types, stores, service methods, events, `/start` routing, flow, properties), `hostRef` on sessions and `AuthContext`, the controller override point.
- **Changed** — managed-bot event publishing is guarded per listener; `AuthContext` javadoc corrected.
- **Removed** — the three dead `messages_tgauth*.properties` files (nothing read them; bot texts live in `FlowMessages`).
- **Migration** — the `host_ref` column, and the optional intent table.

- [ ] **Step 6: Update TECH_DOC**

In `tasks/tech-doc/TECH_DOC.md`: add the new types to the structure map, and fix line ~612, which claims bot texts are `messages_tgauth*.properties` resolved through Spring's `MessageSource` — they are not and never were; they are `FlowMessages` plus a `msg(Key, lang)` override.

- [ ] **Step 7: Verify and commit**

Run: `mvn -q test` one final time, then:

```bash
git add pom.xml README.md CHANGELOG.md tasks/tech-doc/TECH_DOC.md
git commit -m "docs: document managed-bot intents and host-account linking for 0.5.0"
```

Do **not** tag and do **not** push: pushing a `v0.5.0` tag triggers the release workflow and publishes to Maven Central irreversibly. That is the user's call, in a separate step.

---

## Self-review notes

- **Spec coverage.** §3–§5 → Tasks 1–9; §5.4 events → Task 4; §5.5 routing → Tasks 7–8; §5.7 messages → Tasks 8–9; §6 resolution → Task 6; §7 error table → Tasks 3, 5, 6, 7; §8 release → Task 13; §9 tests → distributed across Tasks 1–11; §10 is host work, out of scope here; §11 → Task 12 (§11.5 needs no code, it is documentation in Task 13).
- **Deliberately deferred:** nothing. If a §9 bullet has no test after Task 13, that is a gap to report, not to skip quietly.
- **Type consistency:** `ManagedBotIntent.START_PREFIX` is the only definition of `"mb_"`; `ManagedBotIntentException.Reason` is the only failure enum; `IntentClaim` is only ever returned inside `IntentClaimResult`; `publish(String, Runnable)` is the only guard wrapper in `ManagedBotService`.
