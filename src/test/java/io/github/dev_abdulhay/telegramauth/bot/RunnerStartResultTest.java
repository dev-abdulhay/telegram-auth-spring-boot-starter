package io.github.dev_abdulhay.telegramauth.bot;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TelegramBotRunner#start()} reports whether polling actually began. A
 * caller that publishes the runner as healthy — {@code TenantBotRegistry} — has
 * no other way to tell a live runner from one that returned early: a runner with
 * a blank token is silent rather than failing, so it never spends a failure
 * budget and never announces anything.
 */
class RunnerStartResultTest {

    /** Empty batches on a slow cadence, so the loop cannot spin and never touches the network. */
    static class QuietBot extends TelegramBot {
        final CountDownLatch polled = new CountDownLatch(1);

        QuietBot(String token) { super(java.net.http.HttpClient.newHttpClient(), token); }

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
    void startReportsTrueWhenPollingBegins() throws Exception {
        QuietBot bot = new QuietBot("123:ABC");
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "live_bot").bot(bot).build();
        TelegramBotRunner runner = new TelegramBotRunner(module);
        try {
            assertThat(runner.start()).isTrue();
            assertThat(bot.polled.await(2, TimeUnit.SECONDS))
                    .as("a true result must mean the poll loop is actually running")
                    .isTrue();
        } finally {
            runner.stop();
        }
    }

    @Test
    void startReportsFalseForABlankToken() throws Exception {
        QuietBot bot = new QuietBot("");
        TelegramBotModule module = TelegramBotModule.builder("", "blank_bot").bot(bot).build();
        TelegramBotRunner runner = new TelegramBotRunner(module);
        try {
            assertThat(runner.start()).isFalse();
            assertThat(bot.polled.await(300, TimeUnit.MILLISECONDS))
                    .as("a blank token must not poll at all")
                    .isFalse();
        } finally {
            runner.stop();
        }
    }

    @Test
    void startReportsFalseForARunnerThatIsAlreadyRunning() {
        QuietBot bot = new QuietBot("123:ABC");
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "live_bot").bot(bot).build();
        TelegramBotRunner runner = new TelegramBotRunner(module);
        try {
            assertThat(runner.start()).isTrue();
            assertThat(runner.start())
                    .as("the second call started nothing, and must not claim it did")
                    .isFalse();
        } finally {
            runner.stop();
        }
    }
}
