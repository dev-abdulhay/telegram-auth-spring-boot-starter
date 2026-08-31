# White-label Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Start each managed bot at runtime so every tenant gets its own working, branded login bot.

**Architecture:** A new `whitelabel` package sits on top of `managedbots`, `flow`, `service` and `bot`. A host-supplied `TenantBotFactory` builds the per-tenant module and session service; a `TenantBotRegistry` owns the live bots; an event bridge turns managed-bot lifecycle events into start/restart/stop; a lifecycle bean restores bots at startup. Sessions carry a `bot_user_id` so tenants share tables without sharing rate limits.

**Tech Stack:** Java 17, Spring Boot 3.3.5 (BOM), Spring Data JPA, JUnit 5 + AssertJ, WireMock (already a test dependency).

**Spec:** `docs/superpowers/specs/2026-09-01-white-label-runtime-design.md`

## Global Constraints

- Java 17 (`maven.compiler.source/target=17`). The library must never reference a Java 21 API — `Thread.ofVirtual()` is the host's call, passed in as a `java.util.concurrent.ThreadFactory`.
- Branch `feat/white-label-runtime`, cut from `main` (which already contains v0.4.0 and managed-bots).
- New main code lives in `io.github.dev_abdulhay.telegramauth.whitelabel`; its tests in `src/test/java/io/github/dev_abdulhay/telegramauth/whitelabel/`.
- Dependency direction is one-way: `whitelabel` may import `managedbots`, `flow`, `service`, `entity`, `repository` and `bot`. Nothing may import `whitelabel`. **`managedbots` must still import only from `bot/`** — do not add a `flow/` or `service/` import to it.
- No new dependencies, runtime or test.
- Config prefix `telegram.white-label`. Never add keys under `telegram.auth` or `telegram.managed-bots`.
- Tokens are never logged and never appear in an exception message.
- Existing behaviour must not change for hosts that leave the feature off: the poll-failure budget defaults to "never give up", exactly as today.
- Conventional Commits, no AI attribution trailers of any kind.
- `mvn test` before every commit; it must stay green (173 tests before this plan starts).

---

### Task 1: Tenant column and per-tenant rate limiting

**Files:**
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/entity/BaseAuthSession.java`
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotModule.java`
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/repository/BaseAuthSessionRepository.java`
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/service/AbstractSessionService.java`
- Modify: `src/test/java/com/example/demo/StubSessionRepo.java`
- Test: `src/test/java/com/example/demo/TenantScopingTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `BaseAuthSession#getBotUserId()/setBotUserId(Long)`; `TelegramBotModule#getBotUserId()` and `Builder#botUserId(Long)`; repository method `long countByIpAddressAndBotUserIdAndStatusInAndExpiresAtAfter(String, Long, Collection<Status>, OffsetDateTime)`.

- [ ] **Step 1: Write the failing test**

```java
package com.example.demo;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import io.github.dev_abdulhay.telegramauth.service.SessionRateLimitException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class TenantScopingTest {

    private static DemoSessionService serviceFor(Long botUserId, StubSessionRepo repo) {
        TelegramBotModule.Builder b = TelegramBotModule.builder("123:ABC", "tenant_bot")
                .maxPendingPerIp(2);
        if (botUserId != null) b.botUserId(botUserId);
        return new DemoSessionService(repo, new TokenGenerator(), b.build());
    }

    @Test
    void aTenantSessionCarriesItsBotId() {
        var created = serviceFor(555L, new StubSessionRepo()).create("ip", "ua");
        assertThat(((BaseAuthSession) created.entity()).getBotUserId()).isEqualTo(555L);
    }

    @Test
    void aStaticModuleLeavesTheBotIdNull() {
        var created = serviceFor(null, new StubSessionRepo()).create("ip", "ua");
        assertThat(((BaseAuthSession) created.entity()).getBotUserId()).isNull();
    }

    @Test
    void aFloodAgainstOneTenantDoesNotLockOutAnother() {
        StubSessionRepo shared = new StubSessionRepo();
        DemoSessionService tenantA = serviceFor(555L, shared);
        DemoSessionService tenantB = serviceFor(556L, shared);

        tenantA.create("1.2.3.4", "ua");
        tenantA.create("1.2.3.4", "ua");
        assertThatThrownBy(() -> tenantA.create("1.2.3.4", "ua"))
                .isInstanceOf(SessionRateLimitException.class);

        // same IP, same table, different tenant — must still be allowed
        assertThatCode(() -> tenantB.create("1.2.3.4", "ua")).doesNotThrowAnyException();
    }

    @Test
    void aStaticModuleStillCountsAcrossTheWholeTable() {
        StubSessionRepo shared = new StubSessionRepo();
        DemoSessionService staticModule = serviceFor(null, shared);

        staticModule.create("1.2.3.4", "ua");
        staticModule.create("1.2.3.4", "ua");
        assertThatThrownBy(() -> staticModule.create("1.2.3.4", "ua"))
                .isInstanceOf(SessionRateLimitException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TenantScopingTest`
Expected: compilation failure — `Builder#botUserId` and `getBotUserId` do not exist.

- [ ] **Step 3: Write minimal implementation**

In `BaseAuthSession`, beside `telegramUserId`:

```java
    /**
     * The managed bot this session belongs to, or {@code null} for a session
     * created by a statically configured module. Nullable on purpose: rows
     * written before white-label existed have no bot, so the column is additive
     * and needs no backfill.
     */
    @Column(name = "bot_user_id")
    private Long botUserId;
```

and with the other accessors:

```java
    public Long getBotUserId() { return botUserId; }
    public void setBotUserId(Long botUserId) { this.botUserId = botUserId; }
```

In `TelegramBotModule`, add the field, the constructor assignment (`this.botUserId = b.botUserId;`), the getter, the builder field and the builder method:

```java
    private final Long botUserId;

    public Long getBotUserId() { return botUserId; }
```

```java
        private Long botUserId;

        /**
         * Telegram user id of the managed bot this module drives, or {@code null}
         * for a statically configured bot. Sessions created through this module
         * carry it, which is what keeps one tenant's rate limit off another's.
         */
        public Builder botUserId(Long v) { this.botUserId = v; return this; }
```

In `BaseAuthSessionRepository`, beside the existing count:

```java
    /**
     * Live sessions for an IP <em>within one tenant bot</em>. A flood against one
     * tenant must not consume another tenant's quota, even though both share the
     * table.
     */
    long countByIpAddressAndBotUserIdAndStatusInAndExpiresAtAfter(String ipAddress,
                                                                  Long botUserId,
                                                                  Collection<BaseAuthSession.Status> statuses,
                                                                  OffsetDateTime time);
```

In `AbstractSessionService.create`, replace the count and add the assignment:

```java
        int limit = module.getMaxPendingPerIp();
        Long botUserId = module.getBotUserId();
        if (limit > 0 && ipAddress != null && !ipAddress.isBlank() && liveForIp(ipAddress, botUserId) >= limit) {
            throw new SessionRateLimitException(ipAddress);
        }
        String raw = tokenGenerator.newToken();
        S s = factory.get();
        s.setBotUserId(botUserId);
        s.setTokenHash(tokenGenerator.hash(raw));
```

and add the helper below `create`:

```java
    /**
     * Live sessions for this IP, scoped to the module's tenant when it has one.
     * A statically configured module counts across the whole table, which is the
     * pre-white-label behaviour and must not change.
     */
    private long liveForIp(String ipAddress, Long botUserId) {
        OffsetDateTime now = OffsetDateTime.now();
        return (botUserId == null)
                ? sessionRepo.countByIpAddressAndStatusInAndExpiresAtAfter(ipAddress, LIVE_STATUSES, now)
                : sessionRepo.countByIpAddressAndBotUserIdAndStatusInAndExpiresAtAfter(
                        ipAddress, botUserId, LIVE_STATUSES, now);
    }
```

