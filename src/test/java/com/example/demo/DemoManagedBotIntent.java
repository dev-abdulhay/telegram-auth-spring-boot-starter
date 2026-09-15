package com.example.demo;

import io.github.dev_abdulhay.telegramauth.managedbots.BaseManagedBotIntent;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "demo_managed_bot_intent",
       indexes = {
           @Index(name = "ix_demo_intent_owner_status", columnList = "owner_user_id, status"),
           @Index(name = "ix_demo_intent_status_expires", columnList = "status, expires_at")
       })
public class DemoManagedBotIntent extends BaseManagedBotIntent {
}
