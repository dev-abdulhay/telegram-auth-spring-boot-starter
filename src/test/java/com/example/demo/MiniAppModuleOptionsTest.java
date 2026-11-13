package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dev_abdulhay.telegramauth.api.TelegramAuthApproveHandler;
import io.github.dev_abdulhay.telegramauth.api.dto.AuthApproveResult;
import io.github.dev_abdulhay.telegramauth.api.dto.AuthChannel;
import io.github.dev_abdulhay.telegramauth.api.dto.AuthContext;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.miniapp.MiniAppOptions;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MiniAppModuleOptionsTest {

    @Test
    void defaultModuleHasNoMiniApp() {
        TelegramBotModule m = TelegramBotModule.builder("123:ABC", "demo_bot").build();
        assertTrue(m.getMiniAppOptions().isEmpty());
    }

    @Test
    void miniAppHandlerFallsBackToApproveHandler() {
        TelegramAuthApproveHandler h = (info, ctx) -> new AuthApproveResult(Map.of());
        TelegramBotModule m = TelegramBotModule.builder("123:ABC", "demo_bot")
                .approveHandler(h).miniApp(MiniAppOptions.defaults()).build();
        assertTrue(m.getMiniAppOptions().isPresent());
        assertSame(h, m.getMiniAppHandler());
    }

    @Test
    void explicitMiniAppHandlerWins() {
        TelegramAuthApproveHandler h = (info, ctx) -> new AuthApproveResult(Map.of());
        TelegramAuthApproveHandler mh = (info, ctx) -> new AuthApproveResult(Map.of());
        TelegramBotModule m = TelegramBotModule.builder("123:ABC", "demo_bot")
                .approveHandler(h).miniApp(MiniAppOptions.defaults()).miniAppHandler(mh).build();
        assertSame(mh, m.getMiniAppHandler());
    }

    @Test
    void legacyAuthContextIsBotLogin() {
        AuthContext c = new AuthContext("ip", "ua");
        assertEquals(AuthChannel.BOT_LOGIN, c.getChannel());
        assertNull(c.getStartParam());
    }
}
