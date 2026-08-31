package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBot;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns managed-bot lifecycle events into registry operations: a created bot
 * starts polling, a rotated token restarts it, a decommissioned bot stops.
 *
 * <p>Every callback swallows its own failures. These run on the manager bot's
 * update worker thread, and one tenant that cannot start must not disturb the
 * manager bot or the tenants that can.
 */
public class TenantBotEventBridge<U extends BaseTelegramUser, S extends BaseAuthSession>
        implements ManagedBotEvents {

    private static final Logger log = LoggerFactory.getLogger(TenantBotEventBridge.class);

    private final TenantBotRegistry<U, S> registry;

    public TenantBotEventBridge(TenantBotRegistry<U, S> registry) {
        this.registry = registry;
    }

    @Override
    public void onCreated(ManagedBot bot) {
        guard("start", bot.botUserId(), () -> registry.start(bot));
    }

    @Override
    public void onTokenRotated(ManagedBot bot) {
        guard("restart", bot.botUserId(), () -> registry.restart(bot));
    }

    @Override
    public void onDecommissioned(long botUserId) {
        guard("stop", botUserId, () -> registry.stop(botUserId));
    }

    private void guard(String action, long botUserId, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            log.warn("could not {} tenant bot {}", action, botUserId, e);
        }
    }
}
