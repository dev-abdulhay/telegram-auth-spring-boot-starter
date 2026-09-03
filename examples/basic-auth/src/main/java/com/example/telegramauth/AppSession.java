package com.example.telegramauth;

import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Index follows {@link BaseAuthSession}'s javadoc: every {@code POST /session}
 * runs a per-IP pending count, so without an index on {@code ip_address} that
 * lookup is a full table scan on the hottest endpoint in the library.
 * ({@code token_hash} is already indexed by its unique constraint.)
 */
@Entity
@Table(name = "app_auth_session",
        indexes = @Index(name = "ix_app_session_ip_status", columnList = "ip_address,status"))
public class AppSession extends BaseAuthSession {
}
