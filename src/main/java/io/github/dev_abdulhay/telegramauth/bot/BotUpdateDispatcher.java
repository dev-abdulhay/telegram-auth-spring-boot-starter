package io.github.dev_abdulhay.telegramauth.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dev_abdulhay.telegramauth.entity.MTelegramUser;
import io.github.dev_abdulhay.telegramauth.service.SessionService;
import io.github.dev_abdulhay.telegramauth.service.TelegramUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MVP dispatcher: parses Telegram {@code getUpdates} responses, routes
 * {@code /start <token>} commands. Contact / name-confirmation flow is
 * stubbed — Phase 2 work in the tech-doc.
 */
public class BotUpdateDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BotUpdateDispatcher.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final TelegramBotClient client;
    private final SessionService sessionService;
    private final TelegramUserService userService;

    public BotUpdateDispatcher(TelegramBotClient client,
                               SessionService sessionService,
                               TelegramUserService userService) {
        this.client = client;
        this.sessionService = sessionService;
        this.userService = userService;
    }

    /** Returns the highest update_id seen in the batch, or 0 if empty/invalid. */
    public long dispatch(String json) {
        long maxId = 0;
        try {
            JsonNode root = mapper.readTree(json);
            if (!root.path("ok").asBoolean(false)) {
                log.debug("non-ok getUpdates response");
                return 0;
            }
            for (JsonNode update : root.path("result")) {
                maxId = Math.max(maxId, update.path("update_id").asLong());
                JsonNode message = update.path("message");
                if (message.isObject()) {
                    handleMessage(message);
                }
            }
        } catch (Exception e) {
            log.warn("dispatch failed", e);
        }
        return maxId;
    }

    private void handleMessage(JsonNode message) {
        String text = message.path("text").asText("");
        if (text.startsWith("/start ")) {
            String rawToken = text.substring("/start ".length()).trim();
            long chatId = message.path("chat").path("id").asLong();
            handleStart(chatId, rawToken, message);
        }
    }

    private void handleStart(long chatId, String rawToken, JsonNode message) {
        var sessionOpt = sessionService.findByRawToken(rawToken);
        if (sessionOpt.isEmpty()) {
            client.sendMessage(chatId, "Havola yaroqsiz yoki muddati tugagan.");
            return;
        }
        MTelegramUser user = userService.findByTelegramId(chatId).orElse(null);
        if (user == null || user.getStatus() != MTelegramUser.Status.ACTIVE) {
            // MVP shortcut: auto-register from /start message metadata. Production
            // flow (Contact share + name confirm) is planned for Phase 2.
            String firstName = message.path("from").path("first_name").asText(null);
            String lastName = message.path("from").path("last_name").asText(null);
            String username = message.path("from").path("username").asText(null);
            String lang = message.path("from").path("language_code").asText("uz");
            user = userService.register(chatId, null, firstName, lastName, username, lang);
        }
        // MVP shortcut: auto-approve. Approve/Reject inline-keyboard handling is
        // tracked as the very next iteration after publish.
        sessionService.approve(sessionService.hash(rawToken), user);
        client.sendMessage(chatId, "Tasdiqlandi. Web saytga qayting.");
    }
}
