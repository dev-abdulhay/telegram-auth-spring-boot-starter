package io.github.dev_abdulhay.telegramauth.payments.balance;

/** Argument checks shared by the ledger implementations. */
final class LedgerArgs {

    static final int MAX_REF_KEY = 160;
    static final int MAX_REASON = 64;

    private LedgerArgs() {
    }

    static void check(BalanceAccountKey account, long amount, String refKey, String reason) {
        if (account == null) {
            throw new IllegalArgumentException("account is required");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        if (refKey == null || refKey.isBlank() || refKey.length() > MAX_REF_KEY) {
            throw new IllegalArgumentException("refKey must be 1-" + MAX_REF_KEY + " non-blank chars");
        }
        if (reason != null && reason.length() > MAX_REASON) {
            throw new IllegalArgumentException("reason must be at most " + MAX_REASON + " chars");
        }
    }

    static void checkPage(int page, int size) {
        if (page < 0 || size <= 0) {
            throw new IllegalArgumentException("page must be >= 0 and size > 0");
        }
    }
}
