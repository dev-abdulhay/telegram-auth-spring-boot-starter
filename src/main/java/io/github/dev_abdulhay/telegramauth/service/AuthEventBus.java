package io.github.dev_abdulhay.telegramauth.service;

import java.util.function.Consumer;

/**
 * Internal pub/sub used by wait endpoints. Dispatching an event removes the
 * listener from the registry, so each subscription observes exactly one event.
 * {@link AuthEvent.Type#AWAITING_CODE} is non-terminal: after it fires the
 * client re-subscribes on its next poll. The only implementation is
 * {@link InMemoryAuthEventBus}, for single-instance deployments.
 */
public interface AuthEventBus {

    void subscribe(String tokenHash, Consumer<AuthEvent> listener);

    void unsubscribe(String tokenHash, Consumer<AuthEvent> listener);

    void publish(String tokenHash, AuthEvent event);
}
