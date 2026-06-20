package com.example.demo;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.web.AbstractTelegramAuthController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo-auth")
public class DemoAuthController extends AbstractTelegramAuthController<DemoUser, DemoSession> {
    public DemoAuthController(DemoSessionService service, TelegramBotModule module) {
        super(service, module);
    }
}
