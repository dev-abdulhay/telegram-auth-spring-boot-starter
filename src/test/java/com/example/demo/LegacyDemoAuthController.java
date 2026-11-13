package com.example.demo;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.web.AbstractTelegramAuthController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A host still on the pre-Mini-App two-argument constructor: {@code /miniapp} must stay off for it. */
@RestController
@RequestMapping("/api/legacy-auth")
public class LegacyDemoAuthController extends AbstractTelegramAuthController<DemoUser, DemoSession> {
    public LegacyDemoAuthController(DemoSessionService service, TelegramBotModule module) {
        super(service, module);
    }
}
