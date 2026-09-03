package com.example.telegramauth;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.web.AbstractTelegramAuthController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Four endpoints — POST /session, GET /session/{token}/poll, GET
 * /session/{token}/status, DELETE /session/{token} — come from
 * {@link AbstractTelegramAuthController}; nothing to override for this example.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController extends AbstractTelegramAuthController<AppUser, AppSession> {

    public AuthController(AppSessionService sessionService, TelegramBotModule module) {
        super(sessionService, module);
    }
}
