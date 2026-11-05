package io.github.dev_abdulhay.telegramauth.managedbots;

/** @param intent {@code null} only for {@link IntentClaim#UNKNOWN} */
public record IntentClaimResult(IntentClaim outcome, ManagedBotIntent intent) { }
