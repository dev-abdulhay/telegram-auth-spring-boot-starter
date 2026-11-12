package io.github.dev_abdulhay.telegramauth.miniapp;

import java.time.Instant;

/**
 * Makes each initData {@code hash} redeemable once, so a captured initData cannot log in
 * twice — not even by two requests racing each other. A claim only needs to live until
 * {@code expiresAt}: after that the validator rejects the initData as expired on its own.
 *
 * <p>The default is in-memory and per-instance. A multi-instance deployment must provide a
 * shared implementation as a bean, with an atomic claim (e.g. Redis {@code SET key NX PX ttl}).
 */
public interface InitDataReplayStore {

    /**
     * Atomically records {@code hash} until {@code expiresAt}.
     *
     * @return {@code true} if the hash was free (absent, or its earlier claim expired);
     *         {@code false} if a live claim already exists. A claim is live while
     *         {@code now <= expiresAt}.
     */
    boolean claim(String hash, Instant expiresAt);

    /** Drops a claim so the same initData can be retried — used when the login did not go through. */
    void release(String hash);
}
