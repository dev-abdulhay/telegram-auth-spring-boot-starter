package io.github.dev_abdulhay.telegramauth.api.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthContextTest {

    @Test
    void nullChannelDefaultsToBotLogin() {
        AuthContext ctx = new AuthContext("1.1.1.1", "ua", null, null, "p");

        assertThat(ctx.getChannel()).isEqualTo(AuthChannel.BOT_LOGIN);
        assertThat(ctx.getStartParam()).isEqualTo("p");
    }

    @Test
    void explicitChannelIsKept() {
        assertThat(new AuthContext("1.1.1.1", "ua", null, AuthChannel.MINI_APP, null).getChannel())
                .isEqualTo(AuthChannel.MINI_APP);
    }
}
