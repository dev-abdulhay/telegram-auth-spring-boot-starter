package io.github.dev_abdulhay.telegramauth.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Parses a Telegram {@code getUpdates} response and routes each update through
 * its module's command registry, falling back to the module fallback handler.
 * Handlers receive the full update {@link JsonNode}.
 */
public class BotUpdateDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BotUpdateDispatcher.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final TelegramBotModule module;

    public BotUpdateDispatcher(TelegramBotModule module) {
        this.module = module;
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
                route(update);
            }
        } catch (Exception e) {
            log.warn("dispatch failed", e);
        }
        return maxId;
    }

    private void route(JsonNode update) {
        String text = update.path("message").path("text").asText("");
        if (text.startsWith("/")) {
            String command = parseCommand(text);
            Consumer<JsonNode> handler = module.getCommands().get(command);
            if (handler != null) {
                invoke(handler, update);
                return;
            }
        }
        Consumer<JsonNode> fallback = module.getFallback();
        if (fallback != null) {
            invoke(fallback, update);
        }
    }

    /** "/start@bot ARG" -> "/start". */
    private static String parseCommand(String text) {
        int space = text.indexOf(' ');
        String token = (space >= 0) ? text.substring(0, space) : text;
        int at = token.indexOf('@');
        return (at >= 0) ? token.substring(0, at) : token;
    }

    private void invoke(Consumer<JsonNode> handler, JsonNode update) {
        try {
            handler.accept(update);
        } catch (RuntimeException e) {
            log.warn("update handler threw", e);
        }
    }
}
