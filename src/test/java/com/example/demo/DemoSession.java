package com.example.demo;

import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** Mirrors what the README tells hosts to write, index included. */
@Entity
@Table(name = "demo_tg_session",
        indexes = @Index(name = "ix_demo_session_ip_status", columnList = "ip_address,status"))
public class DemoSession extends BaseAuthSession {
}
