# Login tasdiqlash kodi (number matching) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a second confirmation factor to the Telegram login flow — a 2-digit number shown in the browser that the user must select (or type) in the bot — so approving a link is no longer enough to hand an attacker a session.

**Architecture:** A new non-terminal session status `AWAITING_CODE` sits between `PENDING` and the terminal states. The code is derived deterministically from `tokenHash` (never stored, no migration) through a pluggable `ConfirmCodeGenerator` that both the flow and the controller read, so the two sides can never disagree. `DefaultAuthFlow` gains a code stage plus a per-Telegram-user cooldown tracker; the browser learns the code through `GET /session/{token}/poll?since=PENDING`.

**Tech Stack:** Java 17, Maven, Spring Boot 3 (JPA, Web), JUnit 5, AssertJ, H2 (tests).

**Spec:** `docs/superpowers/specs/2026-08-17-login-confirmation-code-design.md`

## Global Constraints

- Target release **`0.4.0`** (breaking). `pom.xml` version bump happens in the last task, not before.
- Java **17** source/target. No new runtime dependencies.
- Build output is redirected by a hook: run Maven through
  `ctx_execute(language: "shell", code: "cd <repo> && mvn -B test 2>&1 | tail -40")`
  (or `| grep -E 'Tests run|ERROR|FAIL'`). Do not paste raw build logs.
- **All 38 existing tests must stay green after every task.** Full suite: `mvn -B test`.
- Structural renames / signature changes (Task 2) go through the **`jetbrains-refactor`** skill
  (`rename_refactoring` + `build_project`), never text find-replace.
- Commit messages: Conventional Commits, English, **no AI fingerprint** —
  no `Co-Authored-By`, no "Generated with", no Claude/Anthropic reference. This overrides
  any default harness git convention.
- README.md + CHANGELOG.md updates are mandatory for functional changes (project CLAUDE.md);
  they are folded into Task 14. Every README snippet must be verified against real code.
- Code style: match the surrounding files — javadoc on every public/protected member that
  carries a non-obvious contract, comments explain *why* not *what*.

---

## File Structure

**Create (main):**

| File | Responsibility |
|---|---|
| `security/ConfirmCodeGenerator.java` | Strategy interface: `tokenHash → int` |
| `security/ConfirmCode.java` | Default generator (`hash[0..4] as hex % 100`) |
| `flow/CodeConfirmation.java` | `BUTTON \| TYPED \| OFF` enum |
| `flow/CodeStrikeTracker.java` | Per-user wrong-code strikes + escalating cooldown state machine |

**Modify (main):** `entity/BaseAuthSession.java` · `repository/BaseAuthSessionRepository.java` ·
`service/AbstractSessionService.java` · `service/AuthEvent.java` · `service/AuthEventBus.java` ·
`service/InMemoryAuthEventBus.java` · `web/dto/WaitResponse.java` ·
`web/AbstractTelegramAuthController.java` · `bot/TelegramBotModule.java` ·
`bot/BotUpdateDispatcher.java` · `flow/DefaultAuthFlow.java` · `flow/FlowMessages.java` ·
`config/TelegramAuthProperties.java` · `config/TelegramAuthAutoConfiguration.java` · `pom.xml`

**Create (test):** `security/ConfirmCodeTest.java` · `flow/CodeStrikeTrackerTest.java` ·
`com/example/demo/CodeConfirmationFlowTest.java` · `com/example/demo/FlowOptionsBindingTest.java`

**Modify (test):** `StubSessionRepo.java` · `JpaLayerTest.java` · `DefaultAuthFlowTest.java` ·
`DefaultAuthFlowOptionsTest.java` · `DemoTgConfig.java` · `ControllerFlowTest.java`

`DefaultAuthFlow` is already 489 lines and this feature adds roughly 150 more. The cooldown
state machine is therefore extracted into `CodeStrikeTracker` — it is a self-contained,
independently testable unit, and keeping it out of the flow stops the file from growing a
second in-memory map's worth of purge/ceiling bookkeeping.

---

## Task 1: Confirmation code generator

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/security/ConfirmCodeGenerator.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/security/ConfirmCode.java`
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotModule.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/security/ConfirmCodeTest.java`

