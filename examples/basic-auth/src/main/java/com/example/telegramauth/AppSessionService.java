package com.example.telegramauth;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import io.github.dev_abdulhay.telegramauth.service.AbstractSessionService;

public class AppSessionService extends AbstractSessionService<AppUser, AppSession> {

    public AppSessionService(AppSessionRepository repo, TokenGenerator tokenGenerator, TelegramBotModule module) {
        super(repo, AppSession::new, tokenGenerator, module);
    }
}
