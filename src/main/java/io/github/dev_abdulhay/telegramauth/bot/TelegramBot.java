package io.github.dev_abdulhay.telegramauth.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Thin Telegram Bot API wrapper, one instance per {@link TelegramBotModule}.
 * Methods are overridable so hosts/tests can substitute behaviour.
 */
public class TelegramBot {

    private static final Logger log = LoggerFactory.getLogger(TelegramBot.class);

    /**
     * Per-request ceiling for the non-polling calls. Without it a stalled
     * connection would pin the dispatcher's worker thread forever and the bot
     * would stop answering every user, not just the one being messaged.
     */
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Default budget for sitting out a {@code 429}. The wait happens on the caller's
     * thread — the single update worker that also serves the auth flow — so it is a
     * ceiling on how long one rate-limited managed-bot call may stall every other
     * update for.
     */
    public static final Duration DEFAULT_MAX_RATE_LIMIT_WAIT = Duration.ofSeconds(60);

    private final HttpClient http;
    private final String token;
    private final String baseUrl;
    private final Duration maxRateLimitWait;

    public TelegramBot(HttpClient http, String token) {
        this(http, token, "https://api.telegram.org");
    }

    public TelegramBot(HttpClient http, String token, String baseUrl) {
        this(http, token, baseUrl, DEFAULT_MAX_RATE_LIMIT_WAIT);
    }

    /**
     * @param maxRateLimitWait longest {@code retry_after} this bot will wait out
     *                         in-line; a longer one throws instead of blocking the
     *                         update worker, leaving the retry to the caller. See
     *                         {@link #DEFAULT_MAX_RATE_LIMIT_WAIT}.
     */
    public TelegramBot(HttpClient http, String token, String baseUrl, Duration maxRateLimitWait) {
        this.http = Objects.requireNonNull(http, "http");
        this.token = Objects.requireNonNull(token, "token");
        this.baseUrl = baseUrl;
        this.maxRateLimitWait = maxRateLimitWait == null ? DEFAULT_MAX_RATE_LIMIT_WAIT : maxRateLimitWait;
    }

    public String getUpdates(long offset, int timeoutSeconds) throws Exception {
        return getUpdates(offset, timeoutSeconds, null);
    }

