package io.github.dev_abdulhay.telegramauth.miniapp;

import java.time.Duration;
import java.util.Objects;

/**
 * Freshness window for Mini App initData.
 *
 * @param maxAge         how old {@code auth_date} may be before the initData counts as expired
 * @param maxFutureSkew  how far {@code auth_date} may lie in the future, to tolerate clock drift
 */
public record MiniAppOptions(Duration maxAge, Duration maxFutureSkew) {

    public MiniAppOptions {
        Objects.requireNonNull(maxAge, "maxAge");
        Objects.requireNonNull(maxFutureSkew, "maxFutureSkew");
        if (maxAge.isNegative() || maxFutureSkew.isNegative()) {
            throw new IllegalArgumentException("maxAge and maxFutureSkew must not be negative");
        }
    }

    /** One hour of age, sixty seconds of future skew. */
    public static MiniAppOptions defaults() {
        return new MiniAppOptions(Duration.ofHours(1), Duration.ofSeconds(60));
    }
}
