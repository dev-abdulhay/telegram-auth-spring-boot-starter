package io.github.dev_abdulhay.telegramauth.miniapp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * Test helper that signs Mini App initData the way Telegram does, independently of
 * {@link MiniAppInitDataValidator}, so tests check the validator against the spec rather than itself.
 */
public final class MiniAppTestSigner {

    private MiniAppTestSigner() {
    }

    /** Returns {@code fields} plus a valid {@code hash} as a URL-encoded query string. */
    public static String sign(String botToken, Map<String, String> fields) {
        StringJoiner checkString = new StringJoiner("\n");
        new TreeMap<>(fields).forEach((k, v) -> {
            if (!k.equals("hash")) {
                checkString.add(k + "=" + v);
            }
        });
        byte[] secret = hmac("WebAppData".getBytes(StandardCharsets.UTF_8),
                botToken.getBytes(StandardCharsets.UTF_8));
        String hash = HexFormat.of().formatHex(
                hmac(secret, checkString.toString().getBytes(StandardCharsets.UTF_8)));

        Map<String, String> all = new LinkedHashMap<>(fields);
        all.put("hash", hash);
        StringJoiner query = new StringJoiner("&");
        all.forEach((k, v) -> query.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return query.toString();
    }

    private static byte[] hmac(byte[] key, byte[] msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(msg);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
