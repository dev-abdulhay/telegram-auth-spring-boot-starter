package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBot;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotTokenStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Brings the stored tenant bots back up when the application is ready and stops
 * them on shutdown.
 */
public class TenantBotLifecycle<U extends BaseTelegramUser, S extends BaseAuthSession> {

    private static final Logger log = LoggerFactory.getLogger(TenantBotLifecycle.class);

    private final ManagedBotTokenStore store;
    private final TenantBotRegistry<U, S> registry;
    private final boolean restoreOnStartup;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public TenantBotLifecycle(ManagedBotTokenStore store, TenantBotRegistry<U, S> registry,
                              boolean restoreOnStartup) {
        this.store = store;
        this.registry = registry;
        this.restoreOnStartup = restoreOnStartup;
    }

    /**
     * Starts every stored bot. Each one is attempted independently: a token that
     * no longer decrypts, a factory that throws, or a runner that fails to start
     * costs that tenant only. Aborting the loop would let a single bad row leave
     * the application with no bots at all.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startAll() {
        if (!restoreOnStartup) {
            log.info("tenant bot restore is disabled");
            return;
        }
        if (!started.compareAndSet(false, true)) return;

        List<ManagedBot> bots = store.findAll();
        int ok = 0;
        for (ManagedBot bot : bots) {
            try {
                registry.start(bot);
                ok++;
            } catch (RuntimeException e) {
                log.warn("could not restore tenant bot {}; continuing with the rest",
                        bot.botUserId(), e);
            }
        }
        log.info("tenant bots restored: {} of {}", ok, bots.size());
    }

    @PreDestroy
    public void stopAll() {
        registry.stopAll();
        started.set(false);
    }
}
