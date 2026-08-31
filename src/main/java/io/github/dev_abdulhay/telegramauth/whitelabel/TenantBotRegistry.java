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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.stream.Collectors;

/**
 * Owns the tenant bots that are currently polling. One entry per managed bot:
 * its module, its session service and the runner driving it.
 *
 * <p>JVM-local and single-instance by design. Two application instances polling
 * one bot would collide — Telegram answers 409 — so nothing here attempts
 * ownership or leasing.
 *
 * <p><b>Concurrency guarantee.</b> {@link #start} is safe to call concurrently
 * for the same bot id: exactly one caller reserves the id's slot with an atomic
 * {@code putIfAbsent}, and only that caller builds the module, runs the
 * customizer and starts the runner. Every other concurrent (or re-delivered)
 * caller sees the reservation and returns without building a second runner. If
 * any step after the reservation throws — no stored token, the factory, the
 * customizer, or {@code runner.start()} itself — the reservation is released
 * again, so a failed start never permanently blocks a later retry. Until the
 * build finishes, the bot is not yet visible through {@link #running()} or
 * {@link #sessionServiceFor}: both only report a slot whose runner has actually
 * started. {@link #stop}, {@link #stopAll} and {@link #restart} are safe against
 * that same reservation window: a {@code stop} landing while a concurrent
 * {@code start} has reserved but not yet published its runner leaves the
 * reservation untouched rather than evicting it out from under the starting
 * thread, which would otherwise finish into a slot the map no longer holds.
 */
public class TenantBotRegistry<U extends BaseTelegramUser, S extends BaseAuthSession> {

    private static final Logger log = LoggerFactory.getLogger(TenantBotRegistry.class);

    private record Entry<U extends BaseTelegramUser, S extends BaseAuthSession>(
            RunningBot<U, S> bot, TelegramBotRunner runner) { }

    /**
     * One reserved slot for a bot id. Inserted empty — {@code entry == null} — to
     * claim the id before the runner is built, then filled in once {@code start()}
     * has actually started it.
     *
     * <p>Identity, not content, is what makes this safe: the poll-failure listener
     * created inside one {@code start()} call captures the exact {@code Slot} that
     * call reserved. A {@code restart} stops the old runner and reserves a brand
     * new {@code Slot} for the same id, so if the old runner's give-up listener
     * fires late — it announces only after its own teardown, which can trail a
     * {@code stop()} by seconds — it holds a reference to a {@code Slot} the map
     * no longer associates with this id, and its conditional removal is a no-op
     * instead of evicting the replacement. See {@link #start}.
     */
    private static final class Slot<U extends BaseTelegramUser, S extends BaseAuthSession> {
        private volatile Entry<U, S> entry;
    }

    private final ManagedBotService managedBots;
    private final TenantBotFactory<U, S> factory;
    private final ManagedBotCustomizer customizer;
    private final ThreadFactory threadFactory;
    private final Duration pollFailureBudget;

    private final ConcurrentHashMap<Long, Slot<U, S>> running = new ConcurrentHashMap<>();

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
     * Builds and starts one tenant bot. Starting a bot that is already running —
     * or already being started by a concurrent caller — is a no-op rather than a
     * second runner: a re-delivered {@code managed_bot} update, or two racing
     * callers, must not double the polling, which would draw a 409 against our
     * own other poller and drop updates neither side confirms.
     *
     * @throws IllegalStateException if no token is stored for the bot; the registry
     *                                is left unchanged
     */
    public void start(ManagedBot bot) {
        long id = bot.botUserId();
        Slot<U, S> reservation = new Slot<>();
        if (running.putIfAbsent(id, reservation) != null) {
            log.debug("tenant bot {} is already running or starting", id);
            return;
        }
        boolean started = false;
        try {
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
                        // from a competing poller, so this only says "probably". Removed
                        // value-conditionally on the exact slot this start() reserved, so a
                        // late announcement from a runner a restart has already replaced
                        // cannot evict its replacement — see the Slot javadoc.
                        log.warn("tenant bot {} stopped after {}s of unbroken poll failures — "
                                + "its token was probably revoked", id, failingFor.toSeconds());
                        running.remove(id, reservation);
                    });
            runner.start();
            reservation.entry = new Entry<>(built, runner);
            started = true;
            log.info("tenant bot {} (@{}) started", id, built.module().getUsername());
        } finally {
            if (!started) {
                running.remove(id, reservation);
            }
        }
    }

    /**
     * Stops and deregisters one tenant bot; unknown ids, and ids still reserving
     * (a concurrent {@code start()} has not yet published its runner), are ignored.
     *
     * <p>Reads before removing, and removes value-conditionally on the exact slot
     * just read: an unconditional {@code remove} would evict another thread's
     * reservation out from under it, and the runner it eventually starts would
     * finish into a slot the map no longer holds — polling forever, invisible to
     * {@link #running()}, {@link #sessionServiceFor} and to every future
     * {@code stop}/{@code stopAll}, and inviting a second poller on the same token
     * the next time {@code start} is called for this id.
     */
    public void stop(long botUserId) {
        Slot<U, S> slot = running.get(botUserId);
        if (slot == null || slot.entry == null) return;
        if (!running.remove(botUserId, slot)) return;
        slot.entry.runner().stop();
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
        Slot<U, S> slot = running.get(botUserId);
        Entry<U, S> entry = slot == null ? null : slot.entry;
        return entry == null ? Optional.empty() : Optional.of(entry.bot().sessionService());
    }

    /** Ids of every tenant bot currently polling. */
    public Set<Long> running() {
        return running.entrySet().stream()
                .filter(e -> e.getValue().entry != null)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Stops every tenant bot. */
    public void stopAll() {
        running.keySet().forEach(this::stop);
    }
}
