package io.github.abdulhaybro.telegramauth.web.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record CreateSessionResponse(
        String token,
        String botDeepLink,
        OffsetDateTime expiresAt,
        List<String> transports
) {
}
