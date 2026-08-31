package io.github.dev_abdulhay.telegramauth.managedbots;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Builds the deep link that asks a user to create a bot managed by this bot.
 *
 * <p>The suggested username is only a <em>suggestion</em>: the user can change it
 * in Telegram's confirmation dialog, and the Bot API offers no way to check
 * whether a username is still free. Validation here is local and only rejects
 * what Telegram could never accept.
 */
public final class ManagedBotLink {

    private static final String BASE = "https://t.me/newbot/";

    private ManagedBotLink() {
    }

    public static String build(String managerUsername, String suggestedUsername, String suggestedName) {
        if (managerUsername == null || managerUsername.isBlank()) {
            throw new IllegalArgumentException("managerUsername must not be blank");
        }
        StringBuilder link = new StringBuilder(BASE).append(managerUsername.trim());
        if (suggestedUsername != null && !suggestedUsername.isBlank()) {
            link.append('/').append(validateUsername(suggestedUsername.trim()));
        }
        if (suggestedName != null && !suggestedName.isBlank()) {
            link.append("?name=").append(URLEncoder.encode(suggestedName, StandardCharsets.UTF_8));
        }
        return link.toString();
    }

    private static String validateUsername(String username) {
        if (username.length() < 5 || username.length() > 32) {
            throw new IllegalArgumentException(
                    "a bot username must be 5-32 characters but was " + username.length());
        }
        for (int i = 0; i < username.length(); i++) {
            char c = username.charAt(i);
            boolean allowed = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!allowed) {
                throw new IllegalArgumentException(
                        "a bot username may only contain A-Z, a-z, 0-9 and _ but was " + username);
            }
        }
        if (!username.toLowerCase().endsWith("bot")) {
            throw new IllegalArgumentException("a bot username must end with 'bot' but was " + username);
        }
        return username;
    }
}
