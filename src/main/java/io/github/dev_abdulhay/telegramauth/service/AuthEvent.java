package io.github.dev_abdulhay.telegramauth.service;

import java.util.Map;

/**
 * Terminal event published on the {@link AuthEventBus} when a session
 * transitions out of the {@code PENDING} state.
 */
public record AuthEvent(Type type, Map<String, Object> payload) {

    public enum Type { APPROVED, REJECTED, EXPIRED }

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
