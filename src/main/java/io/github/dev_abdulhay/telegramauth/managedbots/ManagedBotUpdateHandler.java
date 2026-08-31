package io.github.dev_abdulhay.telegramauth.managedbots;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;

/**
 * Claims the module's {@code managed_bot} slot for a {@link ManagedBotService}.
 * Constructing this is all the wiring a host needs; the auto-configuration does
 * it when the feature is enabled.
 */
public class ManagedBotUpdateHandler {

    public ManagedBotUpdateHandler(TelegramBotModule module, ManagedBotService service) {
        module.onManagedBot(service::handleUpdate);
    }
}
