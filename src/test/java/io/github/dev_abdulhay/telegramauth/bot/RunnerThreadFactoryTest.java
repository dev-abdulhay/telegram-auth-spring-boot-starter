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
