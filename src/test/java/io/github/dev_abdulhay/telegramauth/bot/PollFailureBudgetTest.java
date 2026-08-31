package io.github.dev_abdulhay.telegramauth.bot;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /**
     * Delivers one real update — which is what makes the runner create its worker
     * thread — and only then goes permanently non-ok, as a token revoked while the
     * bot was live would. Both of the runner's threads therefore exist by the time
     * the budget trips.
     */
    static class DyingBot extends TelegramBot {
        private final AtomicBoolean firstCall = new AtomicBoolean(true);

        DyingBot() { super(java.net.http.HttpClient.newHttpClient(), "123:ABC"); }

        @Override public String getUpdates(long offset, int timeoutSeconds) {
            if (firstCall.compareAndSet(true, false)) {
                return "{\"ok\":true,\"result\":[{\"update_id\":1,\"message\":{\"text\":\"/start\"}}]}";
            }
            return "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}";
        }
        @Override public String getUpdates(long offset, int timeoutSeconds, List<String> allowed) {
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

    /**
     * Giving up must take the runner's threads with it. Both pools are created by
     * {@code start()} but only {@code stop()} tears them down, so a loop that
     * merely returned would leak a poll thread — and a worker thread once one
     * update has been handled — for the life of the JVM, once per revoked token.
     */
    @Test
    void givingUpLeavesNoThreadBehind() throws Exception {
        // distinctive enough that a name match can only be this runner's thread
        String username = "leaky_dying_bot_t3";
        DyingBot bot = new DyingBot();
        TelegramBotModule module = module(bot, username);
        CountDownLatch handled = new CountDownLatch(1);
        module.command("/start", u -> handled.countDown());
        CountDownLatch notified = new CountDownLatch(1);

        TelegramBotRunner runner = new TelegramBotRunner(module, null,
                Duration.ofMillis(150), (m, failingFor) -> notified.countDown());
        try {
            runner.start();
            // the worker thread exists only after a real update has been dispatched
            assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(threadNamesContaining(username)).isNotEmpty();
            assertThat(notified.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            runner.stop();
        }

        List<String> survivors = threadNamesContaining(username);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!survivors.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
            survivors = threadNamesContaining(username);
        }
        assertThat(survivors).isEmpty();
    }

    private static List<String> threadNamesContaining(String fragment) {
        return Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(n -> n.contains(fragment))
                .toList();
    }
}