In `StubSessionRepo`, implement the new method beside the existing count (match the file's existing style):

```java
    @Override public long countByIpAddressAndBotUserIdAndStatusInAndExpiresAtAfter(
            String ipAddress, Long botUserId, Collection<BaseAuthSession.Status> statuses, OffsetDateTime time) {
        return store.values().stream()
                .filter(s -> ipAddress.equals(s.getIpAddress()))
                .filter(s -> botUserId.equals(s.getBotUserId()))
                .filter(s -> statuses.contains(s.getStatus()))
                .filter(s -> s.getExpiresAt() != null && s.getExpiresAt().isAfter(time))
                .count();
    }
```

Read `StubSessionRepo` first: the field holding the rows may not be named `store`, and the existing count method shows the exact idiom to copy.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=TenantScopingTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Run the full suite**

Run: `mvn test`
Expected: BUILD SUCCESS. `AbstractSessionService` is shared by every existing test, so a regression here shows up immediately.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/dev_abdulhay/telegramauth/entity/BaseAuthSession.java \
        src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotModule.java \
        src/main/java/io/github/dev_abdulhay/telegramauth/repository/BaseAuthSessionRepository.java \
        src/main/java/io/github/dev_abdulhay/telegramauth/service/AbstractSessionService.java \
        src/test/java/com/example/demo/StubSessionRepo.java \
        src/test/java/com/example/demo/TenantScopingTest.java
git commit -m "feat(session): scope sessions and rate limiting to a tenant bot"
```

---

### Task 2: ThreadFactory seam

**Files:**
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotRunner.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/bot/RunnerThreadFactoryTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `TelegramBotRunner(TelegramBotModule module, ThreadFactory threadFactory)`; the one-argument constructor stays and delegates with `null`.

Note for the implementer: the runner currently builds two thread factories inline — one naming threads `tg-auth-poll-<username>`, one `tg-auth-work-<username>`, both daemon. When the caller supplies a factory, use it for BOTH pools as-is and do not force `setDaemon` — a supplied factory owns its own thread properties, and a virtual thread is always a daemon anyway. When none is supplied, keep today's behaviour exactly.

- [ ] **Step 1: Write the failing test**

```java
package io.github.dev_abdulhay.telegramauth.bot;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerThreadFactoryTest {

    /** Returns one empty batch, then blocks so the loop cannot spin. */
    static class QuietBot extends TelegramBot {
        final CountDownLatch polled = new CountDownLatch(1);

        QuietBot() { super(java.net.http.HttpClient.newHttpClient(), "123:ABC"); }

        @Override public String getUpdates(long offset, int timeoutSeconds) throws Exception {
            polled.countDown();
            Thread.sleep(200);
            return "{\"ok\":true,\"result\":[]}";
        }
        @Override public String getUpdates(long offset, int timeoutSeconds, List<String> allowed) throws Exception {
            return getUpdates(offset, timeoutSeconds);
        }
    }

    @Test
    void aSuppliedFactoryCreatesEveryThreadTheRunnerUses() throws Exception {
        QuietBot bot = new QuietBot();
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "tenant_bot").bot(bot).build();
        ConcurrentLinkedQueue<String> created = new ConcurrentLinkedQueue<>();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "custom-" + created.size());
            t.setDaemon(true);
            created.add(t.getName());
            return t;
        };

        TelegramBotRunner runner = new TelegramBotRunner(module, factory);
        try {
            runner.start();
            assertThat(bot.polled.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            runner.stop();
        }

        // both the poll thread and the worker thread must come from the supplied factory
        assertThat(created).hasSize(2).allSatisfy(n -> assertThat(n).startsWith("custom-"));
    }

    @Test
    void withoutAFactoryTheRunnerKeepsItsOwnThreadNames() throws Exception {
        QuietBot bot = new QuietBot();
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "legacy_bot").bot(bot).build();

        TelegramBotRunner runner = new TelegramBotRunner(module);
        try {
            runner.start();
            assertThat(bot.polled.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(Thread.getAllStackTraces().keySet())
                    .anySatisfy(t -> assertThat(t.getName()).isEqualTo("tg-auth-poll-legacy_bot"));
        } finally {
            runner.stop();
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=RunnerThreadFactoryTest`
Expected: compilation failure — the two-argument constructor does not exist.

- [ ] **Step 3: Write minimal implementation**

In `TelegramBotRunner`, add the field and constructors:

```java
    private final ThreadFactory threadFactory;

    public TelegramBotRunner(TelegramBotModule module) {
        this(module, null);
    }

    /**
     * @param threadFactory creates the poll and worker threads, or {@code null}
     *                      for the built-in named daemon threads. A host on
     *                      Java 21+ passes {@code Thread.ofVirtual().factory()}
     *                      here; the library itself stays on Java 17 and never
     *                      references a virtual-thread API.
     */
    public TelegramBotRunner(TelegramBotModule module, ThreadFactory threadFactory) {
        this.module = module;
        this.threadFactory = threadFactory;
    }
```

Replace the two inline factories in `start()` with calls to one helper:

```java
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(WORKER_QUEUE_CAPACITY),
                factoryOr("tg-auth-work-" + module.getUsername()),
                TelegramBotRunner::blockUntilQueued);
        worker = pool;
        dispatcher = new BotUpdateDispatcher(module, worker);
        executor = Executors.newSingleThreadExecutor(factoryOr("tg-auth-poll-" + module.getUsername()));
```

and add:

```java
    /**
     * The supplied factory when there is one, otherwise a named daemon factory.
     * A supplied factory is used as-is: it owns its threads' names and daemon
     * status, and forcing {@code setDaemon} on a virtual thread would throw.
     */
    private ThreadFactory factoryOr(String name) {
        if (threadFactory != null) return threadFactory;
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
```

Remove the now-unused `private final TelegramBotModule module;` duplication only if the field was previously assigned in a different constructor — read the file first and keep exactly one assignment.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=RunnerThreadFactoryTest`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotRunner.java \
        src/test/java/io/github/dev_abdulhay/telegramauth/bot/RunnerThreadFactoryTest.java
git commit -m "feat(bot): let a caller supply the runner's ThreadFactory"
```

---

### Task 3: Poll-failure budget

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/bot/PollFailureListener.java`
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotRunner.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/bot/PollFailureBudgetTest.java`

**Interfaces:**
- Consumes: `TelegramBotRunner(TelegramBotModule, ThreadFactory)` from Task 2.
- Produces: `interface PollFailureListener { void onPollFailure(TelegramBotModule module, Duration failingFor); }`; `TelegramBotRunner(TelegramBotModule module, ThreadFactory threadFactory, Duration failureBudget, PollFailureListener listener)`. The two shorter constructors delegate with `null` budget and listener.

Note for the implementer: `null` budget means "never give up", which is exactly today's behaviour — existing hosts must not suddenly find their bots stopping. Measure the budget in **time**, not attempts: a 409 from a competing poller and an ordinary network blip arrive through the same path, so a bot must only be given up on after failing continuously for the whole budget. Reset the failure clock on the first successful poll.

- [ ] **Step 1: Write the failing test**

```java
package io.github.dev_abdulhay.telegramauth.bot;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PollFailureBudgetTest {

    /** Always answers non-ok, as a revoked token would. */
    static class DeadBot extends TelegramBot {
        final AtomicInteger calls = new AtomicInteger();

        DeadBot() { super(java.net.http.HttpClient.newHttpClient(), "123:ABC"); }

        @Override public String getUpdates(long offset, int timeoutSeconds) {
            calls.incrementAndGet();
            return "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}";
        }
        @Override public String getUpdates(long offset, int timeoutSeconds, List<String> allowed) {
            return getUpdates(offset, timeoutSeconds);
        }
    }

    /** Fails once, then succeeds — the failure clock must reset. */
    static class FlakyBot extends TelegramBot {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch succeededTwice = new CountDownLatch(2);

        FlakyBot() { super(java.net.http.HttpClient.newHttpClient(), "123:ABC"); }

        @Override public String getUpdates(long offset, int timeoutSeconds) throws Exception {
            if (calls.incrementAndGet() == 1) {
                return "{\"ok\":false,\"error_code\":409,\"description\":\"Conflict\"}";
            }
            succeededTwice.countDown();
            Thread.sleep(50);
            return "{\"ok\":true,\"result\":[]}";
        }
        @Override public String getUpdates(long offset, int timeoutSeconds, List<String> allowed) throws Exception {
            return getUpdates(offset, timeoutSeconds);
        }
    }

    private static TelegramBotModule module(TelegramBot bot, String username) {
        return TelegramBotModule.builder("123:ABC", username)
                .bot(bot)
                .pollingInterval(Duration.ofMillis(10))
                .build();
    }

    @Test
    void aBotFailingForTheWholeBudgetIsStoppedAndAnnounced() throws Exception {
        DeadBot bot = new DeadBot();
        CountDownLatch notified = new CountDownLatch(1);
        AtomicReference<Duration> reported = new AtomicReference<>();

        TelegramBotRunner runner = new TelegramBotRunner(module(bot, "dead_bot"), null,
                Duration.ofMillis(200), (m, failingFor) -> {
                    reported.set(failingFor);
                    notified.countDown();
                });
        try {
            runner.start();
            assertThat(notified.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            runner.stop();
        }

        assertThat(reported.get()).isGreaterThanOrEqualTo(Duration.ofMillis(200));

        // it really stopped: no further polling after the listener fired
        int afterStop = bot.calls.get();
        Thread.sleep(200);
        assertThat(bot.calls.get()).isEqualTo(afterStop);
    }

    @Test
    void aSingleFailureFollowedBySuccessNeverTripsTheBudget() throws Exception {
        FlakyBot bot = new FlakyBot();
        CountDownLatch notified = new CountDownLatch(1);

        TelegramBotRunner runner = new TelegramBotRunner(module(bot, "flaky_bot"), null,
                Duration.ofMillis(150), (m, failingFor) -> notified.countDown());
        try {
            runner.start();
            assertThat(bot.succeededTwice.await(2, TimeUnit.SECONDS)).isTrue();
            // the budget is 150ms and we have been running longer than that,
            // but the clock reset on the first success
            assertThat(notified.await(300, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            runner.stop();
        }
    }

    @Test
    void withoutABudgetADeadBotKeepsRetryingForever() throws Exception {
        DeadBot bot = new DeadBot();
        CountDownLatch notified = new CountDownLatch(1);

        TelegramBotRunner runner = new TelegramBotRunner(module(bot, "legacy_bot"), null,
                null, (m, failingFor) -> notified.countDown());
        try {
            runner.start();
            assertThat(notified.await(400, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(bot.calls.get()).isGreaterThan(1);
        } finally {
            runner.stop();
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=PollFailureBudgetTest`
Expected: compilation failure — `PollFailureListener` and the four-argument constructor do not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package io.github.dev_abdulhay.telegramauth.bot;

import java.time.Duration;

/**
 * Notified when a runner gives up on a bot that has been failing to poll for its
 * whole failure budget — most often a token the owner revoked in BotFather.
 *
 * <p>Deliberately declared here rather than in a higher-level package: {@code bot}
 * must not know that managed bots or the white-label runtime exist.
 */
@FunctionalInterface
public interface PollFailureListener {

    /**
     * @param failingFor how long polling had been failing without a single success
     */
    void onPollFailure(TelegramBotModule module, Duration failingFor);
}
```

In `TelegramBotRunner`, add the fields and widen the constructors:

```java
    private final Duration failureBudget;
    private final PollFailureListener failureListener;

    public TelegramBotRunner(TelegramBotModule module) {
        this(module, null, null, null);
    }

    public TelegramBotRunner(TelegramBotModule module, ThreadFactory threadFactory) {
        this(module, threadFactory, null, null);
    }

    /**
     * @param failureBudget how long polling may fail continuously before the runner
     *                      stops itself; {@code null} keeps retrying forever, which
     *                      is the behaviour every pre-white-label host has today
     * @param listener      notified once, just before the runner stops
     */
    public TelegramBotRunner(TelegramBotModule module, ThreadFactory threadFactory,
                             Duration failureBudget, PollFailureListener listener) {
        this.module = module;
        this.threadFactory = threadFactory;
        this.failureBudget = failureBudget;
        this.failureListener = listener;
    }
```

In `loop()`, track the failure clock. Add a local above the `while`:

```java
        // start of the current unbroken run of failures, or null while healthy
        Instant failingSince = null;
```

Inside the loop, after `long maxId = dispatcher.dispatch(json);`:

```java
                if (maxId >= 0) {
                    failingSince = null;
                }
                if (maxId > 0) {
                    offset.set(maxId + 1);
                } else if (maxId < 0) {
                    if (failingSince == null) failingSince = Instant.now();
                    if (giveUp(failingSince)) return;
                    Thread.sleep(module.getPollingInterval().toMillis());
                }
```

and in the general `catch (Exception e)` branch, before whatever it does today:

```java
                if (failingSince == null) failingSince = Instant.now();
                if (giveUp(failingSince)) return;
```

Add the helper:

```java
    /**
     * Stops the runner and notifies the listener once the failure budget is spent.
     * Returns {@code true} when the caller should leave the loop.
     */
    private boolean giveUp(Instant failingSince) {
        if (failureBudget == null) return false;
        Duration failingFor = Duration.between(failingSince, Instant.now());
        if (failingFor.compareTo(failureBudget) < 0) return false;
        log.warn("giving up on @{} after {}s of unbroken poll failures",
                module.getUsername(), failingFor.toSeconds());
        // stop() drains the worker, so it must not run on the poll thread itself;
        // running.set(false) ends the loop and the listener owns the teardown
        running.set(false);
        if (failureListener != null) {
            try {
                failureListener.onPollFailure(module, failingFor);
            } catch (RuntimeException e) {
                log.warn("poll-failure listener threw for @{}", module.getUsername(), e);
            }
        }
        return true;
    }
```

Add the `java.time.Instant` and `java.time.Duration` imports if absent.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=PollFailureBudgetTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Run the full suite**

Run: `mvn test`
Expected: BUILD SUCCESS. `TelegramBotRunner` is shared, so `WorkerBackpressureTest` and `TelegramBotRunnerAllowedUpdatesTest` must still pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/dev_abdulhay/telegramauth/bot/PollFailureListener.java \
        src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotRunner.java \
        src/test/java/io/github/dev_abdulhay/telegramauth/bot/PollFailureBudgetTest.java
git commit -m "feat(bot): stop polling a bot that fails for its whole failure budget"
```

---

### Task 4: Runtime contracts

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/whitelabel/RunningBot.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/whitelabel/TenantBotFactory.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/whitelabel/ManagedBotCustomizer.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/whitelabel/RunningBotTest.java`

**Interfaces:**
- Consumes: `ManagedBot` (managedbots), `TelegramBotModule` (bot), `AbstractSessionService` (service), `BaseTelegramUser`/`BaseAuthSession` (entity).
- Produces: `record RunningBot<U, S>(TelegramBotModule module, AbstractSessionService<U, S> sessionService)`; `interface TenantBotFactory<U, S> { RunningBot<U, S> create(ManagedBot bot, String decryptedToken); }`; `interface ManagedBotCustomizer { void customize(TelegramBotModule module, ManagedBot bot); }`.

This task is three small interfaces plus one guard test; there is no behaviour to drive out beyond the guard.

- [ ] **Step 1: Write the failing test**

```java
package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunningBotTest {

    @Test
    void aRunningBotRefusesMissingParts() {
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "tenant_bot").build();

        assertThatThrownBy(() -> new RunningBot<>(null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("module");
        assertThatThrownBy(() -> new RunningBot<>(module, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sessionService");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=RunningBotTest`
Expected: compilation failure — `RunningBot` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.service.AbstractSessionService;

import java.util.Objects;

/**
 * One tenant bot's wiring, as built by a {@link TenantBotFactory}.
 *
 * <p>It carries the session service as well as the module because the registry
 * hands that service back to the host's REST layer later — a bare module would
 * leave the registry holding an untyped service it could not usefully expose.
 */
public record RunningBot<U extends BaseTelegramUser, S extends BaseAuthSession>(
        TelegramBotModule module, AbstractSessionService<U, S> sessionService) {

    public RunningBot {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(sessionService, "sessionService");
    }
}
```

```java
package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBot;

/**
 * Builds the wiring for one managed bot. <b>The host implements this</b> — the
 * library cannot, because {@code AbstractSessionService} and {@code DefaultAuthFlow}
 * are generic over the host's own user and session entities, which the library
 * never sees.
 *
 * <p><b>Resolve the services as prototype-scoped Spring beans, never with a plain
 * {@code new}.</b> A service built with {@code new} is not a Spring bean, so it
 * gets no AOP proxy: {@code @Transactional} silently does nothing, the pessimistic
 * lock in {@code approve}/{@code reject} is released the moment its query returns,
 * and {@code publishAfterCommit} loses its guarantee. All of that compiles, runs,
 * and passes a smoke test — it only corrupts data under concurrency.
 *
 * <p>The implementation must set the bot id on the module:
 * {@code TelegramBotModule.builder(decryptedToken, bot.username()).botUserId(bot.botUserId())}.
 * Without it, sessions carry no tenant and every tenant shares one rate-limit bucket.
 */
@FunctionalInterface
public interface TenantBotFactory<U extends BaseTelegramUser, S extends BaseAuthSession> {

    RunningBot<U, S> create(ManagedBot bot, String decryptedToken);
}
```

```java
package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBot;

/**
 * Optional hook for adding a tenant's own handlers — a support inbox, custom
 * commands, notifications — to a bot the runtime has just built.
 *
 * <p>Called <em>after</em> the auth flow has registered its handlers, so the
 * single-slot handlers it claims are already taken. Which ones depends on the
 * flow's options: {@code onCallbackQuery} when approval or a code step is on,
 * {@code onContact} when contact is required, {@code onText} in {@code TYPED}
 * mode. Route anything that collides through {@link TelegramBotModule#fallback}:
 * the flow forwards every update it does not own there.
 */
@FunctionalInterface
public interface ManagedBotCustomizer {

    void customize(TelegramBotModule module, ManagedBot bot);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=RunningBotTest`
Expected: PASS, 1 test.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/dev_abdulhay/telegramauth/whitelabel/ \
        src/test/java/io/github/dev_abdulhay/telegramauth/whitelabel/RunningBotTest.java
git commit -m "feat(white-label): add the tenant bot factory and customizer contracts"
```

---

### Task 5: Tenant bot registry

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/whitelabel/TenantBotRegistry.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/whitelabel/TenantBotRegistryTest.java`

**Interfaces:**
- Consumes: `RunningBot`, `TenantBotFactory`, `ManagedBotCustomizer` (Task 4); `ManagedBotService#findToken` (managedbots); `TelegramBotRunner(module, threadFactory, budget, listener)` (Tasks 2-3).
- Produces: `TenantBotRegistry<U, S>` with constructor `(ManagedBotService managedBots, TenantBotFactory<U, S> factory, ManagedBotCustomizer customizer, ThreadFactory threadFactory, Duration pollFailureBudget)` and methods `void start(ManagedBot)`, `void stop(long)`, `void restart(ManagedBot)`, `Optional<AbstractSessionService<U, S>> sessionServiceFor(long)`, `Set<Long> running()`, `void stopAll()`.

Note for the implementer: `customizer` and `threadFactory` are nullable. `start` must be idempotent — starting an already-running bot is a no-op, not a second runner. Register the runner's `PollFailureListener` so a dead bot removes itself from the registry.

- [ ] **Step 1: Write the failing test**

```java
package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBot;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.managedbots.AesGcmTokenEncryptor;
import io.github.dev_abdulhay.telegramauth.managedbots.InMemoryManagedBotStore;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBot;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotEvents;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotService;
import io.github.dev_abdulhay.telegramauth.managedbots.TokenEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantBotRegistryTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private InMemoryManagedBotStore store;
    private TokenEncryptor encryptor;
    private ManagedBotService managedBots;
    private List<String> built;

    /** Never touches the network: both getUpdates overloads block briefly and return empty. */
    static class QuietBot extends TelegramBot {
        QuietBot(String token) { super(HttpClient.newHttpClient(), token); }
        @Override public String getUpdates(long offset, int timeoutSeconds) throws Exception {
            Thread.sleep(100);
            return "{\"ok\":true,\"result\":[]}";
        }
        @Override public String getUpdates(long offset, int timeoutSeconds, List<String> allowed) throws Exception {
            return getUpdates(offset, timeoutSeconds);
        }
    }

    private static ManagedBot bot(long id) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ManagedBot(id, "tenant_" + id + "_bot", "Tenant", 7L, null, now, now);
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryManagedBotStore();
        encryptor = new AesGcmTokenEncryptor(KEY);
        built = new ArrayList<>();
        TelegramBotModule manager = TelegramBotModule.builder("999:MANAGER", "manager_bot")
                .bot(new QuietBot("999:MANAGER")).build();
        managedBots = new ManagedBotService(manager, store, encryptor, new ManagedBotEvents() { },
                1, Duration.ZERO);
    }

    /** Stores a bot with a known token so findToken can decrypt it. */
    private ManagedBot storedBot(long id, String token) {
        OffsetDateTime now = OffsetDateTime.now();
        ManagedBot b = new ManagedBot(id, "tenant_" + id + "_bot", "Tenant", 7L,
                encryptor.encrypt(token), now, now);
        store.save(b);
        return b;
    }

    private TenantBotRegistry<DemoU, DemoS> registry(ManagedBotCustomizer customizer) {
        return new TenantBotRegistry<>(managedBots, (b, token) -> {
            built.add(b.botUserId() + ":" + token);
            TelegramBotModule m = TelegramBotModule.builder(token, b.username())
                    .bot(new QuietBot(token))
                    .botUserId(b.botUserId())
                    .build();
            return new RunningBot<>(m, new StubTenantSessionService(m));
        }, customizer, null, null);
    }

    @Test
    void startBuildsTheModuleWithTheDecryptedTokenAndRegistersIt() {
        ManagedBot b = storedBot(555L, "555:CHILD");
        TenantBotRegistry<DemoU, DemoS> registry = registry(null);
        try {
            registry.start(b);

            assertThat(built).containsExactly("555:555:CHILD");
            assertThat(registry.running()).containsExactly(555L);
            assertThat(registry.sessionServiceFor(555L)).isPresent();
        } finally {
            registry.stopAll();
        }
    }

    @Test
    void startingAnAlreadyRunningBotIsANoOp() {
        ManagedBot b = storedBot(555L, "555:CHILD");
        TenantBotRegistry<DemoU, DemoS> registry = registry(null);
        try {
            registry.start(b);
            registry.start(b);

            assertThat(built).hasSize(1);
            assertThat(registry.running()).containsExactly(555L);
        } finally {
            registry.stopAll();
        }
    }

    @Test
    void stopDeregistersTheBot() {
        ManagedBot b = storedBot(555L, "555:CHILD");
        TenantBotRegistry<DemoU, DemoS> registry = registry(null);
        registry.start(b);

        registry.stop(555L);

        assertThat(registry.running()).isEmpty();
        assertThat(registry.sessionServiceFor(555L)).isEmpty();
    }

    @Test
    void restartRebuildsTheModuleWithTheNewToken() {
        ManagedBot b = storedBot(555L, "555:FIRST");
        TenantBotRegistry<DemoU, DemoS> registry = registry(null);
        try {
            registry.start(b);
            ManagedBot rotated = storedBot(555L, "555:SECOND");

            registry.restart(rotated);

            assertThat(built).containsExactly("555:555:FIRST", "555:555:SECOND");
            assertThat(registry.running()).containsExactly(555L);
        } finally {
            registry.stopAll();
        }
    }

    @Test
    void theCustomizerRunsForEveryBotThatStarts() {
        ManagedBot b = storedBot(555L, "555:CHILD");
        List<Long> customized = new ArrayList<>();
        TenantBotRegistry<DemoU, DemoS> registry = registry((m, mb) -> customized.add(mb.botUserId()));
        try {
            registry.start(b);
            assertThat(customized).containsExactly(555L);
        } finally {
            registry.stopAll();
        }
    }

    @Test
    void startingABotWithNoStoredTokenFailsLoudly() {
        TenantBotRegistry<DemoU, DemoS> registry = registry(null);

        assertThatThrownBy(() -> registry.start(bot(999L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("999");
        assertThat(registry.running()).isEmpty();
    }

    @Test
    void sessionServiceForAnUnknownBotIsEmpty() {
        assertThat(registry(null).sessionServiceFor(404L)).isEmpty();
    }
}
```

This test needs two tiny fixtures next to it — a user type, a session type and a stub service. Create them in the same package:

```java
package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;

/** Minimal concrete user for registry tests; never persisted. */
class DemoU extends BaseTelegramUser { }
```

```java
package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;

/** Minimal concrete session for registry tests; never persisted. */
class DemoS extends BaseAuthSession { }
```

```java
package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import io.github.dev_abdulhay.telegramauth.service.AbstractSessionService;

/**
 * A session service that satisfies the type without a database. The registry only
 * ever holds and returns it, so nothing here is exercised.
 */
class StubTenantSessionService extends AbstractSessionService<DemoU, DemoS> {

    StubTenantSessionService(TelegramBotModule module) {
        super(null, DemoS::new, new TokenGenerator(), module);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TenantBotRegistryTest`
Expected: compilation failure — `TenantBotRegistry` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotRunner;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBot;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotService;
import io.github.dev_abdulhay.telegramauth.service.AbstractSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;

/**
 * Owns the tenant bots that are currently polling. One entry per managed bot:
 * its module, its session service and the runner driving it.
 *
 * <p>JVM-local and single-instance by design. Two application instances polling
 * one bot would collide — Telegram answers 409 — so nothing here attempts
 * ownership or leasing.
 */
public class TenantBotRegistry<U extends BaseTelegramUser, S extends BaseAuthSession> {

    private static final Logger log = LoggerFactory.getLogger(TenantBotRegistry.class);

    private record Entry<U extends BaseTelegramUser, S extends BaseAuthSession>(
            RunningBot<U, S> bot, TelegramBotRunner runner) { }

    private final ManagedBotService managedBots;
    private final TenantBotFactory<U, S> factory;
    private final ManagedBotCustomizer customizer;
    private final ThreadFactory threadFactory;
    private final Duration pollFailureBudget;

    private final ConcurrentHashMap<Long, Entry<U, S>> running = new ConcurrentHashMap<>();

    /**
     * @param customizer        optional host hook, run after the auth flow is wired
     * @param threadFactory     optional; a host on Java 21+ passes a virtual-thread factory
     * @param pollFailureBudget how long a bot may fail before it is stopped and
     *                          deregistered; {@code null} retries forever
     */
    public TenantBotRegistry(ManagedBotService managedBots, TenantBotFactory<U, S> factory,
                             ManagedBotCustomizer customizer, ThreadFactory threadFactory,
                             Duration pollFailureBudget) {
        this.managedBots = managedBots;
        this.factory = factory;
        this.customizer = customizer;
        this.threadFactory = threadFactory;
        this.pollFailureBudget = pollFailureBudget;
    }

    /**
     * Builds and starts one tenant bot. Starting a bot that is already running is
     * a no-op rather than a second runner — a re-delivered {@code managed_bot}
     * update must not double the polling.
     *
     * @throws IllegalStateException if no token is stored for the bot
     */
    public void start(ManagedBot bot) {
        long id = bot.botUserId();
        if (running.containsKey(id)) {
            log.debug("tenant bot {} is already running", id);
            return;
        }
        String token = managedBots.findToken(id).orElseThrow(() -> new IllegalStateException(
                "no stored token for managed bot " + id + "; cannot start it"));
        RunningBot<U, S> built = factory.create(bot, token);
        if (customizer != null) {
            customizer.customize(built.module(), bot);
        }
        TelegramBotRunner runner = new TelegramBotRunner(built.module(), threadFactory,
                pollFailureBudget, (module, failingFor) -> {
                    log.warn("tenant bot {} stopped after {}s of poll failures — its token was "
                            + "probably revoked in BotFather", id, failingFor.toSeconds());
                    running.remove(id);
                });
        runner.start();
        running.put(id, new Entry<>(built, runner));
        log.info("tenant bot {} (@{}) started", id, built.module().getUsername());
    }

    /** Stops and deregisters one tenant bot; unknown ids are ignored. */
    public void stop(long botUserId) {
        Entry<U, S> entry = running.remove(botUserId);
        if (entry == null) return;
        entry.runner().stop();
        log.info("tenant bot {} stopped", botUserId);
    }

    /**
     * Stops the bot and starts it again from its current stored token.
     *
     * <p>A rotation cannot be applied in place: {@code TelegramBot} holds its token
     * in a final field. Two costs come with the restart — in-flight logins on this
     * tenant are lost, because the flow's pending-login map is JVM-local, and the
     * new runner starts at offset 0, so Telegram may redeliver updates the old one
     * had handled but not confirmed.
     */
    public void restart(ManagedBot bot) {
        stop(bot.botUserId());
        start(bot);
    }

    /** The session service driving this tenant, or empty when it is not running. */
    public Optional<AbstractSessionService<U, S>> sessionServiceFor(long botUserId) {
        Entry<U, S> entry = running.get(botUserId);
        return Optional.ofNullable(entry).map(e -> e.bot().sessionService());
    }

    /** Ids of every tenant bot currently polling. */
    public Set<Long> running() {
        return Set.copyOf(running.keySet());
    }

    public void stopAll() {
        running.keySet().forEach(this::stop);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=TenantBotRegistryTest`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/dev_abdulhay/telegramauth/whitelabel/TenantBotRegistry.java \
        src/test/java/io/github/dev_abdulhay/telegramauth/whitelabel/
git commit -m "feat(white-label): add the tenant bot registry"
```

---

### Task 6: Event bridge

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/whitelabel/TenantBotEventBridge.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/whitelabel/TenantBotEventBridgeTest.java`

**Interfaces:**
- Consumes: `TenantBotRegistry` (Task 5), `ManagedBotEvents` (managedbots).
- Produces: `TenantBotEventBridge<U, S> implements ManagedBotEvents`, constructor `(TenantBotRegistry<U, S> registry)`.

Note for the implementer: every callback must swallow its own exceptions. These run on the manager bot's update worker thread inside `ManagedBotService.handleUpdate`; an escaping exception there would be logged by the dispatcher but could still abandon work for that update. One tenant failing to start must not disturb the manager bot.

- [ ] **Step 1: Write the failing test**

```java
package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBot;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class TenantBotEventBridgeTest {

    /** Records what the bridge asked for, and can be told to fail. */
    static class RecordingRegistry extends TenantBotRegistry<DemoU, DemoS> {
        final List<String> calls = new ArrayList<>();
        boolean failOnStart;

        RecordingRegistry() { super(null, null, null, null, null); }

        @Override public void start(ManagedBot bot) {
            calls.add("start:" + bot.botUserId());
            if (failOnStart) throw new IllegalStateException("no token");
        }
        @Override public void stop(long botUserId) { calls.add("stop:" + botUserId); }
        @Override public void restart(ManagedBot bot) { calls.add("restart:" + bot.botUserId()); }
    }

    private static ManagedBot bot(long id) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ManagedBot(id, "tenant_bot", "Tenant", 7L, "enc", now, now);
    }

    @Test
    void lifecycleEventsDriveTheRegistry() {
        RecordingRegistry registry = new RecordingRegistry();
        TenantBotEventBridge<DemoU, DemoS> bridge = new TenantBotEventBridge<>(registry);

        bridge.onCreated(bot(555L));
        bridge.onTokenRotated(bot(555L));
        bridge.onDecommissioned(555L);

        assertThat(registry.calls).containsExactly("start:555", "restart:555", "stop:555");
    }

    @Test
    void aFailedTenantNeverEscapesIntoTheManagerBot() {
        RecordingRegistry registry = new RecordingRegistry();
        registry.failOnStart = true;
        TenantBotEventBridge<DemoU, DemoS> bridge = new TenantBotEventBridge<>(registry);

        assertThatCode(() -> bridge.onCreated(bot(555L))).doesNotThrowAnyException();
        assertThat(registry.calls).containsExactly("start:555");
    }

    @Test
    void aFailedTokenFetchStartsNothing() {
        RecordingRegistry registry = new RecordingRegistry();
        TenantBotEventBridge<DemoU, DemoS> bridge = new TenantBotEventBridge<>(registry);

        bridge.onTokenFetchFailed(555L, 7L, new IllegalStateException("boom"));

        assertThat(registry.calls).isEmpty();
    }
}
```

For this test the registry's methods must be overridable — declare `start`, `stop` and `restart` non-final (they already are) and make sure `TenantBotRegistry`'s constructor tolerates null arguments, which it does because nothing is dereferenced in the constructor.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TenantBotEventBridgeTest`
Expected: compilation failure — `TenantBotEventBridge` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBot;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns managed-bot lifecycle events into registry operations: a created bot
 * starts polling, a rotated token restarts it, a decommissioned bot stops.
 *
 * <p>Every callback swallows its own failures. These run on the manager bot's
 * update worker thread, and one tenant that cannot start must not disturb the
 * manager bot or the tenants that can.
 */
public class TenantBotEventBridge<U extends BaseTelegramUser, S extends BaseAuthSession>
        implements ManagedBotEvents {

    private static final Logger log = LoggerFactory.getLogger(TenantBotEventBridge.class);

    private final TenantBotRegistry<U, S> registry;

    public TenantBotEventBridge(TenantBotRegistry<U, S> registry) {
        this.registry = registry;
    }

    @Override
    public void onCreated(ManagedBot bot) {
        guard("start", bot.botUserId(), () -> registry.start(bot));
    }

    @Override
    public void onTokenRotated(ManagedBot bot) {
        guard("restart", bot.botUserId(), () -> registry.restart(bot));
    }

    @Override
    public void onDecommissioned(long botUserId) {
        guard("stop", botUserId, () -> registry.stop(botUserId));
    }

    private void guard(String action, long botUserId, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            log.warn("could not {} tenant bot {}", action, botUserId, e);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=TenantBotEventBridgeTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/dev_abdulhay/telegramauth/whitelabel/TenantBotEventBridge.java \
        src/test/java/io/github/dev_abdulhay/telegramauth/whitelabel/TenantBotEventBridgeTest.java
git commit -m "feat(white-label): bridge managed-bot events to the registry"
```

---

### Task 7: Startup restore

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/whitelabel/TenantBotLifecycle.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/whitelabel/TenantBotLifecycleTest.java`

**Interfaces:**
- Consumes: `TenantBotRegistry` (Task 5), `ManagedBotTokenStore` (managedbots).
- Produces: `TenantBotLifecycle<U, S>` with constructor `(ManagedBotTokenStore store, TenantBotRegistry<U, S> registry, boolean restoreOnStartup)`, an `@EventListener(ApplicationReadyEvent.class)` `startAll()` and a `@PreDestroy` `stopAll()`.

Note for the implementer: the whole point of this task is that **one bad tenant cannot stop the others**. Wrap each `start` in its own try/catch and keep going. Also guard against a double `startAll` the way `TelegramBotLifecycle` does, with an `AtomicBoolean`.

- [ ] **Step 1: Write the failing test**

```java
package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.managedbots.InMemoryManagedBotStore;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBot;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TenantBotLifecycleTest {

    static class RecordingRegistry extends TenantBotRegistry<DemoU, DemoS> {
        final List<Long> started = new ArrayList<>();
        final List<Long> stopped = new ArrayList<>();
        long failFor = -1;

        RecordingRegistry() { super(null, null, null, null, null); }

        @Override public void start(ManagedBot bot) {
            if (bot.botUserId() == failFor) throw new IllegalStateException("cannot decrypt");
            started.add(bot.botUserId());
        }
        @Override public void stopAll() { stopped.addAll(started); }
    }

    private static ManagedBot bot(long id) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ManagedBot(id, "tenant_bot", "Tenant", 7L, "enc", now, now);
    }

    private static InMemoryManagedBotStore storeWith(long... ids) {
        InMemoryManagedBotStore store = new InMemoryManagedBotStore();
        for (long id : ids) store.save(bot(id));
        return store;
    }

    @Test
    void everyStoredBotIsStartedWhenTheApplicationIsReady() {
        RecordingRegistry registry = new RecordingRegistry();
        new TenantBotLifecycle<>(storeWith(1L, 2L, 3L), registry, true).startAll();

        assertThat(registry.started).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void oneTenantThatCannotStartDoesNotStopTheRest() {
        RecordingRegistry registry = new RecordingRegistry();
        registry.failFor = 2L;

        new TenantBotLifecycle<>(storeWith(1L, 2L, 3L), registry, true).startAll();

        // 2 blew up; 1 and 3 must still be running
        assertThat(registry.started).containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    void restoreCanBeTurnedOff() {
        RecordingRegistry registry = new RecordingRegistry();
        new TenantBotLifecycle<>(storeWith(1L, 2L), registry, false).startAll();

        assertThat(registry.started).isEmpty();
    }

    @Test
    void startAllIsIdempotent() {
        RecordingRegistry registry = new RecordingRegistry();
        TenantBotLifecycle<DemoU, DemoS> lifecycle =
                new TenantBotLifecycle<>(storeWith(1L), registry, true);

        lifecycle.startAll();
        lifecycle.startAll();

        assertThat(registry.started).containsExactly(1L);
    }

    @Test
    void shutdownStopsEverything() {
        RecordingRegistry registry = new RecordingRegistry();
        TenantBotLifecycle<DemoU, DemoS> lifecycle =
                new TenantBotLifecycle<>(storeWith(1L, 2L), registry, true);
        lifecycle.startAll();

        lifecycle.stopAll();

        assertThat(registry.stopped).containsExactlyInAnyOrder(1L, 2L);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TenantBotLifecycleTest`
Expected: compilation failure — `TenantBotLifecycle` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBot;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotTokenStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Brings the stored tenant bots back up when the application is ready and stops
 * them on shutdown.
 */
public class TenantBotLifecycle<U extends BaseTelegramUser, S extends BaseAuthSession> {

    private static final Logger log = LoggerFactory.getLogger(TenantBotLifecycle.class);

    private final ManagedBotTokenStore store;
    private final TenantBotRegistry<U, S> registry;
    private final boolean restoreOnStartup;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public TenantBotLifecycle(ManagedBotTokenStore store, TenantBotRegistry<U, S> registry,
                              boolean restoreOnStartup) {
        this.store = store;
        this.registry = registry;
        this.restoreOnStartup = restoreOnStartup;
    }

    /**
     * Starts every stored bot. Each one is attempted independently: a token that
     * no longer decrypts, a factory that throws, or a runner that fails to start
     * costs that tenant only. Aborting the loop would let a single bad row leave
     * the application with no bots at all.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startAll() {
        if (!restoreOnStartup) {
            log.info("tenant bot restore is disabled");
            return;
        }
        if (!started.compareAndSet(false, true)) return;

        List<ManagedBot> bots = store.findAll();
        int ok = 0;
        for (ManagedBot bot : bots) {
            try {
                registry.start(bot);
                ok++;
            } catch (RuntimeException e) {
                log.warn("could not restore tenant bot {}; continuing with the rest",
                        bot.botUserId(), e);
            }
        }
        log.info("tenant bots restored: {} of {}", ok, bots.size());
    }

    @PreDestroy
    public void stopAll() {
        registry.stopAll();
        started.set(false);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=TenantBotLifecycleTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/dev_abdulhay/telegramauth/whitelabel/TenantBotLifecycle.java \
        src/test/java/io/github/dev_abdulhay/telegramauth/whitelabel/TenantBotLifecycleTest.java
git commit -m "feat(white-label): restore tenant bots on startup"
```

---

### Task 8: Configuration and auto-configuration

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/whitelabel/TelegramWhiteLabelProperties.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/whitelabel/TelegramWhiteLabelAutoConfiguration.java`
- Modify: `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `src/test/java/com/example/demo/WhiteLabelAutoConfigTest.java`

**Interfaces:**
- Consumes: everything from Tasks 4-7.
- Produces: `telegram.white-label.*` binding and beans `TenantBotRegistry`, `TenantBotEventBridge` (as the `ManagedBotEvents` bean), `TenantBotLifecycle`.

Note for the implementer: the imports file currently has two lines; append a third. The white-label auto-configuration must be processed **before** `TelegramManagedBotsAutoConfiguration`, because that one registers a no-op `ManagedBotEvents` under `@ConditionalOnMissingBean` and would win the race otherwise — use `@AutoConfiguration(before = TelegramManagedBotsAutoConfiguration.class)`. When white-label is on, the library owns the `ManagedBotEvents` bean; a host wanting its own per-bot wiring uses `ManagedBotCustomizer`, and this must be said in the README.

- [ ] **Step 1: Write the failing test**

```java
package com.example.demo;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.managedbots.InMemoryManagedBotStore;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotEvents;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotTokenStore;
import io.github.dev_abdulhay.telegramauth.managedbots.TelegramManagedBotsAutoConfiguration;
import io.github.dev_abdulhay.telegramauth.whitelabel.RunningBot;
import io.github.dev_abdulhay.telegramauth.whitelabel.TelegramWhiteLabelAutoConfiguration;
import io.github.dev_abdulhay.telegramauth.whitelabel.TenantBotEventBridge;
import io.github.dev_abdulhay.telegramauth.whitelabel.TenantBotFactory;
import io.github.dev_abdulhay.telegramauth.whitelabel.TenantBotRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class WhiteLabelAutoConfigTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Configuration
    static class HostBeans {
        @Bean TelegramBotModule module() {
            return TelegramBotModule.builder("123:ABC", "manager_bot").build();
        }
        @Bean ManagedBotTokenStore store() {
            return new InMemoryManagedBotStore();
        }
        @Bean TenantBotFactory<DemoUser, DemoSession> factory() {
            return (bot, token) -> new RunningBot<>(
                    TelegramBotModule.builder(token, bot.username()).botUserId(bot.botUserId()).build(),
                    null);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TelegramWhiteLabelAutoConfiguration.class,
                    TelegramManagedBotsAutoConfiguration.class))
            .withUserConfiguration(HostBeans.class)
            .withPropertyValues("telegram.managed-bots.enabled=true",
                    "telegram.managed-bots.encryption-key=" + KEY);

    @Test
    void theRuntimeIsOffByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(TenantBotRegistry.class));
    }

    @Test
    void enablingItWiresTheRegistryAndOwnsTheEventsBean() {
        runner.withPropertyValues("telegram.white-label.enabled=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(TenantBotRegistry.class);
            // the bridge must beat managed-bots' no-op ManagedBotEvents
            assertThat(ctx.getBean(ManagedBotEvents.class)).isInstanceOf(TenantBotEventBridge.class);
        });
    }

    @Test
    void enablingItWithoutAFactoryFailsTheContext() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        TelegramWhiteLabelAutoConfiguration.class,
                        TelegramManagedBotsAutoConfiguration.class))
                .withPropertyValues("telegram.white-label.enabled=true",
                        "telegram.managed-bots.enabled=true",
                        "telegram.managed-bots.encryption-key=" + KEY)
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void propertiesBindWithTheirDefaults() {
        runner.withPropertyValues("telegram.white-label.enabled=true").run(ctx -> {
            var props = ctx.getBean(io.github.dev_abdulhay.telegramauth.whitelabel
                    .TelegramWhiteLabelProperties.class);
            assertThat(props.isRestoreOnStartup()).isTrue();
            assertThat(props.getPollFailureBudget()).isEqualTo(java.time.Duration.ofMinutes(5));
        });
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=WhiteLabelAutoConfigTest`
Expected: compilation failure — the auto-configuration and properties classes do not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package io.github.dev_abdulhay.telegramauth.whitelabel;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * White-label runtime settings. A separate namespace from {@code telegram.auth}
 * and {@code telegram.managed-bots}: the runtime is opt-in on top of both.
 */
@ConfigurationProperties(prefix = "telegram.white-label")
public class TelegramWhiteLabelProperties {

    /** Opt-in switch for the whole runtime. */
    private boolean enabled = false;

    /** Start every stored tenant bot when the application becomes ready. */
    private boolean restoreOnStartup = true;

    /**
     * How long a tenant bot may fail to poll continuously before it is stopped and
     * deregistered — usually a token the owner revoked in BotFather. Measured in
     * time rather than attempts so a brief network outage never kills a bot.
     */
    private Duration pollFailureBudget = Duration.ofMinutes(5);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isRestoreOnStartup() { return restoreOnStartup; }
    public void setRestoreOnStartup(boolean restoreOnStartup) { this.restoreOnStartup = restoreOnStartup; }
    public Duration getPollFailureBudget() { return pollFailureBudget; }
    public void setPollFailureBudget(Duration pollFailureBudget) { this.pollFailureBudget = pollFailureBudget; }
}
```

```java
package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotService;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotTokenStore;
import io.github.dev_abdulhay.telegramauth.managedbots.TelegramManagedBotsAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.ThreadFactory;

/**
 * Wires the white-label runtime when {@code telegram.white-label.enabled=true}.
 *
 * <p>Ordered before the managed-bots auto-configuration on purpose: that one
 * registers a no-op {@code ManagedBotEvents} under {@code @ConditionalOnMissingBean},
 * and the event bridge has to win. When this runtime is on, the library owns the
 * events bean; a host adding its own per-bot wiring uses {@link ManagedBotCustomizer}.
 */
@AutoConfiguration(before = TelegramManagedBotsAutoConfiguration.class)
@ConditionalOnProperty(prefix = "telegram.white-label", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TelegramWhiteLabelProperties.class)
public class TelegramWhiteLabelAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public <U extends BaseTelegramUser, S extends BaseAuthSession> TenantBotRegistry<U, S>
    tenantBotRegistry(ManagedBotService managedBots,
                      ObjectProvider<TenantBotFactory<U, S>> factory,
                      ObjectProvider<ManagedBotCustomizer> customizer,
                      ObjectProvider<ThreadFactory> threadFactory,
                      TelegramWhiteLabelProperties properties) {
        TenantBotFactory<U, S> f = factory.getIfAvailable();
        if (f == null) {
            throw new IllegalStateException(
                    "a TenantBotFactory bean is required when telegram.white-label.enabled=true; "
                            + "only the host can build a session service for its own entity types");
        }
        return new TenantBotRegistry<>(managedBots, f, customizer.getIfAvailable(),
                threadFactory.getIfAvailable(), properties.getPollFailureBudget());
    }

    @Bean
    @ConditionalOnMissingBean
    public <U extends BaseTelegramUser, S extends BaseAuthSession> TenantBotEventBridge<U, S>
    tenantBotEventBridge(TenantBotRegistry<U, S> registry) {
        return new TenantBotEventBridge<>(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public <U extends BaseTelegramUser, S extends BaseAuthSession> TenantBotLifecycle<U, S>
    tenantBotLifecycle(ManagedBotTokenStore store, TenantBotRegistry<U, S> registry,
                       TelegramWhiteLabelProperties properties) {
        return new TenantBotLifecycle<>(store, registry, properties.isRestoreOnStartup());
    }
}
```

Append to `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
io.github.dev_abdulhay.telegramauth.whitelabel.TelegramWhiteLabelAutoConfiguration
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=WhiteLabelAutoConfigTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Run the full suite**

Run: `mvn test`
Expected: BUILD SUCCESS. The new auto-configuration loads for every existing test, so it must be inert while disabled.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/dev_abdulhay/telegramauth/whitelabel/TelegramWhiteLabelProperties.java \
        src/main/java/io/github/dev_abdulhay/telegramauth/whitelabel/TelegramWhiteLabelAutoConfiguration.java \
        src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports \
        src/test/java/com/example/demo/WhiteLabelAutoConfigTest.java
git commit -m "feat(white-label): add opt-in auto-configuration"
```

---

### Task 9: Prove `@Transactional` survives the prototype-bean path

**Files:**
- Test: `src/test/java/com/example/demo/TenantServiceTransactionTest.java`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing — this task exists purely to lock the spec's most dangerous requirement.

Note for the implementer: this is the highest-value test in the plan. The spec forbids building tenant services with `new` because they then get no AOP proxy and `@Transactional` silently does nothing. That failure mode compiles, runs, and passes a smoke test. This test proves both halves: a prototype-scoped bean **is** transactional, and a `new` instance **is not**. If the second assertion ever starts failing, the requirement has become unnecessary and the spec should be revisited.

- [ ] **Step 1: Write the test**

```java
package com.example.demo;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = DemoApp.class,
        properties = {"telegram.auth.enabled=true", "spring.liquibase.enabled=false"})
class TenantServiceTransactionTest {

    /** Records whether a transaction was actually active inside a @Transactional method. */
    static class ProbeSessionService extends DemoSessionService {
        Boolean sawTransaction;

        ProbeSessionService(DemoSessionRepository repo, TokenGenerator tg, TelegramBotModule module) {
            super(repo, tg, module);
        }

        @Override
        public CreatedSession create(String ipAddress, String userAgent) {
            sawTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            return super.create(ipAddress, userAgent);
        }
    }

    @TestConfiguration
    static class ProtoConfig {
        @Bean
        @Scope("prototype")
        ProbeSessionService probeSessionService(DemoSessionRepository repo, TokenGenerator tg) {
            return new ProbeSessionService(repo, tg,
                    TelegramBotModule.builder("123:ABC", "tenant_bot").botUserId(555L).build());
        }
    }

    @Autowired
    private ObjectProvider<ProbeSessionService> provider;

    @Autowired
    private DemoSessionRepository repo;

    @Autowired
    private TokenGenerator tokenGenerator;

    @Test
    void aPrototypeScopedTenantServiceIsTransactional() {
        ProbeSessionService service = provider.getObject();

        service.create("1.2.3.4", "ua");

        assertThat(service.sawTransaction)
                .as("a prototype-scoped bean keeps its AOP proxy, so @Transactional applies")
                .isTrue();
    }

    @Test
    void aServiceBuiltWithNewIsNotTransactional() {
        ProbeSessionService service = new ProbeSessionService(repo, tokenGenerator,
                TelegramBotModule.builder("123:ABC", "tenant_bot").botUserId(556L).build());

        service.create("1.2.3.4", "ua");

        // this is exactly why TenantBotFactory must not use `new`: the annotation
        // is still on the method, it just does nothing
        assertThat(service.sawTransaction)
                .as("a hand-built service has no proxy, so @Transactional silently does nothing")
                .isFalse();
    }
}
```

If `DemoSessionService`'s `create` is not overridable, or its constructor is not visible from this package, adjust the probe to extend whatever the demo service exposes — but keep both assertions and their `as(...)` descriptions intact, because those descriptions are what tell the next reader why the requirement exists.

- [ ] **Step 2: Run the test**

Run: `mvn test -Dtest=TenantServiceTransactionTest`
Expected: PASS, 2 tests. If the second test fails — that is, a hand-built service turns out to be transactional after all — stop and report it: the spec's prototype-bean requirement would no longer be justified.

- [ ] **Step 3: Run the full suite**

Run: `mvn test`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/example/demo/TenantServiceTransactionTest.java
git commit -m "test(white-label): prove tenant services need the prototype-bean path"
```

---

### Task 10: Documentation

**Files:**
- Modify: `README.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: the finished public API from Tasks 1-8.
- Produces: nothing in code.

Note for the implementer: the project's CLAUDE.md makes README updates mandatory for user-visible changes, and every snippet must be verified against the real classes — read them, do not guess. Place the new section after the Managed bots section. The CHANGELOG already has an `## [Unreleased]` block from the managed-bots work; add to it rather than creating a second one.

- [ ] **Step 1: Write the README section**

Cover, in this order:

1. What the runtime does in two sentences, and that it requires managed bots to be enabled as well.
2. The `TenantBotFactory` a host must implement, with a worked snippet — and **the prototype-bean warning in the strongest terms**: a service built with `new` gets no AOP proxy, so `@Transactional` does nothing, the pessimistic lock in approve/reject stops serialising, and `publishAfterCommit` loses its guarantee. State that this compiles and passes a smoke test, and only corrupts data under concurrency.
3. That the factory must call `.botUserId(bot.botUserId())`, and what breaks if it does not: sessions carry no tenant and every tenant shares one rate-limit bucket.
4. The config table: `telegram.white-label.enabled` (false), `restore-on-startup` (true), `poll-failure-budget` (5m).
5. Routing a login to the right tenant: the host resolves its own tenant (subdomain, header, path) and calls `registry.sessionServiceFor(botUserId)`, which is empty for a bot that is not running.
6. `ManagedBotCustomizer` for a tenant's own commands, plus which single-slot handlers the auth flow already claims under which options, and that anything colliding routes through `fallback(...)`.
7. Rotation and restart costs: in-flight logins on that tenant are lost, and Telegram may redeliver unconfirmed updates.
8. Token death: a bot failing for the whole budget is stopped and deregistered, and how to bring it back.
9. Threading and the honest ceiling: two platform threads per bot; on Java 21+ supply `Thread.ofVirtual().name("tg-tenant-", 0).factory()` as a `ThreadFactory` bean. State that the library stays on Java 17 and never calls a virtual-thread API, and that the practical ceiling is untested — the likelier limit is simultaneous long-poll connections to Telegram from one address, not the threads.
10. That when the runtime is enabled the library owns the `ManagedBotEvents` bean, so host hooks belong in `ManagedBotCustomizer`.
11. The single-instance constraint, stated plainly: two application instances polling one bot collide with a 409, so this must not be scaled horizontally without a follow-up design.

- [ ] **Step 2: Write the CHANGELOG entry**

Add to the existing `## [Unreleased]` block. Under `### Added`: the `whitelabel` package, `TenantBotFactory`, `RunningBot`, `TenantBotRegistry`, `TenantBotEventBridge`, `TenantBotLifecycle`, `ManagedBotCustomizer`, `PollFailureListener`, the new `TelegramBotRunner` constructors, `TelegramBotModule#botUserId`, `BaseAuthSession#botUserId`, the per-tenant rate-limit query, and the `telegram.white-label.*` properties.

Under `### Changed`: sessions created through a module carrying a bot id are now rate-limited per tenant instead of across the whole table. Note that `bot_user_id` is a new nullable column on the session table — additive, no backfill needed — and that hosts should index it alongside `ip_address` if they enable white-label.

- [ ] **Step 3: Verify every snippet against the code**

Run: `mvn test`
Then re-read each class named in the README and confirm method names, parameter order and defaults match. Fix the docs, never the code, if they disagree.

- [ ] **Step 4: Commit**

```bash
git add README.md CHANGELOG.md
git commit -m "docs(white-label): document the tenant bot runtime"
```

---

## Plan self-review

- **Spec coverage:** package boundary and contracts (T4), registry with start/stop/restart/sessionServiceFor (T5), event bridge (T6), startup restore with per-tenant failure isolation (T7), `bot_user_id` and per-tenant rate limiting (T1), ThreadFactory seam (T2), poll-failure budget and `PollFailureListener` (T3), opt-in configuration with the fail-fast factory rule (T8), the prototype-bean proof (T9), README and CHANGELOG (T10). Every spec section maps to a task.
- **Type consistency:** `RunningBot<U, S>` is defined once in T4 and used with the same component order in T5, T8 and T9. `TenantBotRegistry`'s five-argument constructor is identical in T5, T6's test double and T8. `TenantBotFactory#create(ManagedBot, String)` returns `RunningBot<U, S>` everywhere. `PollFailureListener#onPollFailure(TelegramBotModule, Duration)` matches between T3 and T5.
- **Ordering:** T1-T3 touch shared code and land first so the runtime builds on finished seams. T9 depends on T1's `botUserId` builder method and must not be moved earlier.
- **Deliberate omissions:** no multi-instance lease, no webhook mode, no async poller, no cross-tenant user sharing — all named out of scope in the spec.
