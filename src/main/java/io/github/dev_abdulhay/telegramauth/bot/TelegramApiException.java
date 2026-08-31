package io.github.dev_abdulhay.telegramauth.bot;

/** Thrown when the Bot API answers with {@code ok:false} or an unrecoverable HTTP status. */
public class TelegramApiException extends RuntimeException {

    private final int errorCode;

    public TelegramApiException(int errorCode, String description) {
        super("Telegram API error " + errorCode + ": " + description);
        this.errorCode = errorCode;
    }

    public int getErrorCode() {
        return errorCode;
    }
}
