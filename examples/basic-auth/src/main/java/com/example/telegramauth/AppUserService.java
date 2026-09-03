package com.example.telegramauth;

import io.github.dev_abdulhay.telegramauth.service.AbstractTelegramUserService;
import org.springframework.stereotype.Service;

@Service
public class AppUserService extends AbstractTelegramUserService<AppUser> {

    public AppUserService(AppUserRepository repo) {
        super(repo, AppUser::new);
    }
}
