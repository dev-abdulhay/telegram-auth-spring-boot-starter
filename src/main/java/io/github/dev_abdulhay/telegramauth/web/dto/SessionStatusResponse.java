package io.github.dev_abdulhay.telegramauth.web.dto;

import java.time.OffsetDateTime;

public record SessionStatusResponse(String status, OffsetDateTime expiresAt) {
}
