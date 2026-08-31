package io.github.dev_abdulhay.telegramauth.bot;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerThreadFactoryTest {

    /**
     * Returns one real update on its first poll — so the dispatcher hands work
     * to the worker pool and the worker thread gets created through the
     * runner's factory exactly as it would in production — then empty batches
     * so the loop cannot spin.
     */
    static class QuietBot extends TelegramBot {
        final CountDownLatch polled = new CountDownLatch(1);
        private final AtomicBoolean firstCall = new AtomicBoolean(true);

        QuietBot() { super(java.net.http.HttpClient.newHttpClient(), "123:ABC"); }

        @Override public String getUpdates(long offset, int timeoutSeconds) throws Exception {
            polled.countDown();
            if (firstCall.compareAndSet(true, false)) {
                return "{\"ok\":true,\"result\":[{\"update_id\":1,\"message\":{\"text\":\"/start\"}}]}";
            }
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
        CountDownLatch handled = new CountDownLatch(1);
        module.command("/start", u -> handled.countDown());
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
            assertThat(handled.await(2, TimeUnit.SECONDS)).isTrue();
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
        CountDownLatch handled = new CountDownLatch(1);
        module.command("/start", u -> handled.countDown());

        TelegramBotRunner runner = new TelegramBotRunner(module);
        try {
            runner.start();
            assertThat(bot.polled.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(handled.await(2, TimeUnit.SECONDS)).isTrue();

            List<Thread> runnerThreads = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.getName().equals("tg-auth-poll-legacy_bot")
                            || t.getName().equals("tg-auth-work-legacy_bot"))
                    .toList();
            assertThat(runnerThreads).hasSize(2);
            assertThat(runnerThreads).allSatisfy(t -> assertThat(t.isDaemon()).isTrue());
            assertThat(runnerThreads).anySatisfy(t -> assertThat(t.getName()).isEqualTo("tg-auth-poll-legacy_bot"));
            assertThat(runnerThreads).anySatisfy(t -> assertThat(t.getName()).isEqualTo("tg-auth-work-legacy_bot"));
        } finally {
            runner.stop();
        }
    }
}
