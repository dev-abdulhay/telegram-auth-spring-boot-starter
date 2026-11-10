package com.example.demo;

import io.github.dev_abdulhay.telegramauth.payments.balance.BaseBalanceAccount;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "demo_balance_account")
public class DemoBalanceAccount extends BaseBalanceAccount {
}
