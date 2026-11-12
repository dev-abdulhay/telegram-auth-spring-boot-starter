package io.github.dev_abdulhay.telegramauth.miniapp;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-instance {@link InitDataReplayStore}. {@link #claim} is atomic per hash via
 * {@link ConcurrentHashMap#compute}; expired entries are pruned on each claim.
 */
public class InMemoryInitDataReplayStore implements InitDataReplayStore {

    private final ConcurrentHashMap<String, Instant> claims = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryInitDataReplayStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean claim(String hash, Instant expiresAt) {
        Instant now = clock.instant();
        claims.values().removeIf(existing -> existing.isBefore(now));
        boolean[] won = {false};
        claims.compute(hash, (key, existing) -> {
            if (existing != null && !existing.isBefore(now)) {
                return existing;
            }
            won[0] = true;
            return expiresAt;
        });
        return won[0];
    }

    @Override
    public void release(String hash) {
        claims.remove(hash);
    }
}
