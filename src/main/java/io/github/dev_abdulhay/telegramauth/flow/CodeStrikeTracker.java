package io.github.dev_abdulhay.telegramauth.flow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts failed confirmation-code logins per Telegram user and cools the user
 * down once they pile up.
 *
 * <p>Rejecting the session alone does not stop an attacker: they open a new one
 * and send the victim a fresh link, so {@code 1/n} per session becomes
 * {@code 1 - (1 - 1/n)^k} over {@code k} rounds. The cooldown is what makes the
 * per-session odds hold over time — after it arms, the attacker gets one guess
 * per window and the window doubles with every further failure.
 *
 * <p><b>One strike per dead login, not per guess.</b> A {@code TYPED} user who
 * burns all three attempts on one session has failed once, not three times.
 *
 * <p>JVM-local and not replicated, like the flow's pending-login map: a restart
 * forgives outstanding strikes. That is a deliberate trade — the alternative is
 * a schema change for state that an attacker cannot force us to lose anyway.
 */
public final class CodeStrikeTracker {

    private static final Logger log = LoggerFactory.getLogger(CodeStrikeTracker.class);

    /** Hard ceiling on tracked users, mirroring the flow's pending-login map. */
    private static final int MAX_ENTRIES = 10_000;
    /** Caps the doubling exponent so the shift cannot overflow on absurd strike counts. */
    private static final int MAX_EXPONENT = 16;

    private record Strikes(int count, OffsetDateTime until, OffsetDateTime touchedAt) {}

    private final ConcurrentHashMap<Long, Strikes> byUser = new ConcurrentHashMap<>();
    private final Duration base;
    private final Duration max;
    private final int threshold;

    /**
     * @param base      first cooldown once the threshold is reached; {@code ZERO} disables
     *                  cooldowns entirely while still counting strikes
     * @param max       ceiling for the doubling ladder
     * @param threshold how many failed logins arm the first cooldown ({@code 1} = immediately)
     */
    public CodeStrikeTracker(Duration base, Duration max, int threshold) {
        this.base = base;
        this.max = max;
        this.threshold = threshold;
    }

    /** @return how much cooldown is left for this user, or {@code null} if they are free to log in */
    public Duration remaining(long userId) {
        Strikes s = byUser.get(userId);
        if (s == null || s.until() == null) return null;
        Duration left = Duration.between(OffsetDateTime.now(), s.until());
        return left.isNegative() || left.isZero() ? null : left;
    }

    /**
     * Records one failed login.
     *
     * @return the cooldown that was armed, or {@code null} when the user is still
     *         below the threshold (or cooldowns are disabled)
     */
    public Duration strike(long userId) {
        purge();
        Strikes prev = byUser.get(userId);
        int count = (prev == null ? 0 : prev.count()) + 1;
        OffsetDateTime now = OffsetDateTime.now();

        Duration cooldown = null;
        if (!base.isZero() && !base.isNegative() && count >= threshold) {
            int exponent = Math.min(count - threshold, MAX_EXPONENT);
            cooldown = base.multipliedBy(1L << exponent);
            if (cooldown.compareTo(max) > 0) cooldown = max;
        }
        evictOldestIfFull(userId);
        byUser.put(userId, new Strikes(count, cooldown == null ? null : now.plus(cooldown), now));
        return cooldown;
    }

    /** Forgets a user's history — called when they finally log in successfully. */
    public void clear(long userId) {
        byUser.remove(userId);
    }

    /** Drops entries that have not been touched for a full {@code max} window. */
    public void purge() {
        if (byUser.isEmpty()) return;
        OffsetDateTime cutoff = OffsetDateTime.now().minus(max);
        byUser.entrySet().removeIf(e -> e.getValue().touchedAt().isBefore(cutoff));
    }

    /** Tracked users; exposed for tests and diagnostics. */
    public int size() {
        return byUser.size();
    }

    private void evictOldestIfFull(long incomingUserId) {
        if (byUser.size() < MAX_ENTRIES || byUser.containsKey(incomingUserId)) return;
        byUser.entrySet().stream()
                .min(Comparator.comparing(e -> e.getValue().touchedAt()))
                .ifPresent(oldest -> {
                    log.warn("code-strike map at capacity ({}), evicting the oldest entry", MAX_ENTRIES);
                    byUser.remove(oldest.getKey(), oldest.getValue());
                });
    }
}
