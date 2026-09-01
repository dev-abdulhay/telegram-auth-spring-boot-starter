package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.service.AbstractSessionService;

import java.util.Objects;

/**
 * One tenant bot's wiring, as built by a {@link TenantBotFactory}.
 *
 * <p>It carries the session service as well as the module because the registry
 * hands that service back to the host's REST layer later — a bare module would
 * leave the registry holding an untyped service it could not usefully expose.
 */
public record RunningBot<U extends BaseTelegramUser, S extends BaseAuthSession>(
        TelegramBotModule module, AbstractSessionService<U, S> sessionService) {

    public RunningBot {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(sessionService, "sessionService");
    }
}
