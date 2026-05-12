package io.github.abdulhaybro.telegramauth.api.dto;

/**
 * Immutable snapshot of a Telegram user passed into the host-app callback.
 *
 * @param telegramId    Telegram numeric user/chat id
 * @param phone         E.164 phone number without leading {@code +}
 * @param firstName     User-confirmed first name
 * @param lastName      User-confirmed last name; may be {@code null}
 * @param username      Telegram {@code @username}; may be {@code null}
 * @param languageCode  Telegram-detected language ({@code uz}, {@code ru}, {@code en})
 * @param externalUserId  Host-app-owned user id, if previously linked; may be {@code null}
 */
public record TelegramUserInfo(
        Long telegramId,
        String phone,
        String firstName,
        String lastName,
        String username,
        String languageCode,
        String externalUserId
) {
}
