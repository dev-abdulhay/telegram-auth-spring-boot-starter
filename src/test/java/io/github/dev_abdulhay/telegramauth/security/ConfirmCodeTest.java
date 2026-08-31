package io.github.dev_abdulhay.telegramauth.security;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmCodeTest {

    private static String hashStartingWith(String fourHexChars) {
        return fourHexChars + "0".repeat(60);
    }

    @Test
    void derivesTheCodeFromTheFirstFourHexCharsOfTheHash() {
        assertThat(ConfirmCode.of(hashStartingWith("002a"))).isEqualTo(42);   // 0x002a = 42
        assertThat(ConfirmCode.of(hashStartingWith("ffff"))).isEqualTo(35);   // 0xffff = 65535
        assertThat(ConfirmCode.of(hashStartingWith("0000"))).isEqualTo(0);
    }

    @Test
    void isDeterministicAndAlwaysTwoDigits() {
        TokenGenerator tg = new TokenGenerator();
        for (int i = 0; i < 500; i++) {
            String hash = tg.hash(tg.newToken());
            int code = ConfirmCode.of(hash);
            assertThat(code).isBetween(0, 99);
            assertThat(ConfirmCode.of(hash)).isEqualTo(code);
        }
    }

    @Test
    void moduleUsesTheDefaultGeneratorUnlessOverridden() {
        TelegramBotModule def = TelegramBotModule.builder("123:ABC", "demo_bot").build();
        assertThat(def.getConfirmCodeGenerator().codeFor(hashStartingWith("002a"))).isEqualTo(42);

        TelegramBotModule custom = TelegramBotModule.builder("123:ABC", "demo_bot")
                .confirmCodeGenerator(hash -> 7)
                .build();
        assertThat(custom.getConfirmCodeGenerator().codeFor(hashStartingWith("002a"))).isEqualTo(7);
    }
}
