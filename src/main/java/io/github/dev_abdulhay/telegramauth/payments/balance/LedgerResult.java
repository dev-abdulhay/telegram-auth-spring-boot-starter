package io.github.dev_abdulhay.telegramauth.payments.balance;

/**
 * Outcome of a ledger operation.
 *
 * @param applied {@code false} when the refKey was already used and nothing changed
 * @param balance the account balance after the call
 */
public record LedgerResult(boolean applied, long balance) {
}
