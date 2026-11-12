package io.github.dev_abdulhay.telegramauth.miniapp;

/** Mini App login failures with their HTTP status and wire code. */
public enum MiniAppError {

    INIT_DATA_MISSING(400, "init_data_missing"),
    INIT_DATA_INVALID(401, "init_data_invalid"),
    INIT_DATA_EXPIRED(401, "init_data_expired"),
    USER_BLOCKED(403, "user_blocked");

    private final int httpStatus;
    private final String code;

    MiniAppError(int httpStatus, String code) {
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }
}