    /**
     * @param allowedUpdates update types to receive, or {@code null} to let Telegram
     *                       apply its default list. The default list excludes
     *                       {@code managed_bot}, so a manager bot must pass it explicitly.
     */
    public String getUpdates(long offset, int timeoutSeconds, List<String> allowedUpdates) throws Exception {
        StringBuilder url = new StringBuilder(baseUrl).append("/bot").append(token)
                .append("/getUpdates?offset=").append(offset)
                .append("&timeout=").append(timeoutSeconds);
        if (allowedUpdates != null && !allowedUpdates.isEmpty()) {
            url.append("&allowed_updates=")
                    .append(URLEncoder.encode("[\"" + String.join("\",\"", allowedUpdates) + "\"]",
                            StandardCharsets.UTF_8));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds((long) timeoutSeconds + 5))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            log.warn("getUpdates non-2xx: {}", resp.statusCode());
        }
        return resp.body();
    }

    public void sendMessage(long chatId, String text) {
        sendMessage(chatId, text, null);
    }

    /** Sends a message with an optional {@code reply_markup} JSON (inline/reply keyboard). */
    public void sendMessage(long chatId, String text, String replyMarkupJson) {
        StringBuilder body = new StringBuilder("chat_id=").append(chatId)
                .append("&text=").append(URLEncoder.encode(text, StandardCharsets.UTF_8));
        if (replyMarkupJson != null && !replyMarkupJson.isBlank()) {
            body.append("&reply_markup=").append(URLEncoder.encode(replyMarkupJson, StandardCharsets.UTF_8));
        }
        post("sendMessage", body.toString());
    }

    /** Acknowledges an inline-button press (short toast in the client). */
    public void answerCallbackQuery(String callbackQueryId, String text) {
        StringBuilder body = new StringBuilder("callback_query_id=")
                .append(URLEncoder.encode(callbackQueryId, StandardCharsets.UTF_8));
        if (text != null && !text.isBlank()) {
            body.append("&text=").append(URLEncoder.encode(text, StandardCharsets.UTF_8));
        }
        post("answerCallbackQuery", body.toString());
    }

    /** Replaces a message's text; sending no reply_markup drops its inline keyboard. */
    public void editMessageText(long chatId, long messageId, String text) {
        String body = "chat_id=" + chatId + "&message_id=" + messageId
                + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
        post("editMessageText", body);
    }

    private void post(String method, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/bot" + token + "/" + method))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(SEND_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() / 100 != 2) {
                log.warn("{} non-2xx: {}", method, resp.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("{} interrupted", method);
        } catch (Exception e) {
            log.warn("{} failed", method, e);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** @param botUserId Telegram user id of the managed bot, the API's {@code user_id} */
    public String getManagedBotToken(long botUserId) {
        return requireTextResult("getManagedBotToken", postForResult("getManagedBotToken", "user_id=" + botUserId));
    }

    /** Revokes the managed bot's current token and returns the new one. */
    public String replaceManagedBotToken(long botUserId) {
        return requireTextResult("replaceManagedBotToken",
                postForResult("replaceManagedBotToken", "user_id=" + botUserId));
    }

    /**
     * Guards against a malformed {@code ok:true} response whose {@code result} is
     * missing or not a string — returning it as-is would hand the caller an empty
     * token to silently persist instead of a real one.
     */
    private static String requireTextResult(String method, JsonNode result) {
        if (!result.isTextual() || result.asText().isBlank()) {
            throw new TelegramApiException(0, method + " returned a missing or non-string result");
        }
        return result.asText();
    }

    public JsonNode getManagedBotAccessSettings(long botUserId) {
        return postForResult("getManagedBotAccessSettings", "user_id=" + botUserId);
    }

    /**
     * @param addedUserIds up to 10 users who may access the bot besides its owner;
     *                     ignored by Telegram when {@code restricted} is false. An
     *                     <b>empty</b> list clears the allow-list; {@code null}
     *                     omits the parameter and keeps whatever Telegram has.
     */
    public void setManagedBotAccessSettings(long botUserId, boolean restricted, List<Long> addedUserIds) {
        StringBuilder body = new StringBuilder("user_id=").append(botUserId)
                .append("&is_access_restricted=").append(restricted);
        // an EMPTY list is not the same as no list: omitting the parameter makes
        // Telegram keep the previous allow-list, so clearing one would silently
        // no-op. Only a null list means "leave it alone".
        if (addedUserIds != null) {
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < addedUserIds.size(); i++) {
                if (i > 0) json.append(',');
                json.append(addedUserIds.get(i));
            }
            json.append(']');
            body.append("&added_user_ids=")
                    .append(URLEncoder.encode(json.toString(), StandardCharsets.UTF_8));
        }
        postForResult("setManagedBotAccessSettings", body.toString());
    }

    /**
     * POSTs and returns the {@code result} node. A 429 is a wait signal rather than
     * a failure: we honour {@code retry_after} once and retry, which is separate
     * from any retry budget the caller keeps.
     *
     * <p>Past {@link #DEFAULT_MAX_RATE_LIMIT_WAIT} (or whatever budget this bot was
     * built with) we throw instead of sleeping. The wait would run on the single
     * update worker, so a multi-minute {@code retry_after} on one managed-bot call
     * would stall logins for everyone; failing fast hands the problem to the
     * caller's retry policy, which can back off off-thread.
     */
    private JsonNode postForResult(String method, String body) {
        JsonNode response = send(method, body);
        Integer retryAfter = rateLimitDelay(response);
        if (retryAfter != null) {
            if (retryAfter > maxRateLimitWait.toSeconds()) {
                throw new TelegramApiException(429, method + " is rate-limited for " + retryAfter
                        + "s, beyond the " + maxRateLimitWait.toSeconds() + "s this bot waits in-line");
            }
            try {
                Thread.sleep(retryAfter * 1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new TelegramApiException(429, "interrupted while waiting out a rate limit");
            }
            response = send(method, body);
        }
        if (!response.path("ok").asBoolean(false)) {
            throw new TelegramApiException(response.path("error_code").asInt(0),
                    response.path("description").asText("unknown error"));
        }
        return response.path("result");
    }

    private static Integer rateLimitDelay(JsonNode response) {
        if (response.path("error_code").asInt(0) != 429) return null;
        int seconds = response.path("parameters").path("retry_after").asInt(1);
        return Math.max(seconds, 1);
    }

    private JsonNode send(String method, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/bot" + token + "/" + method))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(SEND_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return MAPPER.readTree(resp.body());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new TelegramApiException(0, method + " interrupted");
        } catch (Exception e) {
            throw new TelegramApiException(0, method + " failed: " + e.getClass().getSimpleName());
        }
    }

    public String maskedToken() {
        if (token == null || token.length() < 12) return "***";
        return token.substring(0, 6) + "..." + token.substring(token.length() - 4);
    }
}
