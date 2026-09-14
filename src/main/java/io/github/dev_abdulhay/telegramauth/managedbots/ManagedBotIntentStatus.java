package io.github.dev_abdulhay.telegramauth.managedbots;

/** Lifecycle of a {@link ManagedBotIntent}, from creation to either a linked bot or a dead end. */
public enum ManagedBotIntentStatus { OPEN, CLAIMED, COMPLETED, EXPIRED, CANCELLED }
