package io.github.dev_abdulhay.telegramauth.managedbots;

/** A user who has access to a restricted managed bot. */
public record ManagedBotUser(long userId, String username, String firstName) {}
