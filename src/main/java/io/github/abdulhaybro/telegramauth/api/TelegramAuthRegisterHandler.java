package io.github.abdulhaybro.telegramauth.api;

import io.github.abdulhaybro.telegramauth.api.dto.RegisterContext;
import io.github.abdulhaybro.telegramauth.api.dto.TelegramUserInfo;

/**
 * Optional host-application hook fired once when a brand-new Telegram user
 * completes the in-bot registration flow. Host may set
 * {@link RegisterContext#setExternalUserId(String)} to persist the link.
 */
@FunctionalInterface
public interface TelegramAuthRegisterHandler {

    void onFirstRegister(TelegramUserInfo user, RegisterContext ctx);
}
