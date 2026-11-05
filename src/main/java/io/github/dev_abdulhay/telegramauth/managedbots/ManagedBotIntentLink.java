package io.github.dev_abdulhay.telegramauth.managedbots;

import java.time.OffsetDateTime;

/**
 * What the host hands to the user: the deep link that claims {@code intentId}.
 * The link goes to the <em>manager</em> bot, not to {@code /newbot} — the bot
 * creation button comes later, from the manager bot's own reply.
 */
public record ManagedBotIntentLink(String intentId, String url, OffsetDateTime expiresAt) { }
