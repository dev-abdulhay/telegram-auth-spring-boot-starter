package io.github.dev_abdulhay.telegramauth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Single-process pub/sub. The starter falls back to this when no Redis bean
 * is available — fine for one-instance deployments. Listeners are removed
 * from the registry the moment the event is dispatched, so a terminal event
 * cannot be observed twice.
 */
public class InMemoryAuthEventBus implements AuthEventBus {

    private static final Logger log = LoggerFactory.getLogger(InMemoryAuthEventBus.class);

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<AuthEvent>>> registry =
            new ConcurrentHashMap<>();

    @Override
    public void subscribe(String tokenHash, Consumer<AuthEvent> listener) {
        registry.computeIfAbsent(tokenHash, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    @Override
    public void unsubscribe(String tokenHash, Consumer<AuthEvent> listener) {
        CopyOnWriteArrayList<Consumer<AuthEvent>> listeners = registry.get(tokenHash);
        if (listeners == null) return;
        listeners.remove(listener);
        if (listeners.isEmpty()) {
            registry.remove(tokenHash, listeners);
        }
    }

    @Override
    public void publish(String tokenHash, AuthEvent event) {
        List<Consumer<AuthEvent>> listeners = registry.remove(tokenHash);
        if (listeners == null || listeners.isEmpty()) {
            log.debug("publish: no local listeners for hash[0..8]={}",
                    tokenHash.substring(0, Math.min(8, tokenHash.length())));
            return;
        }
        for (Consumer<AuthEvent> l : listeners) {
            try {
                l.accept(event);
            } catch (RuntimeException e) {
                log.warn("listener threw on event dispatch", e);
            }
        }
    }
}
