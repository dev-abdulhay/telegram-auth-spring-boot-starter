package com.example.demo;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.web.AbstractTelegramAuthController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/demo-auth")
public class DemoAuthController extends AbstractTelegramAuthController<DemoUser, DemoSession> {
    public DemoAuthController(DemoSessionService service, TelegramBotModule module, DemoUserService users) {
        super(service, module, users);
    }

    @PostMapping("/miniapp")
    public ResponseEntity<?> miniApp(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        return miniAppLogin(body, req);
    }
}
