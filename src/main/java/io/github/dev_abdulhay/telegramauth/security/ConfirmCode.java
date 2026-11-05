package io.github.dev_abdulhay.telegramauth.security;

/**
 * Default {@link ConfirmCodeGenerator}: the first two bytes of the token hash,
 * reduced to two decimal digits. Deterministic, so it needs no storage and no
 * schema change — both sides recompute it from the row they already have.
 */
public final class ConfirmCode implements ConfirmCodeGenerator {

    @Override
    public int codeFor(String tokenHash) {
        return of(tokenHash);
    }

    /** @return a value in {@code 0..99} */
    public static int of(String tokenHash) {
        return Integer.parseInt(tokenHash.substring(0, 4), 16) % 100;
    }
}
