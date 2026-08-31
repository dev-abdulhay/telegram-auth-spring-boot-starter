package io.github.dev_abdulhay.telegramauth.bot;

import java.time.Duration;

/**
 * Notified when a runner gives up on a bot that has been failing to poll for its
 * whole failure budget — most often a token the owner revoked in BotFather.
 *
 * <p>Deliberately declared here rather than in a higher-level package: {@code bot}
 * must not know that managed bots or the white-label runtime exist.
 */
@FunctionalInterface
public interface PollFailureListener {

    /**
     * @param failingFor how long polling had been failing without a single success
     */
    void onPollFailure(TelegramBotModule module, Duration failingFor);
}
