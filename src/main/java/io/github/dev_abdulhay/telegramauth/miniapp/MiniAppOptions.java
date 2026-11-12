package io.github.dev_abdulhay.telegramauth.miniapp;

import java.time.Duration;

/**
 * Freshness window for Mini App initData.
 *
 * @param maxAge         how old {@code auth_date} may be before the initData counts as expired
 * @param maxFutureSkew  how far {@code auth_date} may lie in the future, to tolerate clock drift
 */
public record MiniAppOptions(Duration maxAge, Duration maxFutureSkew) {

    /** One hour of age, sixty seconds of future skew. */
    public static MiniAppOptions defaults() {
        return new MiniAppOptions(Duration.ofHours(1), Duration.ofSeconds(60));
    }
}
