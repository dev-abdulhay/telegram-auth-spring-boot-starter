package io.github.dev_abdulhay.telegramauth.miniapp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dev_abdulhay.telegramauth.api.dto.TelegramUserInfo;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * Verifies Telegram Mini App initData as described in
 * <a href="https://core.telegram.org/bots/webapps#validating-data-received-via-the-mini-app">the Bot API docs</a>:
 * HMAC-SHA256 over the sorted {@code key=value} lines (minus {@code hash} and {@code signature}), keyed by
 * {@code HMAC_SHA256("WebAppData", botToken)}, then a freshness check on {@code auth_date}.
 *
 * <p>Stateless and thread-safe. Every rejection is a {@link MiniAppAuthException}.
 */
public final class MiniAppInitDataValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public MiniAppInitData validate(String rawInitData, String botToken, MiniAppOptions options, Instant now) {
        if (rawInitData == null || rawInitData.isBlank()) {
            throw new MiniAppAuthException(MiniAppError.INIT_DATA_MISSING, "initData is empty");
        }
        Map<String, String> fields = parse(rawInitData);

        String hash = fields.get("hash");
        if (hash == null) {
            throw invalid("hash is missing");
        }
        byte[] expected = HexFormat.of().formatHex(hmac(secretKey(botToken), checkString(fields)))
                .getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, hash.getBytes(StandardCharsets.US_ASCII))) {
            throw invalid("hash does not match");
        }

        Instant authDate = authDate(fields.get("auth_date"));
        if (authDate.isBefore(now.minus(options.maxAge()))) {
            throw new MiniAppAuthException(MiniAppError.INIT_DATA_EXPIRED, "auth_date is older than maxAge");
        }
        if (authDate.isAfter(now.plus(options.maxFutureSkew()))) {
            throw new MiniAppAuthException(MiniAppError.INIT_DATA_EXPIRED, "auth_date is too far in the future");
        }

        return new MiniAppInitData(authDate, user(fields.get("user")), fields.get("start_param"), hash);
    }

    private static Map<String, String> parse(String raw) {
        Map<String, String> fields = new TreeMap<>();
        for (String pair : raw.strip().split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                throw invalid("malformed pair");
            }
            try {
                fields.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw invalid("malformed percent-encoding");
            }
        }
        return fields;
    }

    // fields is a TreeMap, so iteration order is already the lexicographic key order the spec requires
    private static byte[] checkString(Map<String, String> fields) {
        StringJoiner lines = new StringJoiner("\n");
        fields.forEach((key, value) -> {
            if (!key.equals("hash") && !key.equals("signature")) {
                lines.add(key + "=" + value);
            }
        });
        return lines.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] secretKey(String botToken) {
        return hmac("WebAppData".getBytes(StandardCharsets.UTF_8), botToken.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, byte[] msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(msg);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static Instant authDate(String value) {
        if (value == null) {
            throw invalid("auth_date is missing");
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(value));
        } catch (NumberFormatException e) {
            throw invalid("auth_date is not a number");
        }
    }

    private static TelegramUserInfo user(String json) {
        if (json == null) {
            throw invalid("user is missing");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw invalid("user is not valid JSON");
        }
        if (node == null || !node.path("id").isIntegralNumber()) {
            throw invalid("user.id is missing");
        }
        return new TelegramUserInfo(node.get("id").asLong(), null, text(node, "first_name"),
                text(node, "last_name"), text(node, "username"), text(node, "language_code"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static MiniAppAuthException invalid(String message) {
        return new MiniAppAuthException(MiniAppError.INIT_DATA_INVALID, message);
    }
}
