package com.example.demo;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import io.github.dev_abdulhay.telegramauth.service.AbstractSessionService;

public class DemoSessionService extends AbstractSessionService<DemoUser, DemoSession> {
    public DemoSessionService(DemoSessionRepository repo, TokenGenerator tg, TelegramBotModule module) {
        super(repo, DemoSession::new, tg, module);
    }
}
