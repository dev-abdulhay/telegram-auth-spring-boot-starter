package io.github.dev_abdulhay.telegramauth.payments.balance;

import java.util.List;

/**
 * Internal credit balance per {@link BalanceAccountKey}. Every change carries a globally unique
 * {@code refKey} (max 160 chars): reusing one returns {@code applied=false} with the current
 * balance and changes nothing, even if the amount differs, so callers can retry safely.
 * {@code amount} must be positive; {@code reason} is at most 64 chars.
 */
public interface BalanceLedger {

    LedgerResult credit(BalanceAccountKey account, long amount, String refKey, String reason);

    /** @throws InsufficientBalanceException if the balance is below {@code amount}; nothing is recorded */
    LedgerResult debit(BalanceAccountKey account, long amount, String refKey, String reason);

    /** Like {@link #debit} but may take the balance below zero (refunds of spent credits). */
    LedgerResult debitAllowNegative(BalanceAccountKey account, long amount, String refKey, String reason);

    /** @return the balance, {@code 0} for an unknown account */
    long balance(BalanceAccountKey account);

    /** @return entries of the account, newest first */
    List<LedgerEntry> history(BalanceAccountKey account, int page, int size);
}
