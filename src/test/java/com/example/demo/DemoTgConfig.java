package com.example.demo;

import io.github.dev_abdulhay.telegramauth.api.dto.AuthApproveResult;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBot;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.flow.DefaultAuthFlow;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.util.Map;

@Configuration
public class DemoTgConfig {

    @Bean
    TelegramBotModule demoModule() {
        // Custom bot avoids real network during tests.
        TelegramBot fakeBot = new TelegramBot(HttpClient.newHttpClient(), "TEST") {
            @Override public void sendMessage(long chatId, String text) { }
            @Override public String getUpdates(long offset, int timeoutSeconds) throws Exception {
                Thread.sleep(300); // interruptible — lets stopAll() break the loop cleanly
                return "{\"ok\":true,\"result\":[]}";
            }
        };
        return TelegramBotModule.builder("TEST", "demo_bot")
                .bot(fakeBot)
                .approveHandler((info, ctx) -> new AuthApproveResult(Map.of("tgId", info.telegramId())))
                .build();
    }

    // DemoUserService is @Service — component-scanned from DemoApp @ComponentScan, no @Bean needed.

    @Bean
    DemoSessionService demoSessionService(DemoSessionRepository repo, TokenGenerator tg, TelegramBotModule module) {
        return new DemoSessionService(repo, tg, module);
    }

    @Bean
    DefaultAuthFlow<DemoUser, DemoSession> demoFlow(DemoUserService us, DemoSessionService ss, TelegramBotModule module) {
        return new DefaultAuthFlow<>(us, ss, module);
    }
}
