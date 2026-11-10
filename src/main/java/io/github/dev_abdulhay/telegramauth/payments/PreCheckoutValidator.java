package io.github.dev_abdulhay.telegramauth.payments;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * Host veto on a pre-checkout query, run after the built-in checks and inside the
 * {@code pre-checkout-timeout} budget. Must be fast: Telegram cancels the payment if no
 * answer arrives within 10 seconds.
 */
@FunctionalInterface
public interface PreCheckoutValidator {

    /** @return a message shown to the user to reject the payment, or empty to allow it */
    Optional<String> validate(StarOrder order, JsonNode preCheckoutQuery);
}
