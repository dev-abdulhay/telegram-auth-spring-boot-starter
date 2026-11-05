package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBot;

/**
 * Optional hook for adding a tenant's own handlers — a support inbox, custom
 * commands, notifications — to a bot the runtime has just built.
 *
 * <p>Called <em>after</em> the auth flow has registered its handlers, so the
 * single-slot handlers it claims are already taken. Which ones depends on the
 * flow's options: {@code onCallbackQuery} when approval or a code step is on,
 * {@code onContact} when contact is required, {@code onText} in {@code TYPED}
 * mode. Route anything that collides through {@link TelegramBotModule#fallback}:
 * the flow forwards every update it does not own there.
 */
@FunctionalInterface
public interface ManagedBotCustomizer {

    void customize(TelegramBotModule module, ManagedBot bot);
}
