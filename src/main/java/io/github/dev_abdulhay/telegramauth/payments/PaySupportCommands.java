package io.github.dev_abdulhay.telegramauth.payments;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;

/**
 * Registers the {@code /paysupport} and {@code /terms} commands Telegram requires of any
 * bot that accepts payments. A command the host already registered is left untouched.
 */
final class PaySupportCommands {

    private PaySupportCommands() { }

    static void register(TelegramBotModule m, TelegramPaymentsProperties p) {
        add(m, "/paysupport", p.getSupport().getPaysupportText());
        add(m, "/terms", p.getSupport().getTermsText());
    }

    private static void add(TelegramBotModule m, String command, String text) {
        if (m.getCommands().containsKey(command)) {
            return;
        }
        m.command(command, update -> {
            JsonNode chat = update.path("message").path("chat");
            if (chat.hasNonNull("id")) {
                m.getBot().sendMessage(chat.get("id").asLong(), text);
            }
        });
    }
}
