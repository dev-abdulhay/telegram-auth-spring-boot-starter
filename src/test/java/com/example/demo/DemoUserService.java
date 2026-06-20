package com.example.demo;

import io.github.dev_abdulhay.telegramauth.service.AbstractTelegramUserService;
import org.springframework.stereotype.Service;

@Service
public class DemoUserService extends AbstractTelegramUserService<DemoUser> {
    public DemoUserService(DemoUserRepository repo) {
        super(repo, DemoUser::new);
    }
}
