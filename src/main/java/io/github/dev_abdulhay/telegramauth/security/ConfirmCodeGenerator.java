package io.github.dev_abdulhay.telegramauth.security;

/**
 * Derives the browser-visible confirmation code from a session's token hash.
 *
 * <p><b>Must be a pure function of {@code tokenHash}.</b> The bot flow and the
 * REST controller each derive the code independently and never exchange it, so
 * an implementation that is random, stateful, or time-dependent makes the two
 * sides disagree and every login fails.
 *
 * <p>The code is not a secret: anyone holding the deep link holds the raw token
 * and can compute it. Its job is to prove that whoever confirms the login is
 * looking at the browser screen that started it.
 */
@FunctionalInterface
public interface ConfirmCodeGenerator {

    /**
     * @param tokenHash the session's SHA-256 token hash (64 lowercase hex chars)
     * @return the code to show in the browser and ask for in the bot
     */
    int codeFor(String tokenHash);
}