**Interfaces:**
- Consumes: nothing (first task).
- Produces:
  - `ConfirmCodeGenerator.codeFor(String tokenHash) -> int`
  - `ConfirmCode.of(String tokenHash) -> int` (static)
  - `TelegramBotModule.getConfirmCodeGenerator() -> ConfirmCodeGenerator`
  - `TelegramBotModule.Builder.confirmCodeGenerator(ConfirmCodeGenerator) -> Builder`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/github/dev_abdulhay/telegramauth/security/ConfirmCodeTest.java`:

```java
package io.github.dev_abdulhay.telegramauth.security;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmCodeTest {

    private static String hashStartingWith(String fourHexChars) {
        return fourHexChars + "0".repeat(60);
    }

    @Test
    void derivesTheCodeFromTheFirstFourHexCharsOfTheHash() {
        assertThat(ConfirmCode.of(hashStartingWith("002a"))).isEqualTo(42);   // 0x002a = 42
        assertThat(ConfirmCode.of(hashStartingWith("ffff"))).isEqualTo(35);   // 0xffff = 65535
        assertThat(ConfirmCode.of(hashStartingWith("0000"))).isEqualTo(0);
    }

    @Test
    void isDeterministicAndAlwaysTwoDigits() {
        TokenGenerator tg = new TokenGenerator();
        for (int i = 0; i < 500; i++) {
            String hash = tg.hash(tg.newToken());
            int code = ConfirmCode.of(hash);
            assertThat(code).isBetween(0, 99);
            assertThat(ConfirmCode.of(hash)).isEqualTo(code);
        }
    }

    @Test
    void moduleUsesTheDefaultGeneratorUnlessOverridden() {
        TelegramBotModule def = TelegramBotModule.builder("123:ABC", "demo_bot").build();
        assertThat(def.getConfirmCodeGenerator().codeFor(hashStartingWith("002a"))).isEqualTo(42);

        TelegramBotModule custom = TelegramBotModule.builder("123:ABC", "demo_bot")
                .confirmCodeGenerator(hash -> 7)
                .build();
        assertThat(custom.getConfirmCodeGenerator().codeFor(hashStartingWith("002a"))).isEqualTo(7);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
ctx_execute(shell): cd <repo> && mvn -B test -Dtest=ConfirmCodeTest 2>&1 | grep -E "ERROR|cannot find symbol|Tests run" | head -20
```
Expected: COMPILATION ERROR — `ConfirmCode` and `confirmCodeGenerator` do not exist.

- [ ] **Step 3: Create `ConfirmCodeGenerator`**

```java
package io.github.dev_abdulhay.telegramauth.security;

/**
 * Derives the browser-visible confirmation code from a session's token hash.
 *
 * <p><b>Must be a pure function of {@code tokenHash}.</b> The bot flow and the
 * REST controller each derive the code independently and never exchange it, so
 * an implementation that is random, stateful, or time-dependent makes the two
 * sides disagree and every login fails.
 *
 * <p>The code is not a secret: anyone holding the deep link holds the raw token
 * and can compute it. Its job is to prove that whoever confirms the login is
 * looking at the browser screen that started it.
 */
@FunctionalInterface
public interface ConfirmCodeGenerator {

    /**
     * @param tokenHash the session's SHA-256 token hash (64 lowercase hex chars)
     * @return the code to show in the browser and ask for in the bot
     */
    int codeFor(String tokenHash);
}
```

- [ ] **Step 4: Create `ConfirmCode`**

```java
package io.github.dev_abdulhay.telegramauth.security;

/**
 * Default {@link ConfirmCodeGenerator}: the first two bytes of the token hash,
 * reduced to two decimal digits. Deterministic, needs no storage and no schema
 * change — both sides recompute it from the row they already have.
 */
public final class ConfirmCode implements ConfirmCodeGenerator {

    @Override
    public int codeFor(String tokenHash) {
        return of(tokenHash);
    }

    /** @return a value in {@code 0..99} */
    public static int of(String tokenHash) {
        return Integer.parseInt(tokenHash.substring(0, 4), 16) % 100;
    }
}
```

- [ ] **Step 5: Wire the generator into `TelegramBotModule`**

In `TelegramBotModule`, add the import `io.github.dev_abdulhay.telegramauth.security.ConfirmCodeGenerator`
and `io.github.dev_abdulhay.telegramauth.security.ConfirmCode`, then:

- field, next to `bus`: `private final ConfirmCodeGenerator confirmCodeGenerator;`
- in the private constructor, next to `this.bus = ...`:
  ```java
  this.confirmCodeGenerator = (b.confirmCodeGenerator != null) ? b.confirmCodeGenerator : new ConfirmCode();
  ```
- getter, next to `getBus()`:
  ```java
  public ConfirmCodeGenerator getConfirmCodeGenerator() { return confirmCodeGenerator; }
  ```
- Builder field: `private ConfirmCodeGenerator confirmCodeGenerator;`
- Builder method, next to `eventBus(...)`:
  ```java
  /**
   * Replaces the default two-digit confirmation-code scheme. The implementation
   * must be a pure function of the token hash — see {@link ConfirmCodeGenerator}.
   */
  public Builder confirmCodeGenerator(ConfirmCodeGenerator v) { this.confirmCodeGenerator = v; return this; }
  ```

- [ ] **Step 6: Run test to verify it passes**

```
mvn -B test -Dtest=ConfirmCodeTest 2>&1 | grep -E "Tests run|ERROR" | head
```
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 7: Run the full suite**

```
mvn -B test 2>&1 | grep -E "Tests run:|BUILD" | tail -5
```
Expected: BUILD SUCCESS, 41 tests.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/github/dev_abdulhay/telegramauth/security/ConfirmCodeGenerator.java \
        src/main/java/io/github/dev_abdulhay/telegramauth/security/ConfirmCode.java \
        src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotModule.java \
        src/test/java/io/github/dev_abdulhay/telegramauth/security/ConfirmCodeTest.java
git commit -m "feat(security): add pluggable ConfirmCodeGenerator with deterministic default"
```

---

## Task 2: `AWAITING_CODE` status and status-set repository queries

**Files:**
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/entity/BaseAuthSession.java:34`
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/repository/BaseAuthSessionRepository.java:32,35`
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/service/AbstractSessionService.java:75,166`
- Modify: `src/test/java/com/example/demo/StubSessionRepo.java`
- Test: `src/test/java/com/example/demo/JpaLayerTest.java`, `src/test/java/com/example/demo/DefaultAuthFlowOptionsTest.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces:
  - `BaseAuthSession.Status.AWAITING_CODE`
  - `BaseAuthSessionRepository.findByStatusInAndExpiresAtBefore(Collection<Status>, OffsetDateTime) -> List<S>`
  - `BaseAuthSessionRepository.countByIpAddressAndStatusInAndExpiresAtAfter(String, Collection<Status>, OffsetDateTime) -> long`
  - `AbstractSessionService.LIVE_STATUSES` (`List.of(PENDING, AWAITING_CODE)`, `protected static final`)

- [ ] **Step 1: Write the failing tests**

Append to `src/test/java/com/example/demo/DefaultAuthFlowOptionsTest.java` (inside the class,
after `overduePendingSessionsDoNotHoldTheIpLimit`):

```java
    @Test
    void awaitingCodeSessionsCountTowardTheIpLimit() {
        RecordingBot bot = new RecordingBot();
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "demo_bot")
                .bot(bot)
                .maxPendingPerIp(1)
                .build();
        StubSessionRepo repo = new StubSessionRepo();
        DemoSessionService sessions = new DemoSessionService(repo, new TokenGenerator(), module);

        var first = sessions.create("9.9.9.9", "ua");
        ((BaseAuthSession) first.entity()).setStatus(BaseAuthSession.Status.AWAITING_CODE);

        // half-finished logins must still hold their slot, otherwise the limit is trivially bypassed
        assertThatThrownBy(() -> sessions.create("9.9.9.9", "ua"))
                .isInstanceOf(SessionRateLimitException.class);
    }

    @Test
    void sweepExpiresOverdueAwaitingCodeSessions() {
        RecordingBot bot = new RecordingBot();
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "demo_bot").bot(bot).build();
        StubSessionRepo repo = new StubSessionRepo();
        DemoSessionService sessions = new DemoSessionService(repo, new TokenGenerator(), module);

        DemoSession stuck = new DemoSession();
        stuck.setTokenHash("h-awaiting");
        stuck.setStatus(BaseAuthSession.Status.AWAITING_CODE);
        stuck.setCreatedAt(OffsetDateTime.now().minusMinutes(10));
        stuck.setExpiresAt(OffsetDateTime.now().minusMinutes(5));
        repo.save(stuck);

        sessions.sweepExpired();

        assertThat(repo.findByTokenHash("h-awaiting").orElseThrow().getStatus())
                .isEqualTo(BaseAuthSession.Status.EXPIRED);
    }
```

In `src/test/java/com/example/demo/JpaLayerTest.java`, replace the query call inside
`sessionFindsByTokenHashAndByExpiry`:

```java
        List<DemoSession> overdue = sessions.findByStatusInAndExpiresAtBefore(
                List.of(BaseAuthSession.Status.PENDING, BaseAuthSession.Status.AWAITING_CODE),
                OffsetDateTime.now());
        assertThat(overdue).hasSize(1);
```

- [ ] **Step 2: Run tests to verify they fail**

```
mvn -B test -Dtest='DefaultAuthFlowOptionsTest+JpaLayerTest' 2>&1 | grep -E "ERROR|cannot find symbol|Tests run" | head -20
```
Expected: COMPILATION ERROR — `AWAITING_CODE` and `findByStatusInAndExpiresAtBefore` do not exist.

- [ ] **Step 3: Add the status**

`BaseAuthSession.java:34`:

```java
    /**
     * {@code AWAITING_CODE} is <b>not</b> terminal: the user confirmed the login
     * but still owes the browser-visible confirmation code. It holds its per-IP
     * rate-limit slot and is swept to {@code EXPIRED} like {@code PENDING}.
     */
    public enum Status { PENDING, AWAITING_CODE, APPROVED, REJECTED, EXPIRED }
```

The column is `length = 20` and `EnumType.STRING`; `AWAITING_CODE` is 13 chars — **no schema
change and no migration**.

- [ ] **Step 4: Widen the two repository queries**

Use the **`jetbrains-refactor` skill** (`rename_refactoring`, then `build_project` to verify)
for both renames — the derived-query names are part of Spring Data's contract and a missed
call site only fails at runtime.

`BaseAuthSessionRepository.java`:

```java
    /** Live sessions in any of the given statuses whose deadline has passed. */
    List<S> findByStatusInAndExpiresAtBefore(Collection<BaseAuthSession.Status> statuses, OffsetDateTime time);

    /**
     * Live sessions for an IP in any of the given statuses; overdue rows are excluded
     * so a stale batch cannot lock the IP out. Both {@code PENDING} and
     * {@code AWAITING_CODE} count — a half-finished login still occupies a slot.
     */
    long countByIpAddressAndStatusInAndExpiresAtAfter(String ipAddress,
                                                      Collection<BaseAuthSession.Status> statuses,
                                                      OffsetDateTime time);
```

- [ ] **Step 5: Update the two call sites in `AbstractSessionService`**

Add next to `TERMINAL_STATUSES` (line 38):

```java
    /**
     * Statuses a login can still be completed from. {@code TERMINAL_STATUSES} is
     * deliberately NOT extended with {@code AWAITING_CODE}: it drives the
     * retention purge, and a live session must never be deleted. The sweeper
     * moves overdue {@code AWAITING_CODE} rows to {@code EXPIRED} first, and the
     * purge picks them up from there.
     */
    protected static final List<Status> LIVE_STATUSES = List.of(Status.PENDING, Status.AWAITING_CODE);
```

In `create(...)` (line 75):

```java
                && sessionRepo.countByIpAddressAndStatusInAndExpiresAtAfter(
                        ipAddress, LIVE_STATUSES, OffsetDateTime.now()) >= limit) {
```

In `sweepExpired()` (line 166):

```java
        List<S> overdue = sessionRepo.findByStatusInAndExpiresAtBefore(LIVE_STATUSES, OffsetDateTime.now());
```

Also update the `sweepExpired` javadoc first sentence to
*"Marks overdue live (PENDING / AWAITING_CODE) sessions as EXPIRED …"*.

- [ ] **Step 6: Update `StubSessionRepo`**

Replace the two overrides:

```java
    @Override public List<DemoSession> findByStatusInAndExpiresAtBefore(
            java.util.Collection<BaseAuthSession.Status> statuses, OffsetDateTime time) {
        return store.values().stream()
                .filter(s -> statuses.contains(s.getStatus())
                        && s.getExpiresAt() != null && s.getExpiresAt().isBefore(time))
                .toList();
    }
    @Override public long countByIpAddressAndStatusInAndExpiresAtAfter(
            String ipAddress, java.util.Collection<BaseAuthSession.Status> statuses, OffsetDateTime time) {
        return store.values().stream()
                .filter(s -> ipAddress.equals(s.getIpAddress()) && statuses.contains(s.getStatus())
                        && s.getExpiresAt() != null && s.getExpiresAt().isAfter(time))
                .count();
    }
```

- [ ] **Step 7: Run tests to verify they pass**

```
mvn -B test 2>&1 | grep -E "Tests run:|BUILD" | tail -5
```
Expected: BUILD SUCCESS, 43 tests.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/github/dev_abdulhay/telegramauth/entity/BaseAuthSession.java \
        src/main/java/io/github/dev_abdulhay/telegramauth/repository/BaseAuthSessionRepository.java \
        src/main/java/io/github/dev_abdulhay/telegramauth/service/AbstractSessionService.java \
        src/test/java/com/example/demo/StubSessionRepo.java \
        src/test/java/com/example/demo/JpaLayerTest.java \
        src/test/java/com/example/demo/DefaultAuthFlowOptionsTest.java
git commit -m "feat(session): add non-terminal AWAITING_CODE status to sweep and rate-limit queries"
```

---

## Task 3: `awaitCode()` transition and the `AWAITING_CODE` event

**Files:**
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/service/AuthEvent.java`
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/service/AuthEventBus.java`
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/service/InMemoryAuthEventBus.java`
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/service/AbstractSessionService.java:109,146`
- Test: `src/test/java/com/example/demo/SessionServiceTest.java`

**Interfaces:**
- Consumes: `Status.AWAITING_CODE`, `LIVE_STATUSES` (Task 2).
- Produces:
  - `AuthEvent.Type.AWAITING_CODE`, `AuthEvent.awaitingCode() -> AuthEvent`
  - `AbstractSessionService.awaitCode(String tokenHash) -> boolean`
  - `approve(String, U)` and `reject(String)` now also accept a session in `AWAITING_CODE`

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/com/example/demo/SessionServiceTest.java`:

```java
    @Test
    void awaitCodeMovesPendingToAwaitingCodeWithoutCallingTheHostHandler() {
        AtomicReference<Boolean> handlerCalled = new AtomicReference<>(false);
        TelegramBot fake = new TelegramBot(HttpClient.newHttpClient(), "x") {
            @Override public void sendMessage(long chatId, String text) { }
        };
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "demo_bot")
                .bot(fake)
                .approveHandler((info, ctx) -> {
                    handlerCalled.set(true);
                    return new io.github.dev_abdulhay.telegramauth.api.dto.AuthApproveResult(Map.of());
                })
                .build();
        DemoSessionService svc = new DemoSessionService(new StubSessionRepo(), new TokenGenerator(), module);

        var created = svc.create("1.2.3.4", "JUnit");
        String hash = svc.hash(created.rawToken());

        AtomicReference<AuthEvent> got = new AtomicReference<>();
        module.getBus().subscribe(hash, got::set);

        assertThat(svc.awaitCode(hash)).isTrue();
        assertThat(((BaseAuthSession) created.entity()).getStatus())
                .isEqualTo(BaseAuthSession.Status.AWAITING_CODE);
        assertThat(got.get().type()).isEqualTo(AuthEvent.Type.AWAITING_CODE);
        // the host is told about a login only once, at final approval
        assertThat(handlerCalled.get()).isFalse();

        // not repeatable: the session is no longer PENDING
        assertThat(svc.awaitCode(hash)).isFalse();
    }

    @Test
    void approveAndRejectAlsoWorkFromAwaitingCode() {
        TelegramBotModule module = module();
        DemoSessionService svc = new DemoSessionService(new StubSessionRepo(), new TokenGenerator(), module);

        var a = svc.create("1.2.3.4", "JUnit");
        String hashA = svc.hash(a.rawToken());
        svc.awaitCode(hashA);
        DemoUser u = new DemoUser();
        u.setTelegramId(99L);
        assertThat(svc.approve(hashA, u)).isTrue();
        assertThat(((BaseAuthSession) a.entity()).getStatus()).isEqualTo(BaseAuthSession.Status.APPROVED);

        var b = svc.create("1.2.3.4", "JUnit");
        String hashB = svc.hash(b.rawToken());
        svc.awaitCode(hashB);
        assertThat(svc.reject(hashB)).isTrue();
        assertThat(((BaseAuthSession) b.entity()).getStatus()).isEqualTo(BaseAuthSession.Status.REJECTED);
    }
```

- [ ] **Step 2: Run test to verify it fails**

```
mvn -B test -Dtest=SessionServiceTest 2>&1 | grep -E "ERROR|cannot find symbol|Tests run" | head
```
Expected: COMPILATION ERROR — `awaitCode` does not exist.

- [ ] **Step 3: Extend `AuthEvent`**

```java
/**
 * Event published on the {@link AuthEventBus} when a session leaves the
 * {@code PENDING} state. {@code AWAITING_CODE} is the one non-terminal type:
 * the login advanced but has not finished.
 */
public record AuthEvent(Type type, Map<String, Object> payload) {

    public enum Type { AWAITING_CODE, APPROVED, REJECTED, EXPIRED }

    /** Non-terminal: the browser may now show its confirmation code. */
    public static AuthEvent awaitingCode() {
        return new AuthEvent(Type.AWAITING_CODE, Map.of());
    }
    ...
```

The code is deliberately **not** carried on the event: it is a pure function of the token
hash, which every subscriber already has.

- [ ] **Step 4: Correct the bus contracts**

`AuthEventBus` class javadoc:

```java
/**
 * Internal pub/sub used by wait endpoints. Dispatching an event removes the
 * listener from the registry, so each subscription observes exactly one event.
 * {@link AuthEvent.Type#AWAITING_CODE} is non-terminal: after it fires the
 * client re-subscribes on its next poll. The only implementation is
 * {@link InMemoryAuthEventBus}, for single-instance deployments.
 */
```

`InMemoryAuthEventBus` class javadoc: replace *"so a terminal event cannot be observed
twice"* with *"so one subscription observes exactly one event; a client that wants the next
one subscribes again"*. **No code change.**

- [ ] **Step 5: Add `awaitCode` and widen the two guards**

In `AbstractSessionService`, after `approve(...)`:

```java
    /**
     * Moves a PENDING, non-expired session to {@code AWAITING_CODE}: the user
     * confirmed the login but still owes the browser-visible confirmation code.
     *
     * <p>The host {@code approveHandler} is deliberately <b>not</b> called here —
     * it fires once, at {@link #approve(String, BaseTelegramUser)}, so a login
     * that dies at the code step leaves nothing behind.
     *
     * @return {@code true} if the session moved; {@code false} if it was missing,
     *         already past PENDING, or expired
     */
    @Transactional
    public boolean awaitCode(String tokenHash) {
        S s = sessionRepo.findWithLockByTokenHash(tokenHash).orElse(null);
        if (s == null || s.getStatus() != Status.PENDING) {
            log.debug("awaitCode: session not found or not pending");
            return false;
        }
        if (s.getExpiresAt().isBefore(OffsetDateTime.now())) {
            s.setStatus(Status.EXPIRED);
            sessionRepo.save(s);
            publishAfterCommit(tokenHash, AuthEvent.expired());
            return false;
        }
        s.setStatus(Status.AWAITING_CODE);
        sessionRepo.save(s);
        publishAfterCommit(tokenHash, AuthEvent.awaitingCode());
        return true;
    }
```

Guard in `approve` (line 111):

```java
        if (s == null || !LIVE_STATUSES.contains(s.getStatus())) {
            log.debug("approve: session not found or no longer live");
            return false;
        }
```

Guard in `reject` (line 148):

```java
        if (s == null || !LIVE_STATUSES.contains(s.getStatus())) return false;
```

Add to the `approve` javadoc:

```
     * <p>Accepts both {@code PENDING} and {@code AWAITING_CODE}. Ordering the
     * confirmation steps is the flow's job, not this layer's: a host calling
     * {@code approve} directly bypasses the code step by design.
```

- [ ] **Step 6: Run tests to verify they pass**

```
mvn -B test 2>&1 | grep -E "Tests run:|BUILD" | tail -5
```
Expected: BUILD SUCCESS, 45 tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/dev_abdulhay/telegramauth/service/ \
        src/test/java/com/example/demo/SessionServiceTest.java
git commit -m "feat(session): add awaitCode transition and non-terminal AWAITING_CODE event"
```

---

## Task 4: Poll delivers the code via `?since=PENDING`

**Files:**
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/web/dto/WaitResponse.java`
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/web/AbstractTelegramAuthController.java:73,117,127,140`
- Test: `src/test/java/com/example/demo/ControllerFlowTest.java`

**Interfaces:**
- Consumes: `awaitCode` (Task 3), `getConfirmCodeGenerator()` (Task 1).
- Produces:
  - `WaitResponse(String status, Map<String,Object> payload, Integer confirmCode)` + 2-arg convenience constructor
  - `GET /session/{token}/poll?since=PENDING` → `202` + `confirmCode`
  - `DELETE /session/{token}` cancels `AWAITING_CODE` too

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/com/example/demo/ControllerFlowTest.java`:

```java
    @Test
    void pollWithSincePendingReturnsTheConfirmationCodeOnceTheCodeStageStarts() throws Exception {
        MvcResult createRes = mvc.perform(post("/api/demo-auth/session"))
                .andExpect(status().isOk()).andReturn();
        String token = json.readTree(createRes.getResponse().getContentAsString()).get("token").asText();
        String hash = sessionService.hash(token);

        // still PENDING: no code is exposed, the request long-polls
        MvcResult pending = mvc.perform(get("/api/demo-auth/session/{t}/poll", token).param("since", "PENDING"))
                .andReturn();
        assertThat(pending.getRequest().isAsyncStarted()).isTrue();

        sessionService.awaitCode(hash);

        MvcResult awaiting = mvc.perform(get("/api/demo-auth/session/{t}/poll", token).param("since", "PENDING"))
                .andExpect(status().isAccepted()).andReturn();
        JsonNode body = json.readTree(awaiting.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("AWAITING_CODE");
        assertThat(body.get("confirmCode").asInt()).isEqualTo(
                io.github.dev_abdulhay.telegramauth.security.ConfirmCode.of(hash));
    }

    @Test
    void pollWithoutSinceKeepsThePreviousTerminalOnlyContract() throws Exception {
        MvcResult createRes = mvc.perform(post("/api/demo-auth/session"))
                .andExpect(status().isOk()).andReturn();
        String token = json.readTree(createRes.getResponse().getContentAsString()).get("token").asText();
        sessionService.awaitCode(sessionService.hash(token));

        // a 0.3.0 client must not be told "APPROVED" and must not see a code
        MvcResult async = mvc.perform(get("/api/demo-auth/session/{t}/poll", token)).andReturn();
        assertThat(async.getRequest().isAsyncStarted()).isTrue();
    }

    @Test
    void pollWithSinceAwaitingCodeDoesNotReturnImmediately() throws Exception {
        MvcResult createRes = mvc.perform(post("/api/demo-auth/session"))
                .andExpect(status().isOk()).andReturn();
        String token = json.readTree(createRes.getResponse().getContentAsString()).get("token").asText();
        sessionService.awaitCode(sessionService.hash(token));

        // without this the client would busy-loop on its own current state
        MvcResult async = mvc.perform(get("/api/demo-auth/session/{t}/poll", token)
                        .param("since", "AWAITING_CODE"))
                .andReturn();
        assertThat(async.getRequest().isAsyncStarted()).isTrue();
    }

    @Test
    void statusReportsAwaitingCodeButNeverTheCode() throws Exception {
        MvcResult createRes = mvc.perform(post("/api/demo-auth/session"))
                .andExpect(status().isOk()).andReturn();
        String token = json.readTree(createRes.getResponse().getContentAsString()).get("token").asText();
        sessionService.awaitCode(sessionService.hash(token));

        MvcResult res = mvc.perform(get("/api/demo-auth/session/{t}/status", token))
                .andExpect(status().isOk()).andReturn();
        String body = res.getResponse().getContentAsString();
        assertThat(json.readTree(body).get("status").asText()).isEqualTo("AWAITING_CODE");
        assertThat(body).doesNotContain("confirmCode");
    }

    @Test
    void cancelRejectsASessionStuckAtTheCodeStep() throws Exception {
        MvcResult createRes = mvc.perform(post("/api/demo-auth/session"))
                .andExpect(status().isOk()).andReturn();
        String token = json.readTree(createRes.getResponse().getContentAsString()).get("token").asText();
        sessionService.awaitCode(sessionService.hash(token));

        mvc.perform(delete("/api/demo-auth/session/{t}", token)).andExpect(status().isNoContent());

        assertThat(sessionService.findByRawToken(token).orElseThrow().getStatus())
                .isEqualTo(io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession.Status.REJECTED);
    }
```

Add the import `static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;`.

- [ ] **Step 2: Run tests to verify they fail**

```
mvn -B test -Dtest=ControllerFlowTest 2>&1 | grep -E "Tests run|FAIL|ERROR" | head
```
Expected: failures — `202` never returned, `confirmCode` absent, cancel leaves `AWAITING_CODE`.

- [ ] **Step 3: Extend `WaitResponse`**

```java
package io.github.dev_abdulhay.telegramauth.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Poll result. {@code confirmCode} is set only on an {@code AWAITING_CODE}
 * response and is omitted from the JSON otherwise — it is the browser-side half
 * of the number-matching check, not part of the host's approve payload.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WaitResponse(String status, Map<String, Object> payload, Integer confirmCode) {

    public WaitResponse(String status, Map<String, Object> payload) {
        this(status, payload, null);
    }
}
```

- [ ] **Step 4: Rework `poll`, `terminalResponse` and `cancel`**

Replace the `poll` signature and body (line 72-115):

```java
    /**
     * Long-polls for the next session state the caller has not seen yet.
     *
     * <p>{@code since} names the state the client already knows. Omitting it keeps
     * the pre-0.4.0 contract: only terminal states are returned, and an
     * {@code AWAITING_CODE} transition arriving mid-poll is answered with
     * {@code 204} so the client simply polls again. Passing
     * {@code since=PENDING} opts into the code step and yields {@code 202} plus
     * the browser-visible {@code confirmCode}; passing
     * {@code since=AWAITING_CODE} waits for a terminal state, which is what stops
     * a client from busy-looping on the state it is already in.
     */
    @GetMapping("/session/{token}/poll")
    public DeferredResult<ResponseEntity<WaitResponse>> poll(@PathVariable String token,
                                                             @RequestParam(required = false) String since) {
        String hash = sessionService.hash(token);
        boolean wantsCode = "PENDING".equalsIgnoreCase(since);
        S s = sessionService.findByRawToken(token).orElse(null);
        if (s == null) {
            return immediate(ResponseEntity.status(HttpStatus.GONE).build());
        }
        ResponseEntity<WaitResponse> ready = immediateResponse(s, hash, wantsCode);
        if (ready != null) {
            return immediate(ready);
        }

        long remainingMs = Duration.between(OffsetDateTime.now(), s.getExpiresAt()).toMillis();
        long timeoutMs = Math.min(module.getPollingTimeout().toMillis(), Math.max(remainingMs, 0));

        DeferredResult<ResponseEntity<WaitResponse>> result = new DeferredResult<>(timeoutMs);
        result.onTimeout(() -> result.setResult(ResponseEntity.noContent().build()));

        Consumer<AuthEvent> listener = ev -> {
            ResponseEntity<WaitResponse> resp = switch (ev.type()) {
                case APPROVED -> ResponseEntity.ok(new WaitResponse("APPROVED", ev.payload()));
                case REJECTED -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(new WaitResponse("REJECTED", Map.of()));
                case EXPIRED -> ResponseEntity.status(HttpStatus.GONE).build();
                // a client that did not ask for the code step gets the same answer
                // as a timeout, so it re-polls instead of hanging for nothing
                case AWAITING_CODE -> wantsCode ? awaitingCodeResponse(hash) : ResponseEntity.noContent().build();
            };
            result.setResult(resp);
        };
        module.getBus().subscribe(hash, listener);
        result.onCompletion(() -> module.getBus().unsubscribe(hash, listener));

        // Re-check AFTER subscribing: a transition that landed between the first
        // read and the subscription is caught here from the DB.
        S fresh = sessionService.findByRawToken(token).orElse(null);
        ResponseEntity<WaitResponse> late = (fresh == null)
                ? ResponseEntity.<WaitResponse>status(HttpStatus.GONE).build()
                : immediateResponse(fresh, hash, wantsCode);
        if (late != null) {
            result.setResult(late);
        }
        return result;
    }
```

Replace `terminalResponse` (line 139-152):

```java
    /** Response for a state the caller has not seen yet, or {@code null} to keep waiting. */
    private ResponseEntity<WaitResponse> immediateResponse(S s, String hash, boolean wantsCode) {
        if (s.getStatus() == Status.APPROVED) {
            return ResponseEntity.ok(new WaitResponse("APPROVED", readPayload(s)));
        }
        if (s.getStatus() == Status.REJECTED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new WaitResponse("REJECTED", Map.of()));
        }
        // checked before AWAITING_CODE so an overdue half-finished login reads as gone
        if (s.getStatus() == Status.EXPIRED || s.getExpiresAt().isBefore(OffsetDateTime.now())) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }
        if (s.getStatus() == Status.AWAITING_CODE && wantsCode) {
            return awaitingCodeResponse(hash);
        }
        return null;
    }

    private ResponseEntity<WaitResponse> awaitingCodeResponse(String hash) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new WaitResponse(
                "AWAITING_CODE", Map.of(), module.getConfirmCodeGenerator().codeFor(hash)));
    }
```

In `cancel` (line 127):

```java
            if (s.getStatus() == Status.PENDING || s.getStatus() == Status.AWAITING_CODE) {
```

Add the import `org.springframework.web.bind.annotation.RequestParam`.

- [ ] **Step 5: Run tests to verify they pass**

```
mvn -B test 2>&1 | grep -E "Tests run:|BUILD" | tail -5
```
Expected: BUILD SUCCESS, 50 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/dev_abdulhay/telegramauth/web/ \
        src/test/java/com/example/demo/ControllerFlowTest.java
git commit -m "feat(web): deliver the confirmation code through poll?since=PENDING"
```

---

## Task 5: Text routing hook

**Files:**
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotModule.java`
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/bot/BotUpdateDispatcher.java:11-15,70-92`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/bot/BotUpdateDispatcherTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `TelegramBotModule.onText(Consumer<JsonNode>)`, `getTextHandler() -> Consumer<JsonNode>`
  - dispatcher order: callback → command → contact → **text** → fallback
  - `TelegramBotModule.Builder.sessionTtl` default `Duration.ofMinutes(5)`

- [ ] **Step 1: Write the failing test**

Append to `BotUpdateDispatcherTest`:

```java
    @Test
    void plainTextRoutesToTheTextHandlerInsteadOfTheFallback() {
        TelegramBotModule m = module();
        AtomicReference<JsonNode> text = new AtomicReference<>();
        AtomicReference<JsonNode> fb = new AtomicReference<>();
        m.onText(text::set);
        m.fallback(fb::set);
        BotUpdateDispatcher d = new BotUpdateDispatcher(m);

        String json = "{\"ok\":true,\"result\":[{\"update_id\":11,"
                + "\"message\":{\"text\":\"42\",\"chat\":{\"id\":5}}}]}";
        assertThat(d.dispatch(json)).isEqualTo(11);
        assertThat(text.get()).isNotNull();
        assertThat(fb.get()).isNull();
    }

    @Test
    void unregisteredCommandsAlsoReachTheTextHandler() {
        TelegramBotModule m = module();
        AtomicReference<JsonNode> text = new AtomicReference<>();
        m.command("/start", u -> { });
        m.onText(text::set);
        BotUpdateDispatcher d = new BotUpdateDispatcher(m);

        String json = "{\"ok\":true,\"result\":[{\"update_id\":12,"
                + "\"message\":{\"text\":\"/nope\",\"chat\":{\"id\":5}}}]}";
        assertThat(d.dispatch(json)).isEqualTo(12);
        assertThat(text.get()).isNotNull();
    }

    @Test
    void textStillFallsBackWhenNoTextHandlerIsRegistered() {
        TelegramBotModule m = module();
        AtomicReference<JsonNode> fb = new AtomicReference<>();
        m.fallback(fb::set);
        BotUpdateDispatcher d = new BotUpdateDispatcher(m);

        String json = "{\"ok\":true,\"result\":[{\"update_id\":13,"
                + "\"message\":{\"text\":\"hello\",\"chat\":{\"id\":9}}}]}";
        assertThat(d.dispatch(json)).isEqualTo(13);
        assertThat(fb.get()).isNotNull();
    }

    @Test
    void textHandlerSlotRefusesASecondRegistration() {
        TelegramBotModule m = module();
        m.onText(u -> { });
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> m.onText(u -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("text handler is already registered");
    }

    @Test
    void sessionTtlDefaultsToFiveMinutes() {
        assertThat(module().getSessionTtl()).isEqualTo(java.time.Duration.ofMinutes(5));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```
mvn -B test -Dtest=BotUpdateDispatcherTest 2>&1 | grep -E "ERROR|cannot find symbol|Tests run" | head
```
Expected: COMPILATION ERROR — `onText` does not exist.

- [ ] **Step 3: Add the text slot to `TelegramBotModule`**

Field next to `contactHandler`:

```java
    private volatile Consumer<JsonNode> textHandler;
```

Method next to `onContact`:

```java
    /**
     * Handler for message updates carrying plain {@code text} that matched no
     * registered command. Single-slot with the same replace-guard as
     * {@link #onCallbackQuery(Consumer)}: {@code DefaultAuthFlow} claims it when
     * {@code codeConfirmation} is {@code TYPED}.
     *
     * <p>It also receives <b>unregistered {@code /commands}</b> — the dispatcher
     * cannot tell them from ordinary text once the registry misses. A handler
     * that does not own such an update should forward it to the
     * {@link #fallback(Consumer)}.
     *
     * @throws IllegalStateException if a different handler is already registered
     */
    public void onText(Consumer<JsonNode> handler) {
        this.textHandler = claimSlot("text", this.textHandler, handler);
    }
```

Getter next to `getContactHandler()`:

```java
    public Consumer<JsonNode> getTextHandler() { return textHandler; }
```

Builder default (line 127): `private Duration sessionTtl = Duration.ofMinutes(5);` and add to
the `sessionTtl` builder method a javadoc line:

```java
        /** How long a login link stays usable. Default 5 minutes — enough for the contact and code steps. */
```

- [ ] **Step 4: Route text in `BotUpdateDispatcher`**

Class javadoc routing sentence becomes: *"Routing order: `callback_query` handler, then the
command registry, then the `contact` handler, then the text handler, then the module
fallback."*

Replace the tail of `route(...)` (lines 86-91):

```java
        if (message.has("contact")) {
            Consumer<JsonNode> handler = module.getContactHandler();
            invoke(handler != null ? handler : module.getFallback(), update);
            return;
        }
        if (!text.isEmpty()) {
            Consumer<JsonNode> handler = module.getTextHandler();
            invoke(handler != null ? handler : module.getFallback(), update);
            return;
        }
        invoke(module.getFallback(), update);
```

- [ ] **Step 5: Run tests to verify they pass**

```
mvn -B test 2>&1 | grep -E "Tests run:|BUILD" | tail -5
```
Expected: BUILD SUCCESS, 55 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/dev_abdulhay/telegramauth/bot/ \
        src/test/java/io/github/dev_abdulhay/telegramauth/bot/BotUpdateDispatcherTest.java
git commit -m "feat(bot): add single-slot text handler and raise default sessionTtl to 5m"
```

---


---

## Task 6: `Options` gains the code-confirmation settings

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/flow/CodeConfirmation.java`
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/flow/DefaultAuthFlow.java:76-100`
- Test: `src/test/java/com/example/demo/DefaultAuthFlowOptionsTest.java`,
  `src/test/java/com/example/demo/DefaultAuthFlowTest.java`,
  `src/test/java/com/example/demo/DemoTgConfig.java`

**Interfaces:**
- Produces: `CodeConfirmation.{BUTTON,TYPED,OFF}`; `Options.codeConfirmation()`,
  `codeButtons()`, `maxCodeAttempts()`, `effectiveMaxCodeAttempts()`, `codeCooldown()`,
  `codeCooldownMax()`, `codeCooldownThreshold()` and the matching `Builder` setters.

The flow does not read the new options yet — this task only introduces the surface and
**pins every existing behavioural test to `codeConfirmation(OFF)`**, so Task 9's behaviour
change lands on tests that have already declared what they expect.

- [ ] **Step 1: Write the failing test**

Append to `DefaultAuthFlowOptionsTest`:

```java
    @Test
    void codeButtonsMustStayBetweenThreeAndTen() {
        assertThatThrownBy(() -> DefaultAuthFlow.Options.builder().codeButtons(2).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("codeButtons");
        assertThatThrownBy(() -> DefaultAuthFlow.Options.builder().codeButtons(11).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("codeButtons");
        assertThat(DefaultAuthFlow.Options.builder().codeButtons(3).build().codeButtons()).isEqualTo(3);
        assertThat(DefaultAuthFlow.Options.builder().codeButtons(10).build().codeButtons()).isEqualTo(10);
    }

    @Test
    void codeConfirmationDefaultsToButtonAndAttemptsFollowTheMode() {
        DefaultAuthFlow.Options d = DefaultAuthFlow.Options.defaults();
        assertThat(d.codeConfirmation()).isEqualTo(CodeConfirmation.BUTTON);
        assertThat(d.codeButtons()).isEqualTo(3);
        assertThat(d.effectiveMaxCodeAttempts()).isEqualTo(1);
        assertThat(d.codeCooldown()).isEqualTo(Duration.ofMinutes(5));
        assertThat(d.codeCooldownMax()).isEqualTo(Duration.ofHours(1));
        assertThat(d.codeCooldownThreshold()).isEqualTo(1);

        assertThat(DefaultAuthFlow.Options.builder().codeConfirmation(CodeConfirmation.TYPED)
                .build().effectiveMaxCodeAttempts()).isEqualTo(3);
        // an explicit value always wins over the per-mode default
        assertThat(DefaultAuthFlow.Options.builder().codeConfirmation(CodeConfirmation.TYPED)
                .maxCodeAttempts(2).build().effectiveMaxCodeAttempts()).isEqualTo(2);
    }

    @Test
    void cooldownCeilingMustNotBeBelowTheBaseCooldown() {
        assertThatThrownBy(() -> DefaultAuthFlow.Options.builder()
                .codeCooldown(Duration.ofMinutes(10)).codeCooldownMax(Duration.ofMinutes(5)).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("codeCooldownMax");
    }
```

- [ ] **Step 2: Pin the existing tests to the legacy mode**

In `DefaultAuthFlowOptionsTest`, add a helper next to `env(...)`:

```java
    /** 0.3.0 behaviour: the number-matching step off, so these tests keep asserting the old flow. */
    private static DefaultAuthFlow.Options.Builder legacy() {
        return DefaultAuthFlow.Options.builder().codeConfirmation(CodeConfirmation.OFF);
    }
```

Then replace, throughout the file, every `DefaultAuthFlow.Options.builder()` with `legacy()`
and every `DefaultAuthFlow.Options.defaults()` with `legacy().build()`. Leave the three new
option tests above untouched.

In `DefaultAuthFlowTest.registersUserAndApprovesSession`, replace the flow construction:

```java
        new DefaultAuthFlow<>(userService, sessionService, module,
                DefaultAuthFlow.Options.builder().codeConfirmation(CodeConfirmation.OFF).build());
```

In `DemoTgConfig.demoFlow`, same change — the demo app's REST tests approve through the
service and must not be perturbed by the new default.

- [ ] **Step 3: Run tests to verify they fail**

```
mvn -B test -Dtest='DefaultAuthFlowOptionsTest+DefaultAuthFlowTest' 2>&1 | grep -E "ERROR|cannot find symbol|Tests run" | head
```
Expected: COMPILATION ERROR — `CodeConfirmation` does not exist.

- [ ] **Step 4: Create the enum**

```java
package io.github.dev_abdulhay.telegramauth.flow;

/**
 * How the browser-visible confirmation code is collected in the bot.
 *
 * <ul>
 *   <li>{@code BUTTON} — inline keyboard of candidate numbers; one wrong tap ends the login</li>
 *   <li>{@code TYPED} — the user sends the number as text; 100 candidates make three tries safe</li>
 *   <li>{@code OFF} — no code step (pre-0.4.0 behaviour)</li>
 * </ul>
 */
public enum CodeConfirmation { BUTTON, TYPED, OFF }
```

- [ ] **Step 5: Extend `Options`**

Replace the `Options` class (lines 76-100) with the eight-field version: fields
`requireContact`, `requireApproval`, `codeConfirmation`, `codeButtons`, `maxCodeAttempts`,
`codeCooldown`, `codeCooldownMax`, `codeCooldownThreshold`; accessors of the same names;
`effectiveMaxCodeAttempts()`; and `Builder.build()` validation that rejects
`codeConfirmation == null`, `codeButtons` outside `3..10`, `maxCodeAttempts < 0`, a null or
negative `codeCooldown`, a `codeCooldownMax` below `codeCooldown`, and
`codeCooldownThreshold < 1`. Defaults: `BUTTON`, `3`, `0`, `5m`, `1h`, `1`.

Also rewrite the `DefaultAuthFlow` class javadoc: the "both flags default to false" sentence
is no longer true.

- [ ] **Step 6: Run the full suite**

```
mvn -B test 2>&1 | grep -E "Tests run:|BUILD" | tail -5
```
Expected: BUILD SUCCESS, 58 tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/dev_abdulhay/telegramauth/flow/CodeConfirmation.java \
        src/main/java/io/github/dev_abdulhay/telegramauth/flow/DefaultAuthFlow.java \
        src/test/java/com/example/demo/
git commit -m "feat(flow): add code-confirmation options and pin legacy tests to OFF"
```

---

## Task 7: Bot texts for the code step

**Files:**
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/flow/FlowMessages.java`
- Test: `src/test/java/com/example/demo/DefaultAuthFlowOptionsTest.java`

**Interfaces:**
- Produces: `FlowMessages.Key.{CODE_PROMPT_BUTTON, CODE_PROMPT_TYPED, CODE_WRONG,
  CODE_NOT_A_NUMBER, CODE_ATTEMPTS_EXHAUSTED, TOO_MANY_ATTEMPTS, CONFIRM_STEP_DONE,
  CONFIRM_WARNING}` — all present in `uz`, `ru`, `en`.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void everyMessageKeyIsTranslatedIntoAllThreeLanguages() {
        for (FlowMessages.Key key : FlowMessages.Key.values()) {
            for (String lang : List.of("uz", "ru", "en")) {
                assertThat(FlowMessages.text(key, lang))
                        .as("%s/%s", key, lang).isNotBlank();
            }
        }
        assertThat(FlowMessages.text(FlowMessages.Key.CODE_WRONG, "uz")).contains("%d");
        assertThat(FlowMessages.text(FlowMessages.Key.TOO_MANY_ATTEMPTS, "uz")).contains("%d");
        assertThat(FlowMessages.text(FlowMessages.Key.CONFIRM_WARNING, "uz")).contains("❌");
    }
```

This currently passes vacuously; it starts failing the moment a key is added without a
translation, which is the regression it exists to catch.

- [ ] **Step 2: Add the eight keys and their three translations**

Keys and `uz` / `ru` / `en` text (see the spec §5.6 table for the exact strings). `CODE_WRONG`
and `TOO_MANY_ATTEMPTS` carry a single `%d`.

- [ ] **Step 3: Run tests, then commit**

```
mvn -B test 2>&1 | grep -E "Tests run:|BUILD" | tail -5
git add src/main/java/io/github/dev_abdulhay/telegramauth/flow/FlowMessages.java src/test/java/com/example/demo/DefaultAuthFlowOptionsTest.java
git commit -m "feat(flow): add localized texts for the confirmation-code step"
```

---

## Task 8: `CodeStrikeTracker`

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/flow/CodeStrikeTracker.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/flow/CodeStrikeTrackerTest.java`

**Interfaces:**
- Produces:
  - `new CodeStrikeTracker(Duration base, Duration max, int threshold)`
  - `Duration strike(long userId)` — records one failed login, returns the armed cooldown or `null`
  - `Duration remaining(long userId)` — remaining cooldown or `null`
  - `void clear(long userId)` · `void purge()` · `int size()`

Behaviour: cooldown arms once `strikes >= threshold`, lasts
`base * 2^(strikes - threshold)` capped at `max`; the exponent is clamped to 16 so the shift
cannot overflow. `base == ZERO` disables cooldowns entirely (strikes are still counted).
Entries age out `max` after their last touch and the map has the same 10 000-entry ceiling as
`pendingLogins`.

- [ ] **Step 1: Write the failing test** — cover: no cooldown below the threshold; doubling
  `5→10→20→40`; the `max` cap; `clear` resetting the ladder; `remaining` returning `null`
  once the window passes; `ZERO` base disabling arming; `purge` dropping stale entries.
- [ ] **Step 2: Run it and watch it fail** (class missing).
- [ ] **Step 3: Implement the tracker.**
- [ ] **Step 4: Run tests.** Expected: green.
- [ ] **Step 5: Commit** — `feat(flow): add per-user wrong-code strike tracker with escalating cooldown`

---

## Task 9: Code stage entry and deferred registration

**Files:**
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/flow/DefaultAuthFlow.java`
- Test: `src/test/java/com/example/demo/CodeConfirmationFlowTest.java` (new)

**Interfaces:**
- Consumes: `awaitCode` (T3), `CodeConfirmation` + `Options` (T6), message keys (T7).
- Produces (all `protected`, override points):
  - `enterCodeStage(long, String, String, String, boolean)`
  - `sendCodePrompt(long, String, String, S)`
  - `sessionDetails(S, String)` — extracted from `confirmPrompt`
  - `codeChoices(int realCode, int count) -> List<Integer>`
  - `formatCode(int) -> String`

- [ ] **Step 1: Write the failing tests** — a new `CodeConfirmationFlowTest` reusing the
  `RecordingBot` / `Env` shape of `DefaultAuthFlowOptionsTest`, covering:
  `requireApproval=true` + `BUTTON` → after ✅ the session is `AWAITING_CODE`, the keyboard
  carries `tgauth:c` entries, **no user row exists yet**; `requireApproval=false` + `BUTTON`
  → `/start` alone reaches `AWAITING_CODE` and the prompt contains the IP, the device and the
  ❌ warning; decoys never equal the real code and never repeat; double-tapping ✅ re-issues
  the prompt instead of reporting an expired session.
- [ ] **Step 2: Run and watch them fail.**
- [ ] **Step 3: Implement** — register the callback handler when
  `requireApproval || codeConfirmation != OFF`; grow `Pending` with `codeAttempts`; add
  `enterCodeStage` / `sendCodePrompt` / `codeKeyboard` / `codeChoices` / `formatCode`; split
  `sessionDetails` out of `confirmPrompt` and append `CONFIRM_WARNING`; branch
  `proceedAfterIdentity` and the `approve` callback on `codeConfirmation`; move
  `registerFrom` out of the `approve` callback.
- [ ] **Step 4: Run tests.** Expected: green, full suite green.
- [ ] **Step 5: Commit** — `feat(flow): add the confirmation-code stage and defer registration to it`

---

## Task 10: BUTTON guess handling

**Files:** `flow/DefaultAuthFlow.java` · `CodeConfirmationFlowTest.java`

**Interfaces:** produces `protected String handleGuess(long, JsonNode, String, int, String)`
and the `tgauth:c<NN>:<token>` callback branch.

- [ ] **Step 1: Failing tests** — correct tap approves and registers; first wrong tap rejects
  the session and leaves no user row (`maxCodeAttempts` default 1); with
  `maxCodeAttempts(2)` a wrong tap re-prompts with a **freshly shuffled** keyboard and only
  the second wrong tap rejects; a guess against a session that is not `AWAITING_CODE` reports
  an expired session; every wrong guess is logged at WARN.
- [ ] **Step 2–4: Implement, run, verify.**
- [ ] **Step 5: Commit** — `feat(flow): verify BUTTON-mode confirmation codes and reject on failure`

---

## Task 11: TYPED text handling

**Files:** `flow/DefaultAuthFlow.java` · `CodeConfirmationFlowTest.java`

**Interfaces:** produces `public void onText(JsonNode)`, registered only when
`codeConfirmation == TYPED`.

- [ ] **Step 1: Failing tests** — three wrong numbers reject the session (and only the third
  does); text with no login in progress goes to the host fallback; an unregistered
  `/command` goes to the fallback and burns no attempt; non-numeric text re-prompts and burns
  no attempt; the correct number approves.
- [ ] **Step 2–4: Implement, run, verify.**
- [ ] **Step 5: Commit** — `feat(flow): accept typed confirmation codes without stealing host text updates`

---

## Task 12: Cooldown wiring

**Files:** `flow/DefaultAuthFlow.java` · `CodeConfirmationFlowTest.java`

**Interfaces:** consumes `CodeStrikeTracker` (T8); no new public surface.

- [ ] **Step 1: Failing tests** — after a failed code the next `/start` is refused with the
  cooldown message; ❌ still works while cooling; a successful login clears the ladder; three
  wrong TYPED guesses count as **one** strike, not three.
- [ ] **Step 2–4: Implement** — construct the tracker from `Options`, check
  `remaining(userId)` at the top of `onStart`, `onCallback` (except `reject`) and `onText`,
  call `strike(...)` where a login dies at the code step and `clear(...)` on success, and
  purge alongside `purgeStalePending()`.
- [ ] **Step 5: Commit** — `feat(flow): cool a user down after wrong confirmation codes`

---

## Task 13: `telegram.auth.flow` configuration binding

**Files:**
- Modify: `config/TelegramAuthProperties.java`, `config/TelegramAuthAutoConfiguration.java`
- Test: `src/test/java/com/example/demo/FlowOptionsBindingTest.java` (new)

**Interfaces:**
- Produces: `TelegramAuthProperties.Flow` (nullable wrappers) with `toOptions()` and
  `toOptions(Flow base)`; `getFlow()`, `getFlows()`; auto-config bean
  `DefaultAuthFlow.Options telegramAuthFlowOptions(TelegramAuthProperties)`.

- [ ] **Step 1: Failing tests** — a plain unit test that an unset field in `flows.admin`
  falls back to `flow` and then to `Options.defaults()`, while a set field wins; plus a
  `@SpringBootTest` with `telegram.auth.flow.code-confirmation=TYPED` and
  `telegram.auth.flow.code-cooldown=90s` asserting the autowired `Options` bean reflects them.
- [ ] **Step 2–4: Implement, run, verify.** Validation stays in `Options.Builder.build()`, so
  a bad `code-buttons` fails at startup.
- [ ] **Step 5: Commit** — `feat(config): bind DefaultAuthFlow options from telegram.auth.flow properties`

---

## Task 14: Version, README and CHANGELOG

**Files:** `pom.xml:10` · `README.md` · `CHANGELOG.md`

- [ ] **Step 1: Bump** `<version>` to `0.4.0`.
- [ ] **Step 2: README** — add the combination matrix (spec §3), the threat model with the
  honest attack-probability table (spec §2), the `?since` contract with the new `202`/`204`
  codes, the `telegram.auth.flow` / `telegram.auth.flows` YAML sample, the
  `ConfirmCodeGenerator` / `codeChoices` / `formatCode` override points, the JVM-local note
  for `pendingLogins` and the strike tracker with its now-wider window, and a migration
  section for the `codeConfirmation=BUTTON` default. **Verify every snippet against the real
  code before writing it.**
- [ ] **Step 3: CHANGELOG** — Keep-a-Changelog `0.4.0` entry with an explicit `BREAKING`
  block: `codeConfirmation` default, `WaitResponse` third component, the two repository
  signatures, `sessionTtl` `3m→5m`, the extra `AuthEvent.Type`.
- [ ] **Step 4: Full suite.** Expected: BUILD SUCCESS.
- [ ] **Step 5: Commit** — `chore(release): 0.4.0 — login confirmation code`
