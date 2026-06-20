package io.github.dev_abdulhay.telegramauth.flow;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.service.AbstractSessionService;
import io.github.dev_abdulhay.telegramauth.service.AbstractTelegramUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Default reg/auth flow. Self-registers its {@code /start} handler into the
 * module on construction, so declaring this bean is enough to get a working
 * login. Subclass and {@code @Override} {@link #onStart} to customise.
 *
 * <p>MVP: {@code /start <token>} auto-registers the user from message metadata
 * and approves the session. Contact-share and inline approve/reject are future
 * work; route them via additional commands or the module fallback.
 */
public class DefaultAuthFlow<U extends BaseTelegramUser, S extends BaseAuthSession> {

    private static final Logger log = LoggerFactory.getLogger(DefaultAuthFlow.class);
    private static final String START = "/start ";

    protected final AbstractTelegramUserService<U> userService;
    protected final AbstractSessionService<U, S> sessionService;
    protected final TelegramBotModule module;

    public DefaultAuthFlow(AbstractTelegramUserService<U> userService,
                           AbstractSessionService<U, S> sessionService,
                           TelegramBotModule module) {
        this.userService = userService;
        this.sessionService = sessionService;
        this.module = module;
        module.command("/start", this::onStart);
    }

    public void onStart(JsonNode update) {
        JsonNode message = update.path("message");
        long chatId = message.path("chat").path("id").asLong();
        String text = message.path("text").asText("");
        String rawToken = text.length() > START.length() ? text.substring(START.length()).trim() : "";

        Optional<S> session = rawToken.isEmpty() ? Optional.empty() : sessionService.findByRawToken(rawToken);
        if (session.isEmpty()) {
            module.getBot().sendMessage(chatId, "Havola yaroqsiz yoki muddati tugagan.");
            return;
        }

        U user = userService.findByTelegramId(chatId).orElse(null);
        if (user == null || user.getStatus() != BaseTelegramUser.Status.ACTIVE) {
            JsonNode from = message.path("from");
            user = userService.register(
                    chatId,
                    null,
                    from.path("first_name").asText(null),
                    from.path("last_name").asText(null),
                    from.path("username").asText(null),
                    from.path("language_code").asText("uz"));
        }

        sessionService.approve(sessionService.hash(rawToken), user);
        module.getBot().sendMessage(chatId, "Tasdiqlandi. Web saytga qayting.");
        log.debug("default flow approved chatId={}", chatId);
    }
}
