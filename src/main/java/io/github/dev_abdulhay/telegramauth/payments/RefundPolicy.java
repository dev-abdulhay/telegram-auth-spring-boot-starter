package io.github.dev_abdulhay.telegramauth.payments;

/** What a refund of a top-up does when the credited balance was already spent. */
public enum RefundPolicy { REQUIRE_BALANCE, ALLOW_NEGATIVE }
