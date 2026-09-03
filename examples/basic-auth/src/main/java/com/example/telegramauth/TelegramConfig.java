package com.example.telegramauth;

import io.github.dev_abdulhay.telegramauth.api.dto.AuthApproveResult;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.flow.DefaultAuthFlow;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class TelegramConfig {

    @Bean
    public TelegramBotModule telegramBotModule(@Value("${telegram.bot.token}") String botToken,
                                                @Value("${telegram.bot.username}") String botUsername) {
        return TelegramBotModule.builder(botToken, botUsername)
                // NOTE: a real application mints its own JWT / session cookie here and
                // returns it as the payload; this example just echoes the Telegram id.
                .approveHandler((info, ctx) -> new AuthApproveResult(Map.of("userId", info.telegramId())))
                .build();
    }

    @Bean
    public AppSessionService appSessionService(AppSessionRepository repo,
                                               TokenGenerator tokenGenerator,
                                               TelegramBotModule module) {
        return new AppSessionService(repo, tokenGenerator, module);
    }

    @Bean
    public DefaultAuthFlow<AppUser, AppSession> defaultAuthFlow(AppUserService userService,
                                                                 AppSessionService sessionService,
                                                                 TelegramBotModule module) {
        // Options.defaults(): requireContact=false, requireApproval=false,
        // codeConfirmation=BUTTON — the "one touch" flow (see README).
        return new DefaultAuthFlow<>(userService, sessionService, module);
    }
}
