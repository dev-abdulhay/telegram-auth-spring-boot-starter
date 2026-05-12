package io.github.abdulhaybro.telegramauth.web.dto;

import java.time.OffsetDateTime;

public record SessionStatusResponse(String status, OffsetDateTime expiresAt) {
}
