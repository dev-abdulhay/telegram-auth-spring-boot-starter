package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBot;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.ObjectProvider;

import java.util.function.Consumer;

/**
 * Turns managed-bot lifecycle events into registry operations: a created bot
 * starts polling, a rotated token restarts it, a decommissioned bot stops.
 *
 * <p>Every callback swallows its own failures. These run on the manager bot's
 * update worker thread, and one tenant that cannot start must not disturb the
 * manager bot or the tenants that can.
 *
 * <p><b>Host hooks still run.</b> When the runtime is on, this bridge is the
 * {@code ManagedBotEvents} bean {@code ManagedBotService} is wired with, so a host
 * that declares its own would otherwise be silently locked out of the lifecycle.
 * Any host-declared {@code ManagedBotEvents} beans are therefore forwarded to,
 * <em>after</em> the registry work: a broken host hook must not be able to stop a
 * tenant starting, and its failures are swallowed exactly as the registry's are.
 *
 * <p><b>Self-forwarding is filtered out.</b> The bridge is itself a
 * {@code ManagedBotEvents} bean — and, being {@code @Primary}, the very one a
 * single-candidate lookup returns — so forwarding blindly would recurse straight
 * back into this class. The candidates are therefore filtered by identity, and
 * that has to happen <em>at call time</em>: at construction time this bean does
 * not exist yet, so there is nothing to compare against. The comparison first
 * unwraps any Spring AOP proxy via {@link AopProxyUtils#getSingletonTarget}, so
 * a host whose auto-proxy creator or aspect happens to wrap this bean — nothing
 * in a stock context proxies it — still recognises and filters out itself
 * instead of forwarding into a proxy that delegates straight back, recursing
 * until {@code StackOverflowError}.
 */
public class TenantBotEventBridge<U extends BaseTelegramUser, S extends BaseAuthSession>
        implements ManagedBotEvents {

    private static final Logger log = LoggerFactory.getLogger(TenantBotEventBridge.class);

    private final TenantBotRegistry<U, S> registry;
    private final ObjectProvider<ManagedBotEvents> hostEvents;

    /** Drives the registry only; nothing is forwarded. */
    public TenantBotEventBridge(TenantBotRegistry<U, S> registry) {
        this(registry, null);
    }

    /**
     * @param hostEvents every {@code ManagedBotEvents} bean in the context, this
     *                   bridge included — resolved lazily so the self-reference is
     *                   not a construction-time cycle, and filtered out on each
     *                   call. {@code null} forwards to nobody.
     */
    public TenantBotEventBridge(TenantBotRegistry<U, S> registry,
                                ObjectProvider<ManagedBotEvents> hostEvents) {
        this.registry = registry;
        this.hostEvents = hostEvents;
    }

    @Override
    public void onCreated(ManagedBot bot) {
        guard("start", bot.botUserId(), () -> registry.start(bot));
        forward("onCreated", bot.botUserId(), d -> d.onCreated(bot));
    }

    @Override
    public void onTokenRotated(ManagedBot bot) {
        guard("restart", bot.botUserId(), () -> registry.restart(bot));
        forward("onTokenRotated", bot.botUserId(), d -> d.onTokenRotated(bot));
    }

    @Override
    public void onDecommissioned(long botUserId) {
        guard("stop", botUserId, () -> registry.stop(botUserId));
        forward("onDecommissioned", botUserId, d -> d.onDecommissioned(botUserId));
    }

    /**
     * Nothing to start — there is no token. Overridden purely to forward, since a
     * host may want to schedule a {@code fetchAndStore} recovery.
     */
    @Override
    public void onTokenFetchFailed(long botUserId, long ownerUserId, Exception cause) {
        forward("onTokenFetchFailed", botUserId, d -> d.onTokenFetchFailed(botUserId, ownerUserId, cause));
    }

    /**
     * Hands one event to every host-declared {@code ManagedBotEvents}, this bridge
     * excluded. Guarded twice on purpose: the inner guard keeps one bad host bean
     * from starving the next, the outer one covers the lazy bean resolution the
     * stream itself performs.
     */
    private void forward(String event, long botUserId, Consumer<ManagedBotEvents> call) {
        if (hostEvents == null) return;
        String action = "hand " + event + " to the host's ManagedBotEvents for";
        guard(action, botUserId, () -> hostEvents.orderedStream()
                .filter(delegate -> unwrapProxy(delegate) != this)
                .forEach(delegate -> guard(action, botUserId, () -> call.accept(delegate))));
    }

    /**
     * Peels away any Spring AOP proxy (JDK or CGLIB) around {@code candidate},
     * repeating for proxies of proxies, until it reaches the real singleton target
     * — or {@code candidate} itself if it was never a proxy to begin with.
     * {@code AopProxyUtils} has no single call for this; it is assembled the same
     * way {@code ultimateTargetClass} walks the chain, one {@code getSingletonTarget}
     * hop at a time.
     */
    private static Object unwrapProxy(Object candidate) {
        Object current = candidate;
        Object target;
        while ((target = AopProxyUtils.getSingletonTarget(current)) != null) {
            current = target;
        }
        return current;
    }

    private void guard(String action, long botUserId, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            // Throwable, not RuntimeException: this runs on the manager bot's update
            // worker thread, and an Error escaping here (e.g. AssertionError from a
            // host callback, or NoClassDefFoundError) would surface as an uncaught
            // exception on that thread instead of the warning this guard exists to
            // produce. See TelegramBotRunner.announceGiveUp for the same pattern.
            log.warn("could not {} tenant bot {}", action, botUserId, t);
        }
    }
}
