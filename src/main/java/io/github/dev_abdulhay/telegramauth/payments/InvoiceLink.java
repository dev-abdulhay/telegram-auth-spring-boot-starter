package io.github.dev_abdulhay.telegramauth.payments;

/** An invoice link for {@code WebApp.openInvoice(url)} and the order it pays. */
public record InvoiceLink(String url, StarOrder order) {
}
