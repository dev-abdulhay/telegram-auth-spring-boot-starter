package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBot;

/**
 * Builds the wiring for one managed bot. <b>The host implements this</b> — the
 * library cannot, because {@code AbstractSessionService} and {@code DefaultAuthFlow}
 * are generic over the host's own user and session entities, which the library
 * never sees.
 *
 * <p><b>Resolve the services as prototype-scoped Spring beans, never with a plain
 * {@code new}.</b> A service built with {@code new} is not a Spring bean, so it
 * gets no AOP proxy: {@code @Transactional} silently does nothing, the pessimistic
 * lock in {@code approve}/{@code reject} is released the moment its query returns,
 * and {@code publishAfterCommit} loses its guarantee. All of that compiles, runs,
 * and passes a smoke test — it only corrupts data under concurrency.
 *
 * <p>The implementation must set the bot id on the module:
 * {@code TelegramBotModule.builder(decryptedToken, bot.username()).botUserId(bot.botUserId())}.
 * Without it, sessions carry no tenant and every tenant shares one rate-limit bucket.
 */
@FunctionalInterface
public interface TenantBotFactory<U extends BaseTelegramUser, S extends BaseAuthSession> {

    RunningBot<U, S> create(ManagedBot bot, String decryptedToken);
}
