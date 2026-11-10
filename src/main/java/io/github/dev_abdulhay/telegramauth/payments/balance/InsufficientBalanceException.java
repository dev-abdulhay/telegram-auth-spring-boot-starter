package io.github.dev_abdulhay.telegramauth.payments.balance;

/** A {@link BalanceLedger#debit} would take the balance below zero; nothing was recorded. */
public class InsufficientBalanceException extends RuntimeException {

    private final long required;
    private final long available;

    public InsufficientBalanceException(long required, long available) {
        super("Insufficient balance: required " + required + ", available " + available);
        this.required = required;
        this.available = available;
    }

    public long required() {
        return required;
    }

    public long available() {
        return available;
    }
}
