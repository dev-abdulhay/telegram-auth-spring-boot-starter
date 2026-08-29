package io.github.dev_abdulhay.telegramauth.service;

/**
 * Thrown by {@link AbstractSessionService#create} when a client IP already has
 * the maximum allowed number of PENDING sessions
 * ({@code TelegramBotModule.Builder#maxPendingPerIp}). Mapped to HTTP 429 by
 * {@code AbstractTelegramAuthController}.
 */
public class SessionRateLimitException extends RuntimeException {

    public SessionRateLimitException(String ipAddress) {
        super("too many pending sessions for ip " + ipAddress);
    }
}
