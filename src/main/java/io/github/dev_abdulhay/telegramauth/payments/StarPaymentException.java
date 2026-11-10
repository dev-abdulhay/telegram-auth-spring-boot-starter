package io.github.dev_abdulhay.telegramauth.payments;

/** A payment request the service refuses: unknown product, bad state, guard veto, unknown bot. */
public class StarPaymentException extends RuntimeException {

    public StarPaymentException(String message) {
        super(message);
    }
}
