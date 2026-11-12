package com.example.demo;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.web.AbstractTelegramAuthController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 3-arg constructor and a module with {@code miniApp(...)}, but no {@code /miniapp} mapping of its own. */
@RestController
@RequestMapping("/api/not-opted-in-auth")
public class NotOptedInDemoAuthController extends AbstractTelegramAuthController<DemoUser, DemoSession> {
    public NotOptedInDemoAuthController(DemoSessionService service, TelegramBotModule module, DemoUserService users) {
        super(service, module, users);
    }
}
