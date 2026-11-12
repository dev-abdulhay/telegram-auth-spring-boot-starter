package io.github.dev_abdulhay.telegramauth.miniapp;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Single-instance {@link InitDataReplayStore}; expired entries are dropped on each {@link #isUsed} call. */
public class InMemoryInitDataReplayStore implements InitDataReplayStore {

    private final Map<String, Instant> used = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryInitDataReplayStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean isUsed(String hash) {
        Instant now = clock.instant();
        used.values().removeIf(expiresAt -> !expiresAt.isAfter(now));
        return used.containsKey(hash);
    }

    @Override
    public void markUsed(String hash, Instant expiresAt) {
        used.put(hash, expiresAt);
    }
}
