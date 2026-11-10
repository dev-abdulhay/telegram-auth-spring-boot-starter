package io.github.dev_abdulhay.telegramauth.payments;

/** A purchasable item priced in Telegram Stars. */
public record StarProduct(String code, String title, String description, int stars, long credits,
                          boolean subscription) {
}
