package io.github.dev_abdulhay.telegramauth.bot;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rejection policy decides what a full update queue does to the poll thread.
 * {@code CallerRunsPolicy} would run the overflow handler on the caller, i.e.
 * alongside the worker's current handler and ahead of everything queued — the
 * single-threaded, in-order guarantee would break exactly under load.
 */
class WorkerBackpressureTest {

    private static ThreadPoolExecutor pool(int queueCapacity) {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                TelegramBotRunner::blockUntilQueued);
    }

    @Test
    void aFullQueueParksTheCallerInsteadOfRunningTheTaskInline() throws Exception {
        ThreadPoolExecutor pool = pool(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workerBusy = new CountDownLatch(1);
        try {
            pool.execute(() -> {                       // occupies the single worker
                workerBusy.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertThat(workerBusy.await(2, TimeUnit.SECONDS)).isTrue();
            pool.execute(() -> { });                   // fills the one queue slot

            AtomicReference<String> ranOn = new AtomicReference<>();
            CountDownLatch handed = new CountDownLatch(1);
            Thread caller = new Thread(() -> {
                TelegramBotRunner.blockUntilQueued(() -> ranOn.set(Thread.currentThread().getName()), pool);
                handed.countDown();
            }, "fake-poll-thread");
            caller.start();

            // the caller must still be parked in put(), and must not have run the task itself
            assertThat(handed.await(300, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(ranOn.get()).isNull();

            release.countDown();
            assertThat(handed.await(2, TimeUnit.SECONDS)).isTrue();
            pool.shutdown();
            assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            assertThat(ranOn.get()).isNotEqualTo("fake-poll-thread");   // a worker ran it, not the caller
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void aShuttingDownPoolDropsTheTaskInsteadOfBlockingForever() {
        ThreadPoolExecutor pool = pool(1);
        pool.shutdown();

        AtomicReference<Boolean> ran = new AtomicReference<>(false);
        TelegramBotRunner.blockUntilQueued(() -> ran.set(true), pool);

        // polling has stopped, so Telegram never sees a higher offset and redelivers
        assertThat(ran.get()).isFalse();
        assertThat(pool.getQueue()).isEmpty();
    }
}
