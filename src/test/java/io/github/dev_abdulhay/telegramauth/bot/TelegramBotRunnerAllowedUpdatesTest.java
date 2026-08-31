package io.github.dev_abdulhay.telegramauth.bot;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
}
