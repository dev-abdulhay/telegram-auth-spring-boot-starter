package io.github.dev_abdulhay.telegramauth.api.dto;

/**
 * Hook context passed to {@link io.github.dev_abdulhay.telegramauth.api.TelegramAuthRegisterHandler}.
 * The host may call {@link #setExternalUserId(String)} to link the new
 * Telegram user to its own user table. Leave unset for fully decoupled mode.
 */
public final class RegisterContext {

    private String externalUserId;

    public String getExternalUserId() {
        return externalUserId;
    }

    public void setExternalUserId(String externalUserId) {
        this.externalUserId = externalUserId;
    }
}
