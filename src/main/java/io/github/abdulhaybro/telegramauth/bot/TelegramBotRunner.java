package io.github.abdulhaybro.telegramauth.bot;

import io.github.abdulhaybro.telegramauth.config.TelegramAuthProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the long-poll loop. MVP scope: pulls updates from Telegram and hands
 * raw JSON to {@link BotUpdateDispatcher}. Stays running until the Spring
 * context closes.
 */
public class TelegramBotRunner {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotRunner.class);

    private final TelegramBotClient client;
    private final BotUpdateDispatcher dispatcher;
    private final TelegramAuthProperties props;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong offset = new AtomicLong(0);
    private ExecutorService executor;

    public TelegramBotRunner(TelegramBotClient client,
                             BotUpdateDispatcher dispatcher,
                             TelegramAuthProperties props) {
        this.client = client;
        this.dispatcher = dispatcher;
        this.props = props;
    }

    @PostConstruct
    public void start() {
        if (props.getBot().getToken() == null || props.getBot().getToken().isBlank()) {
            log.warn("telegram.auth.bot.token not set — bot polling disabled");
            return;
        }
        if (!running.compareAndSet(false, true)) return;
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tg-auth-bot-poll");
            t.setDaemon(true);
            return t;
        });
        executor.submit(this::loop);
        log.info("Telegram bot polling started, token={}", client.maskedToken());
    }

    @PreDestroy
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (executor != null) executor.shutdownNow();
        log.info("Telegram bot polling stopped");
    }

    private void loop() {
        int timeoutS = (int) props.getBot().getPollingTimeout().toSeconds();
        while (running.get()) {
            try {
                String json = client.getUpdates(offset.get(), timeoutS);
                long maxId = dispatcher.dispatch(json);
                if (maxId > 0) offset.set(maxId + 1);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("getUpdates failed; backing off", e);
                try {
                    Thread.sleep(props.getBot().getPollingInterval().toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}
