package io.github.dev_abdulhay.telegramauth.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

    private final HttpClient http;
    private final String token;
    private final String baseUrl;

    public TelegramBot(HttpClient http, String token) {
        this(http, token, "https://api.telegram.org");
    }

    public TelegramBot(HttpClient http, String token, String baseUrl) {
        this.http = Objects.requireNonNull(http, "http");
        this.token = Objects.requireNonNull(token, "token");
        this.baseUrl = baseUrl;
    }

    public String getUpdates(long offset, int timeoutSeconds) throws Exception {
        String url = baseUrl + "/bot" + token + "/getUpdates?offset=" + offset
                + "&timeout=" + timeoutSeconds;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
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

    public String maskedToken() {
        if (token == null || token.length() < 12) return "***";
        return token.substring(0, 6) + "..." + token.substring(token.length() - 4);
    }
}
