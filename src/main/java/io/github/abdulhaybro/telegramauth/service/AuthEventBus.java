package io.github.abdulhaybro.telegramauth.service;

import java.util.function.Consumer;

/**
 * Internal pub/sub used by wait endpoints. Listener is invoked at most once
 * (terminal events only). Implementations: {@link InMemoryAuthEventBus} for
 * single-instance deployments; a Redis-backed variant is on the roadmap.
 */
public interface AuthEventBus {

    void subscribe(String tokenHash, Consumer<AuthEvent> listener);

    void unsubscribe(String tokenHash, Consumer<AuthEvent> listener);

    void publish(String tokenHash, AuthEvent event);
}
