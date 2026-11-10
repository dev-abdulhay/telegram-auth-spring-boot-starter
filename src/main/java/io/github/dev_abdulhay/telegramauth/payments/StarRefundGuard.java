package io.github.dev_abdulhay.telegramauth.payments;

import java.util.Optional;

/**
 * Veto hook consulted before a bot-initiated refund, so the core stays ledger-agnostic
 * (the balance module enforces its refund policy here).
 */
@FunctionalInterface
public interface StarRefundGuard {

    /** @return a reason to refuse the refund, or empty to allow it */
    Optional<String> check(StarOrder order);
}
