package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotRunner;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBot;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotService;
import io.github.dev_abdulhay.telegramauth.service.AbstractSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;

/**
 * Owns the tenant bots that are currently polling. One entry per managed bot:
 * its module, its session service and the runner driving it.
 *
 * <p>JVM-local and single-instance by design. Two application instances polling
 * one bot would collide — Telegram answers 409 — so nothing here attempts
 * ownership or leasing.
 */
public class TenantBotRegistry<U extends BaseTelegramUser, S extends BaseAuthSession> {

    private static final Logger log = LoggerFactory.getLogger(TenantBotRegistry.class);

    private record Entry<U extends BaseTelegramUser, S extends BaseAuthSession>(
            RunningBot<U, S> bot, TelegramBotRunner runner) { }

    private final ManagedBotService managedBots;
    private final TenantBotFactory<U, S> factory;
    private final ManagedBotCustomizer customizer;
    private final ThreadFactory threadFactory;
    private final Duration pollFailureBudget;

    private final ConcurrentHashMap<Long, Entry<U, S>> running = new ConcurrentHashMap<>();

    /**
     * @param customizer        optional host hook, run after the auth flow is wired
     * @param threadFactory     optional; a host on Java 21+ passes a virtual-thread factory
     * @param pollFailureBudget how long a bot may fail before it is stopped and
     *                          deregistered; {@code null} retries forever
     */
    public TenantBotRegistry(ManagedBotService managedBots, TenantBotFactory<U, S> factory,
                             ManagedBotCustomizer customizer, ThreadFactory threadFactory,
                             Duration pollFailureBudget) {
        this.managedBots = managedBots;
        this.factory = factory;
        this.customizer = customizer;
        this.threadFactory = threadFactory;
        this.pollFailureBudget = pollFailureBudget;
    }

    /**
     * Builds and starts one tenant bot. Starting a bot that is already running is
     * a no-op rather than a second runner — a re-delivered {@code managed_bot}
     * update must not double the polling, which would draw a 409 against our own
     * other poller and drop updates neither side confirms.
     *
     * @throws IllegalStateException if no token is stored for the bot; the registry
     *                                is left unchanged
     */
    public void start(ManagedBot bot) {
        long id = bot.botUserId();
        if (running.containsKey(id)) {
            log.debug("tenant bot {} is already running", id);
            return;
        }
        String token = managedBots.findToken(id).orElseThrow(() -> new IllegalStateException(
                "no stored token for managed bot " + id + "; cannot start it"));
        RunningBot<U, S> built = factory.create(bot, token);
        if (customizer != null) {
            customizer.customize(built.module(), bot);
        }
        TelegramBotRunner runner = new TelegramBotRunner(built.module(), threadFactory,
                pollFailureBudget, (module, failingFor) -> {
                    // Runs after the runner has already torn its own pools down, so the
                    // only job here is dropping the bot from the map — calling stop()
                    // would be redundant at best and would race a concurrent restart at
                    // worst. A revoked token is the common cause but not the only one:
                    // dispatch() also gives up on an unparseable payload and on a 409
                    // from a competing poller, so this only says "probably".
                    log.warn("tenant bot {} stopped after {}s of unbroken poll failures — "
                            + "its token was probably revoked", id, failingFor.toSeconds());
                    running.remove(id);
                });
        runner.start();
        running.put(id, new Entry<>(built, runner));
        log.info("tenant bot {} (@{}) started", id, built.module().getUsername());
    }

    /** Stops and deregisters one tenant bot; unknown ids are ignored. */
    public void stop(long botUserId) {
        Entry<U, S> entry = running.remove(botUserId);
        if (entry == null) return;
        entry.runner().stop();
        log.info("tenant bot {} stopped", botUserId);
    }

    /**
     * Stops the bot and starts it again from its current stored token — the only
     * way to apply a rotation, since {@code TelegramBot} holds its token in a
     * final field and cannot be updated in place.
     *
     * <p>Two costs come with it. In-flight logins on this tenant are lost: the
     * flow's pending-login map is JVM-local, not persisted, and stopping the
     * runner discards it. And the new runner starts polling from offset 0, so
     * Telegram may redeliver updates the old runner had already received but
     * never confirmed by advancing past them.
     */
    public void restart(ManagedBot bot) {
        stop(bot.botUserId());
        start(bot);
    }

    /** The session service driving this tenant, or empty when it is not running. */
    public Optional<AbstractSessionService<U, S>> sessionServiceFor(long botUserId) {
        Entry<U, S> entry = running.get(botUserId);
        return Optional.ofNullable(entry).map(e -> e.bot().sessionService());
    }

    /** Ids of every tenant bot currently polling. */
    public Set<Long> running() {
        return Set.copyOf(running.keySet());
    }

    /** Stops every tenant bot. */
    public void stopAll() {
        running.keySet().forEach(this::stop);
    }
}
