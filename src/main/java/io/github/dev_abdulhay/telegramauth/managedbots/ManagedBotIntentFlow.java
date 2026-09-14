package io.github.dev_abdulhay.telegramauth.managedbots;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.flow.FlowMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Handles {@code /start mb_<intentId>} on the manager bot: claims the intent for
 * whoever opened it and answers with the button that creates the bot.
 *
 * <p>Self-registering, like {@code DefaultAuthFlow} — constructing it is all the
 * wiring a host needs. Registration goes through
 * {@link TelegramBotModule#startPayload}, not the command registry, so the login
 * flow keeps {@code /start} and a payload this flow does not recognise falls
 * straight through to it.
 *
 * <p>Override {@link #msg(FlowMessages.Key, String)} to change the wording.
 */
public class ManagedBotIntentFlow {

    private static final Logger log = LoggerFactory.getLogger(ManagedBotIntentFlow.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TelegramBotModule module;
    private final ManagedBotService service;

    public ManagedBotIntentFlow(TelegramBotModule module, ManagedBotService service) {
        this.module = module;
        this.service = service;
        module.startPayload(ManagedBotIntent.START_PREFIX, this::onStart);
    }

    /** @return whether this update was ours; {@code false} sends it on to the login flow */
    protected boolean onStart(JsonNode update) {
        JsonNode message = update.path("message");
        JsonNode from = message.path("from");
        long userId = from.path("id").asLong();
        if (!isPrivateChat(message)) {
            // Groups and channels: the chat id is not a user id there, and an intent
            // must never be claimed from a chat where anyone could tap the link.
            log.debug("/start intent payload outside a private chat ignored");
            return true;
        }
        String text = message.path("text").asText("");
        // Mirrors how the dispatcher itself finds the payload (BotUpdateDispatcher#startRouteFor):
        // the first token can carry a "@botname" suffix ("/start@manager_bot mb_x"), so the
        // payload is whatever follows the first space, not a fixed-length prefix cut.
        int space = text.indexOf(' ');
        String payload = space < 0 ? "" : text.substring(space + 1).trim();
        if (!payload.startsWith(ManagedBotIntent.START_PREFIX)) return false;
        String intentId = payload.substring(ManagedBotIntent.START_PREFIX.length());
        String lang = FlowMessages.resolveLang(from.path("language_code").asText(null));

        IntentClaimResult result = service.claimIntent(intentId, userId);
        switch (result.outcome()) {
            case UNKNOWN:
                return false;
            case CLOSED:
                send(userId, msg(FlowMessages.Key.INVALID_LINK, lang));
                return true;
            case OTHER_OWNER:
                send(userId, msg(FlowMessages.Key.INTENT_OTHER_OWNER, lang));
                return true;
            case COMPLETED:
                send(userId, msg(FlowMessages.Key.INTENT_ALREADY_DONE, lang));
                return true;
            default:   // CLAIMED, RECLAIMED
                if (result.intent().status() == ManagedBotIntentStatus.COMPLETED) {
                    // The claim itself found the bot: asking for one now would be absurd.
                    send(userId, msg(FlowMessages.Key.INTENT_ALREADY_DONE, lang));
                } else {
                    sendPrompt(userId, result.intent(), lang);
                }
                return true;
        }
    }

    private void sendPrompt(long userId, ManagedBotIntent intent, String lang) {
        String url = ManagedBotLink.build(module.getUsername(),
                intent.suggestedUsername(), intent.suggestedName());
        String markup = toJson(Map.of("inline_keyboard", List.of(List.of(
                Map.of("text", msg(FlowMessages.Key.BTN_CREATE_BOT, lang), "url", url)))));
        module.getBot().sendMessage(userId, msg(FlowMessages.Key.INTENT_PROMPT, lang), markup);
    }

    private void send(long userId, String text) {
        module.getBot().sendMessage(userId, text);
    }

    /** Override point for custom wording; defaults to the built-in 3-language table. */
    protected String msg(FlowMessages.Key key, String lang) {
        return FlowMessages.text(key, lang);
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialize a reply markup", e);
        }
    }

    /**
     * A Telegram private chat always has {@code chat.id == from.id}; anywhere
     * else (group, supergroup, channel) the chat id is not a user id. Mirrors
     * {@code DefaultAuthFlow}'s own check exactly, including the {@code from.id
     * != 0} guard against an update with no identity at all and the chat-type
     * allow-list, so the two flows agree on what counts as private.
     */
    private static boolean isPrivateChat(JsonNode message) {
        long userId = message.path("from").path("id").asLong();
        long chatId = message.path("chat").path("id").asLong();
        String type = message.path("chat").path("type").asText("");
        return userId != 0 && chatId == userId && (type.isEmpty() || "private".equals(type));
    }
}
