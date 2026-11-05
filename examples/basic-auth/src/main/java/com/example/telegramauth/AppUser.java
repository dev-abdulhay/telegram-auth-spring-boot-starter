package com.example.telegramauth;

import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_user")
public class AppUser extends BaseTelegramUser {
}
