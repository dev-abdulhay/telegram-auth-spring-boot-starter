package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import io.github.dev_abdulhay.telegramauth.service.AbstractSessionService;

/**
 * A session service that satisfies the type without a database. The registry only
 * ever holds and returns it, so nothing here is exercised.
 */
class StubTenantSessionService extends AbstractSessionService<DemoU, DemoS> {

    StubTenantSessionService(TelegramBotModule module) {
        super(null, DemoS::new, new TokenGenerator(), module);
    }
}
