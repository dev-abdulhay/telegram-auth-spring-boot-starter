package io.github.dev_abdulhay.telegramauth.service;

import java.util.Map;

/**
 * Event published on the {@link AuthEventBus} when a session transitions out of
 * the {@code PENDING} state. {@code AWAITING_CODE} is the one non-terminal type:
 * the login advanced but has not finished.
 */
public record AuthEvent(Type type, Map<String, Object> payload) {

    public enum Type { AWAITING_CODE, APPROVED, REJECTED, EXPIRED }

    /**
     * Non-terminal: the browser may now show its confirmation code. The code is
     * deliberately not carried here — it is a pure function of the token hash,
     * which every subscriber already has.
     */
    public static AuthEvent awaitingCode() {
        return new AuthEvent(Type.AWAITING_CODE, Map.of());
    }

    public static AuthEvent approved(Map<String, Object> payload) {
        return new AuthEvent(Type.APPROVED, payload);
    }

    public static AuthEvent rejected() {
        return new AuthEvent(Type.REJECTED, Map.of());
    }

    public static AuthEvent expired() {
        return new AuthEvent(Type.EXPIRED, Map.of());
    }
}
