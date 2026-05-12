package io.github.abdulhaybro.telegramauth.api.dto;

import java.util.Map;

/**
 * What the host application returns from
 * {@link io.github.abdulhaybro.telegramauth.api.TelegramAuthApproveHandler#onApprove}.
 * <p>
 * The {@code payload} map is forwarded verbatim to the waiting client.
 */
public record AuthApproveResult(Map<String, Object> payload) {
}
