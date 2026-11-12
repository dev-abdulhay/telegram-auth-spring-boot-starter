package io.github.dev_abdulhay.telegramauth.miniapp;

import java.time.Instant;

/**
 * Remembers which initData {@code hash} values already produced a login, so a captured
 * initData cannot be redeemed twice. Entries only need to live until {@code expiresAt}:
 * after that the validator rejects the initData as expired on its own.
 *
 * <p>The default is in-memory and per-instance; a multi-instance deployment should
 * provide a shared implementation (e.g. Redis) as a bean.
 */
public interface InitDataReplayStore {

    boolean isUsed(String hash);

    void markUsed(String hash, Instant expiresAt);
}
