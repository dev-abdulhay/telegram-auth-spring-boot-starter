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
 * <p><b>Resolve the session service as a prototype-scoped Spring bean that receives
 * the module as a construction argument.</b> Three independent requirements hide in
 * that one sentence, and each fails differently:
 *
 * <ol>
 *   <li><b>Container-managed, never a plain {@code new}.</b> A hand-built instance
 *       is not a Spring bean, so it gets no AOP proxy: {@code @Transactional}
 *       silently does nothing, the {@code PESSIMISTIC_WRITE} lock in
 *       {@code findWithLockByTokenHash} is released the moment its query returns
 *       instead of serialising concurrent {@code approve}/{@code reject}
 *       transitions, and {@code publishAfterCommit} falls through to its "no
 *       transaction active" branch. This has nothing to do with scope — the proxy
 *       comes from a {@code BeanPostProcessor}, which runs on every
 *       container-managed instance whatever its scope. All of it compiles, runs,
 *       and passes a smoke test; it only corrupts data under concurrency.
 *   <li><b>Prototype-scoped, never singleton</b> — a different failure with a
 *       different cause. {@code AbstractSessionService} keeps one tenant's
 *       {@link io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule} in a
 *       final field and reads {@code getBotUserId}, {@code getSessionTtl},
 *       {@code getMaxPendingPerIp}, {@code getApproveHandler} and {@code getBus}
 *       from it. A singleton would be built once and handed to every later tenant,
 *       freezing the first tenant's module: cross-tenant tokens, a shared event bus
 *       and one shared rate-limit bucket.
 *   <li><b>Prototype scope alone is not enough</b> — the per-bot module still has
 *       to <em>reach</em> the bean. Declare the {@code @Bean} method so the module
 *       is a construction argument and pass it in
 *       ({@code ObjectProvider#getObject(args)} or equivalent); a prototype that
 *       autowires its module by type would get the host's statically configured
 *       manager module on every tenant. Spring matches explicit arguments against
 *       the factory method's <em>whole</em> parameter list, so the {@code @Bean}
 *       method must take the module and nothing else — its other dependencies are
 *       injected into the enclosing {@code @Configuration} class.
 * </ol>
 *
 * <p>The implementation must set the bot id on the module:
 * {@code TelegramBotModule.builder(decryptedToken, bot.username()).botUserId(bot.botUserId())}.
 * Without it, sessions carry no tenant and every tenant shares one rate-limit bucket.
 */
@FunctionalInterface
public interface TenantBotFactory<U extends BaseTelegramUser, S extends BaseAuthSession> {

    RunningBot<U, S> create(ManagedBot bot, String decryptedToken);
}
