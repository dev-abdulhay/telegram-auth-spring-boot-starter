package com.example.demo;

import io.github.dev_abdulhay.telegramauth.payments.BaseStarOrder;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "demo_star_order",
       indexes = {
           @Index(name = "ix_demo_star_order_status_paid", columnList = "status, fulfilled_at"),
           @Index(name = "ix_demo_star_order_status_expires", columnList = "status, expires_at")
       })
public class DemoStarOrder extends BaseStarOrder {
}
