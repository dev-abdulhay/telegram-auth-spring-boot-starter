package io.github.dev_abdulhay.telegramauth.miniapp;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryInitDataReplayStoreTest {

    private static final class MutableClock extends Clock {
        private Instant now;

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
    void markedHashIsUsed() {
        InMemoryInitDataReplayStore store = new InMemoryInitDataReplayStore(new MutableClock(t0));

        assertThat(store.isUsed("abc")).isFalse();
        store.markUsed("abc", t0.plusSeconds(60));

        assertThat(store.isUsed("abc")).isTrue();
        assertThat(store.isUsed("other")).isFalse();
    }

    @Test
    void hashIsForgottenOncePastExpiresAt() {
        MutableClock clock = new MutableClock(t0);
        InMemoryInitDataReplayStore store = new InMemoryInitDataReplayStore(clock);
        store.markUsed("abc", t0.plusSeconds(60));

        clock.advance(Duration.ofSeconds(59));
        assertThat(store.isUsed("abc")).isTrue();

        clock.advance(Duration.ofSeconds(2));
        assertThat(store.isUsed("abc")).isFalse();
    }
}
