package io.github.dev_abdulhay.telegramauth.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Parses a Telegram {@code getUpdates} response and routes each update through
 * its module's handlers. Routing order: {@code managed_bot} handler (a
 * top-level {@code Update} field, checked before anything nested in
 * {@code message}), then the {@code callback_query} handler, then the command
 * registry, then the {@code contact} handler, then the text handler, then the
 * module fallback. Handlers receive the full update {@link JsonNode}.
 *
 * <p>An unregistered {@code /command} reaches the <em>text</em> handler, not the
 * fallback: once the registry misses there is nothing left to distinguish it
 * from ordinary text.
 */
public class BotUpdateDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BotUpdateDispatcher.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final TelegramBotModule module;
    private final Executor executor;

    public BotUpdateDispatcher(TelegramBotModule module) {
        this(module, Runnable::run);
    }

    /**
     * @param executor executes handler invocations; a single-threaded executor
     *                 keeps update ordering while freeing the polling thread.
     */
    public BotUpdateDispatcher(TelegramBotModule module, Executor executor) {
        this.module = module;
        this.executor = executor;
    }

    /**
     * Returns the highest update_id seen in the batch, 0 if the batch is empty,
     * or -1 when the response is not ok / unparseable (callers should back off).
     *
     * <p>A failure to route one update never collapses the batch to -1: the
     * offset must still advance past the updates that were routed, otherwise
     * Telegram re-delivers the whole batch and every handler in it runs twice.
     */
    public long dispatch(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            log.warn("getUpdates response unparseable", e);
            return -1;
        }
        if (!root.path("ok").asBoolean(false)) {
            log.debug("non-ok getUpdates response");
            return -1;
        }
        long maxId = 0;
        for (JsonNode update : root.path("result")) {
            maxId = Math.max(maxId, update.path("update_id").asLong());
            try {
                route(update);
            } catch (RuntimeException e) {
                log.warn("routing update {} failed", update.path("update_id").asLong(), e);
            }
        }
        return maxId;
    }

    private void route(JsonNode update) {
        if (update.has("managed_bot")) {
            Consumer<JsonNode> handler = module.getManagedBotHandler();
            invoke(handler != null ? handler : module.getFallback(), update);
            return;
        }
        if (update.has("callback_query")) {
            Consumer<JsonNode> handler = module.getCallbackHandler();
            invoke(handler != null ? handler : module.getFallback(), update);
            return;
        }
        JsonNode message = update.path("message");
        String text = message.path("text").asText("");
        if (text.startsWith("/")) {
            String command = parseCommand(text);
            Consumer<JsonNode> handler = module.getCommands().get(command);
            if (handler != null) {
                invoke(handler, update);
                return;
            }
        }
        if (message.has("contact")) {
            Consumer<JsonNode> handler = module.getContactHandler();
            invoke(handler != null ? handler : module.getFallback(), update);
            return;
        }
        if (!text.isEmpty()) {
            Consumer<JsonNode> handler = module.getTextHandler();
            invoke(handler != null ? handler : module.getFallback(), update);
            return;
        }
        invoke(module.getFallback(), update);
    }

    /** "/start@bot ARG" -> "/start". */
    private static String parseCommand(String text) {
        int space = text.indexOf(' ');
        String token = (space >= 0) ? text.substring(0, space) : text;
        int at = token.indexOf('@');
        return (at >= 0) ? token.substring(0, at) : token;
    }

    private void invoke(Consumer<JsonNode> handler, JsonNode update) {
        if (handler == null) return;
        executor.execute(() -> {
            try {
                handler.accept(update);
            } catch (RuntimeException e) {
                log.warn("update handler threw", e);
            }
        });
    }
}
