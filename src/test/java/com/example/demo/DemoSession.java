package com.example.demo;

import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "demo_tg_session")
public class DemoSession extends BaseAuthSession {
}
