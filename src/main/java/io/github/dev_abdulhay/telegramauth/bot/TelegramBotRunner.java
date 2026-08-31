package io.github.dev_abdulhay.telegramauth.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns one long-poll loop for a single {@link TelegramBotModule}. Pulls updates
 * on a dedicated poll thread and hands raw JSON to a {@link BotUpdateDispatcher}
 * backed by a single worker thread, so slow handlers never stall the poll loop.
 *
 * <p>Handlers run one at a time, in arrival order. The worker queue is bounded,
 * and a full queue <em>blocks</em> the poll thread until a slot frees rather
 * than running the overflow task inline — inline execution
 * ({@code CallerRunsPolicy}) would put a handler on the poll thread next to the
 * worker's, breaking both the single-threaded and the in-order guarantee exactly
 * when the bot is already under load. Blocking instead applies backpressure:
 * polling slows to the speed of the handlers instead of buffering an unbounded
 * backlog.
 *
 * <p>{@link #stop()} drains the queue before forcing threads down, so a normal
 * shutdown does not silently discard updates whose offsets the next poll would
 * have confirmed to Telegram.
 */
public class TelegramBotRunner {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotRunner.class);

    /** Updates that may sit unhandled before polling itself blocks. */
    private static final int WORKER_QUEUE_CAPACITY = 100;
    /** How long {@link #stop()} waits for queued handlers before interrupting them. */
    private static final long WORKER_DRAIN_SECONDS = 5;

    private final TelegramBotModule module;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong offset = new AtomicLong(0);
    private volatile ExecutorService executor;
    private volatile ExecutorService worker;
    private volatile BotUpdateDispatcher dispatcher;

    public TelegramBotRunner(TelegramBotModule module) {
        this.module = module;
    }

    public void start() {
        String token = module.getBotToken();
        if (token == null || token.isBlank()) {
            log.warn("bot token blank for @{} — polling disabled", module.getUsername());
            return;
        }
        if (!running.compareAndSet(false, true)) return;
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(WORKER_QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "tg-auth-work-" + module.getUsername());
                    t.setDaemon(true);
                    return t;
                },
                TelegramBotRunner::blockUntilQueued);
        worker = pool;
        dispatcher = new BotUpdateDispatcher(module, worker);
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tg-auth-poll-" + module.getUsername());
            t.setDaemon(true);
            return t;
        });
        executor.submit(this::loop);
        log.info("Telegram polling started for @{}, token={}", module.getUsername(), module.getBot().maskedToken());
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (executor != null) executor.shutdownNow();
        drainWorker();
        log.info("Telegram polling stopped for @{}", module.getUsername());
    }

    /**
     * Rejection policy for a full worker queue: park the poll thread until a slot
     * frees, keeping handler execution single-threaded and in arrival order.
     * Once the pool is shutting down the task is dropped instead — polling has
     * stopped by then, so Telegram never sees a higher offset and re-delivers.
     */
    static void blockUntilQueued(Runnable task, ThreadPoolExecutor pool) {
        if (pool.isShutdown()) {
            log.debug("update dropped: worker already shutting down");
            return;
        }
        try {
            pool.getQueue().put(task);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Lets already-queued updates finish before interrupting. Their offsets are
     * confirmed to Telegram by the next poll, which for a lagging worker has
     * usually already happened — dropping them would lose them for good.
     */
    private void drainWorker() {
        ExecutorService w = worker;
        if (w == null) return;
        w.shutdown();
        try {
            if (!w.awaitTermination(WORKER_DRAIN_SECONDS, TimeUnit.SECONDS)) {
                log.warn("update worker did not drain in {}s for @{}; forcing shutdown",
                        WORKER_DRAIN_SECONDS, module.getUsername());
                w.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            w.shutdownNow();
        }
    }

    private void loop() {
        int timeoutS = (int) module.getPollingTimeout().toSeconds();
        while (running.get()) {
            try {
                // recomputed every iteration, not snapshotted before the loop: the
                // managed_bot handler is registered by a singleton the container may
                // build after start(), and a snapshot would then pin allowed_updates
                // to null forever — managed_bot silently never requested. One extra
                // getter call per long poll is free next to the poll itself.
                List<String> allowed = module.getManagedBotHandler() != null
                        ? List.of("message", "callback_query", "managed_bot")
                        : null;
                // the 2-arg overload is called (not 3-arg with a null list) so that hosts/tests
                // overriding only getUpdates(long, int) — see DemoTgConfig — keep working
                String json = (allowed != null)
                        ? module.getBot().getUpdates(offset.get(), timeoutS, allowed)
                        : module.getBot().getUpdates(offset.get(), timeoutS);
                long maxId = dispatcher.dispatch(json);
                if (maxId > 0) {
                    offset.set(maxId + 1);
                } else if (maxId < 0) {
                    // non-ok response (bad token, 409 from a competing poller, ...) —
                    // back off instead of hammering the API in a tight loop
                    Thread.sleep(module.getPollingInterval().toMillis());
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("getUpdates failed for @{}; backing off", module.getUsername(), e);
                try {
                    Thread.sleep(module.getPollingInterval().toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}
