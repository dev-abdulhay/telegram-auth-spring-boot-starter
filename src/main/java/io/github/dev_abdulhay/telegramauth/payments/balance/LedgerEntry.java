package io.github.dev_abdulhay.telegramauth.payments.balance;

import java.time.OffsetDateTime;

/** One applied balance change; {@code delta} is negative for debits. */
public record LedgerEntry(String refKey, BalanceAccountKey account, long delta, String reason,
                          OffsetDateTime createdAt) {
}
