package com.example.demo;

import io.github.dev_abdulhay.telegramauth.payments.balance.BaseLedgerEntry;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "demo_ledger_entry",
       indexes = @Index(name = "ix_demo_ledger_entry_account", columnList = "account_key, created_at"))
public class DemoLedgerEntry extends BaseLedgerEntry {
}
