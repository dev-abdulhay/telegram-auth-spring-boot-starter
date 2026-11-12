package io.github.dev_abdulhay.telegramauth.miniapp;

/** A Mini App login the starter refuses; {@link #error()} says why and how to answer. */
public class MiniAppAuthException extends RuntimeException {

    private final MiniAppError error;

    public MiniAppAuthException(MiniAppError error, String message) {
        super(message);
        this.error = error;
    }

    public MiniAppError error() {
        return error;
    }
}
