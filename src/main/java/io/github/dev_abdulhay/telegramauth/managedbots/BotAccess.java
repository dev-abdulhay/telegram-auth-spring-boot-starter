package io.github.dev_abdulhay.telegramauth.managedbots;

import java.util.List;

/**
 * A managed bot's access settings as Telegram reports them.
 *
 * <p>Note the asymmetry with {@code setAccessSettings}: reads return whole users,
 * writes take ids.
 *
 * @param addedUsers users allowed besides the owner; empty when access is open
 */
public record BotAccess(boolean restricted, List<ManagedBotUser> addedUsers) {}
