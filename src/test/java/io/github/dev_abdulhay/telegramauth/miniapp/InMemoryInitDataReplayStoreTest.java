package io.github.dev_abdulhay.telegramauth.miniapp;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryInitDataReplayStoreTest {

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final Instant t0 = Instant.parse("2026-11-13T10:00:00Z");

    @Test
    void secondClaimOfALiveHashFails() {
        InMemoryInitDataReplayStore store = new InMemoryInitDataReplayStore(new MutableClock(t0));

        assertThat(store.claim("abc", t0.plusSeconds(60))).isTrue();
        assertThat(store.claim("abc", t0.plusSeconds(60))).isFalse();
        assertThat(store.claim("other", t0.plusSeconds(60))).isTrue();
    }

    @Test
    void releasedHashCanBeClaimedAgain() {
        InMemoryInitDataReplayStore store = new InMemoryInitDataReplayStore(new MutableClock(t0));
        store.claim("abc", t0.plusSeconds(60));

        store.release("abc");

        assertThat(store.claim("abc", t0.plusSeconds(60))).isTrue();
    }

    @Test
    void entryStaysLiveAtExpiresAtAndLapsesAfter() {
        MutableClock clock = new MutableClock(t0);
        InMemoryInitDataReplayStore store = new InMemoryInitDataReplayStore(clock);
        store.claim("abc", t0.plusSeconds(60));

        // the validator still accepts at now == authDate + maxAge, so the claim must hold there too
        clock.advance(Duration.ofSeconds(60));
        assertThat(store.claim("abc", t0.plusSeconds(60))).isFalse();

        clock.advance(Duration.ofSeconds(1));
        assertThat(store.claim("abc", t0.plusSeconds(120))).isTrue();
    }

    @Test
    void concurrentClaimsOfOneHashLetExactlyOneThrough() throws Exception {
        InMemoryInitDataReplayStore store = new InMemoryInitDataReplayStore(new MutableClock(t0));
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return store.claim("abc", t0.plusSeconds(60));
                }));
            }
            start.countDown();

            int wins = 0;
            for (Future<Boolean> r : results) {
                if (r.get(5, TimeUnit.SECONDS)) wins++;
            }
            assertThat(wins).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
