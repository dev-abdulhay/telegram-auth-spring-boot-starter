package com.example.demo;

import io.github.dev_abdulhay.telegramauth.managedbots.BaseManagedBot;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "demo_managed_bot",
       indexes = @Index(name = "ix_demo_managed_bot_owner", columnList = "owner_user_id"))
public class DemoManagedBot extends BaseManagedBot {
}
