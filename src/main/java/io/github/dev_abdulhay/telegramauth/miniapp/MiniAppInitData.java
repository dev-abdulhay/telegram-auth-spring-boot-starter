package io.github.dev_abdulhay.telegramauth.miniapp;

import io.github.dev_abdulhay.telegramauth.api.dto.TelegramUserInfo;

import java.time.Instant;

/**
 * Mini App initData whose signature and freshness have been verified.
 *
 * @param authDate    when Telegram issued the initData
 * @param user        the Telegram user; {@code phone} is always {@code null} because initData does not carry it
 * @param startParam  the {@code start_param} deep-link payload; may be {@code null}
 * @param hash        the verified {@code hash} field
 */
public record MiniAppInitData(Instant authDate, TelegramUserInfo user, String startParam, String hash) {
}
