package com.example.demo;

import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "demo_tg_user")
public class DemoUser extends BaseTelegramUser {
}
