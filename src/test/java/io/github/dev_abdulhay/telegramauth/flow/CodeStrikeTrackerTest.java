package io.github.dev_abdulhay.telegramauth.flow;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CodeStrikeTrackerTest {

    private static CodeStrikeTracker tracker(int threshold) {
        return new CodeStrikeTracker(Duration.ofMinutes(5), Duration.ofHours(1), threshold);
    }

    @Test
    void firstStrikeArmsTheCooldownWhenTheThresholdIsOne() {
        CodeStrikeTracker t = tracker(1);
        assertThat(t.remaining(7L)).isNull();

        assertThat(t.strike(7L)).isEqualTo(Duration.ofMinutes(5));
        assertThat(t.remaining(7L)).isNotNull().isLessThanOrEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void aHigherThresholdGrantsFreeStrikesFirst() {
        CodeStrikeTracker t = tracker(3);

        assertThat(t.strike(7L)).isNull();
        assertThat(t.strike(7L)).isNull();
        assertThat(t.remaining(7L)).isNull();

        assertThat(t.strike(7L)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void repeatedStrikesDoubleTheCooldownUpToTheCeiling() {
        CodeStrikeTracker t = tracker(1);

        assertThat(t.strike(7L)).isEqualTo(Duration.ofMinutes(5));
        assertThat(t.strike(7L)).isEqualTo(Duration.ofMinutes(10));
        assertThat(t.strike(7L)).isEqualTo(Duration.ofMinutes(20));
        assertThat(t.strike(7L)).isEqualTo(Duration.ofMinutes(40));
        // 80 minutes would exceed the ceiling
        assertThat(t.strike(7L)).isEqualTo(Duration.ofHours(1));
        assertThat(t.strike(7L)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void aSuccessfulLoginResetsTheLadder() {
        CodeStrikeTracker t = tracker(1);
        t.strike(7L);
        t.strike(7L);

        t.clear(7L);

        assertThat(t.remaining(7L)).isNull();
        assertThat(t.strike(7L)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void usersAreTrackedIndependently() {
        CodeStrikeTracker t = tracker(1);
        t.strike(7L);
        t.strike(7L);

        assertThat(t.strike(8L)).isEqualTo(Duration.ofMinutes(5));
        assertThat(t.remaining(9L)).isNull();
    }

    @Test
    void anExpiredCooldownStopsBlockingButKeepsTheLadder() {
        // a 1 ns base cools down instantly, which is the fastest way to observe the boundary
        CodeStrikeTracker t = new CodeStrikeTracker(Duration.ofNanos(1), Duration.ofHours(1), 1);

        t.strike(7L);
        assertThat(t.remaining(7L)).isNull();
        // the count survived, so the next failure is punished harder
        assertThat(t.strike(7L)).isEqualTo(Duration.ofNanos(2));
    }

    @Test
    void aZeroBaseDisablesCooldownsEntirely() {
        CodeStrikeTracker t = new CodeStrikeTracker(Duration.ZERO, Duration.ofHours(1), 1);

        assertThat(t.strike(7L)).isNull();
        assertThat(t.strike(7L)).isNull();
        assertThat(t.remaining(7L)).isNull();
    }

    @Test
    void purgeDropsEntriesOlderThanTheCeilingWindow() {
        CodeStrikeTracker t = new CodeStrikeTracker(Duration.ofNanos(1), Duration.ofNanos(1), 1);
        t.strike(7L);
        assertThat(t.size()).isEqualTo(1);

        t.purge();

        assertThat(t.size()).isZero();
    }
}
