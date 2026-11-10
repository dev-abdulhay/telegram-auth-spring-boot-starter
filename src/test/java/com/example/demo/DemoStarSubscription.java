package com.example.demo;

import io.github.dev_abdulhay.telegramauth.payments.BaseStarSubscription;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "demo_star_subscription",
       indexes = {
           @Index(name = "ix_demo_star_sub_user", columnList = "user_id"),
           @Index(name = "ix_demo_star_sub_status_expires", columnList = "status, expires_at")
       })
public class DemoStarSubscription extends BaseStarSubscription {
}
