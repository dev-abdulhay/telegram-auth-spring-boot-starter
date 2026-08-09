package io.github.dev_abdulhay.telegramauth.service;

import java.util.function.Consumer;

/**
 * Internal pub/sub used by wait endpoints. Listener is invoked at most once
 * (terminal events only). The only implementation is
 * {@link InMemoryAuthEventBus}, for single-instance deployments.
 */
public interface AuthEventBus {

    void subscribe(String tokenHash, Consumer<AuthEvent> listener);

    void unsubscribe(String tokenHash, Consumer<AuthEvent> listener);

    void publish(String tokenHash, AuthEvent event);
}
