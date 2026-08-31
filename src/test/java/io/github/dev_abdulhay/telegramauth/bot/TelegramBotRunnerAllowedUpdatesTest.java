package io.github.dev_abdulhay.telegramauth.bot;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves which {@code TelegramBot#getUpdates} overload {@link TelegramBotRunner#loop()}
 * actually calls. {@code TelegramBot}'s class javadoc advertises that its methods are
 * overridable so hosts/tests can substitute behaviour (see {@code DemoTgConfig}, which
 * overrides only the 2-arg overload to avoid real network calls in tests) — the runner
 * must keep calling that overload when no {@code allowed_updates} list applies, never the
 * 3-arg one with a null list, or such overrides become dead code and the runner falls
 * through to the base implementation's real HTTP call.
 */
class TelegramBotRunnerAllowedUpdatesTest {

    /**
     * Records which overload was invoked, then throws {@link InterruptedException} so
     * {@link TelegramBotRunner#loop()} breaks out immediately after the first call —
     * deterministic, no busy-spin, no sleep to wait out.
     */
    private static final class RecordingBot extends TelegramBot {
        final CountDownLatch called = new CountDownLatch(1);
        volatile String overloadUsed;
        volatile List<String> allowedSeen;

        RecordingBot() { super(HttpClient.newHttpClient(), "123:ABC"); }

        @Override
        public String getUpdates(long offset, int timeoutSeconds) throws Exception {
            overloadUsed = "two-arg";
            called.countDown();
            throw new InterruptedException("test double: stop the loop after the first call");
        }

        @Override
        public String getUpdates(long offset, int timeoutSeconds, List<String> allowedUpdates) throws Exception {
            overloadUsed = "three-arg";
            allowedSeen = allowedUpdates;
            called.countDown();
            throw new InterruptedException("test double: stop the loop after the first call");
        }
    }

    @Test
    void noManagedBotHandlerCallsTheTwoArgOverload() throws Exception {
        RecordingBot bot = new RecordingBot();
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "runner_bot").bot(bot).build();
        TelegramBotRunner runner = new TelegramBotRunner(module);

        runner.start();
        try {
            assertThat(bot.called.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            runner.stop();
        }

        assertThat(bot.overloadUsed).isEqualTo("two-arg");
    }

    @Test
    void aManagedBotHandlerCallsTheThreeArgOverloadWithTheFullList() throws Exception {
        RecordingBot bot = new RecordingBot();
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "runner_bot").bot(bot).build();
        module.onManagedBot(u -> { });
        TelegramBotRunner runner = new TelegramBotRunner(module);

        runner.start();
        try {
            assertThat(bot.called.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            runner.stop();
        }

        assertThat(bot.overloadUsed).isEqualTo("three-arg");
        assertThat(bot.allowedSeen).containsExactly("message", "callback_query", "managed_bot");
    }

    /**
     * The {@code managed_bot} handler is claimed by an auto-configured singleton the
     * container may build after polling has started. Computing {@code allowed_updates}
     * once before the loop would pin it to whatever was registered at start-up, and
     * {@code managed_bot} would then never be requested — silently, with no error and
     * no log. So the list is recomputed each iteration.
     */
    private static final class LateHandlerBot extends TelegramBot {
        final CountDownLatch firstPoll = new CountDownLatch(1);
        final CountDownLatch handlerRegistered = new CountDownLatch(1);
        final CountDownLatch secondPoll = new CountDownLatch(1);
        volatile List<String> allowedOnSecondPoll;
        private final AtomicInteger polls = new AtomicInteger();

        LateHandlerBot() { super(HttpClient.newHttpClient(), "123:ABC"); }

        @Override
        public String getUpdates(long offset, int timeoutSeconds) throws Exception {
            if (polls.incrementAndGet() == 1) {
                firstPoll.countDown();
                handlerRegistered.await(2, TimeUnit.SECONDS);
                return "{\"ok\":true,\"result\":[]}";
            }
            // still the 2-arg overload on the second poll: the snapshot never refreshed
            secondPoll.countDown();
            throw new InterruptedException("test double: stop the loop");
        }

        @Override
        public String getUpdates(long offset, int timeoutSeconds, List<String> allowedUpdates) throws Exception {
            polls.incrementAndGet();
            allowedOnSecondPoll = allowedUpdates;
            secondPoll.countDown();
            throw new InterruptedException("test double: stop the loop");
        }
    }

    @Test
    void aManagedBotHandlerRegisteredAfterStartIsPickedUpByTheNextPoll() throws Exception {
        LateHandlerBot bot = new LateHandlerBot();
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "runner_bot").bot(bot).build();
        TelegramBotRunner runner = new TelegramBotRunner(module);

        runner.start();
        try {
            assertThat(bot.firstPoll.await(2, TimeUnit.SECONDS)).isTrue();
            module.onManagedBot(u -> { });
            bot.handlerRegistered.countDown();
            assertThat(bot.secondPoll.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            runner.stop();
        }

        assertThat(bot.allowedOnSecondPoll).containsExactly("message", "callback_query", "managed_bot");
    }
}
