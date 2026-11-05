package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunningBotTest {

    @Test
    void aRunningBotRefusesMissingParts() {
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "tenant_bot").build();

        assertThatThrownBy(() -> new RunningBot<>(null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("module");
        assertThatThrownBy(() -> new RunningBot<>(module, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sessionService");
    }
}
