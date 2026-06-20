package io.github.dev_abdulhay.telegramauth.bot;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Starts one {@link TelegramBotRunner} per registered {@link TelegramBotModule}
 * once the application is ready, and stops them all on shutdown.
 */
public class TelegramBotLifecycle {

    private final ObjectProvider<TelegramBotModule> modules;
    private final List<TelegramBotRunner> runners = new CopyOnWriteArrayList<>();

    public TelegramBotLifecycle(ObjectProvider<TelegramBotModule> modules) {
        this.modules = modules;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startAll() {
        modules.forEach(module -> {
            TelegramBotRunner runner = new TelegramBotRunner(module);
            runner.start();
            runners.add(runner);
        });
    }

    @PreDestroy
    public void stopAll() {
        runners.forEach(TelegramBotRunner::stop);
        runners.clear();
    }
}
