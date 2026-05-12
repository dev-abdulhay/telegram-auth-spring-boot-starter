package io.github.dev_abdulhay.telegramauth.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Thin Telegram Bot API wrapper used by the polling loop. Only the operations
 * needed by MVP are exposed: {@code getUpdates}, {@code sendMessage},
 * {@code answerCallbackQuery}. A richer client can be plugged in later by
 * overriding this bean.
 */
public class TelegramBotClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotClient.class);

    private final HttpClient http;
    private final String token;
    private final String baseUrl;

    public TelegramBotClient(HttpClient http, String token) {
        this(http, token, "https://api.telegram.org");
    }

    public TelegramBotClient(HttpClient http, String token, String baseUrl) {
        this.http = http;
        this.token = token;
        this.baseUrl = baseUrl;
    }

    public String getUpdates(long offset, int timeoutSeconds) throws Exception {
        String url = baseUrl + "/bot" + token + "/getUpdates"
                + "?offset=" + offset
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
        try {
            String body = "chat_id=" + chatId + "&text=" + java.net.URLEncoder.encode(text, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/bot" + token + "/sendMessage"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("sendMessage failed", e);
        }
    }

    public String maskedToken() {
        if (token == null || token.length() < 12) return "***";
        return token.substring(0, 6) + "..." + token.substring(token.length() - 4);
    }
}
