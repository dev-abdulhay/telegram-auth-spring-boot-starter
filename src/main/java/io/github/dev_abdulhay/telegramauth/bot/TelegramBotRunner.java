package io.github.dev_abdulhay.telegramauth.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns one long-poll loop for a single {@link TelegramBotModule}. Pulls updates
 * and hands raw JSON to a {@link BotUpdateDispatcher}.
 */
public class TelegramBotRunner {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotRunner.class);

    private final TelegramBotModule module;
    private final BotUpdateDispatcher dispatcher;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong offset = new AtomicLong(0);
    private ExecutorService executor;

    public TelegramBotRunner(TelegramBotModule module) {
        this.module = module;
        this.dispatcher = new BotUpdateDispatcher(module);
    }

    public void start() {
        String token = module.getBotToken();
        if (token == null || token.isBlank()) {
            log.warn("bot token blank for @{} — polling disabled", module.getUsername());
            return;
        }
        if (!running.compareAndSet(false, true)) return;
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
        log.info("Telegram polling stopped for @{}", module.getUsername());
    }

    private void loop() {
        int timeoutS = (int) module.getPollingTimeout().toSeconds();
        while (running.get()) {
            try {
                String json = module.getBot().getUpdates(offset.get(), timeoutS);
                long maxId = dispatcher.dispatch(json);
                if (maxId > 0) offset.set(maxId + 1);
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
