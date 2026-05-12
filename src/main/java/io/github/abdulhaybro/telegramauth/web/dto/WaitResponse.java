package io.github.abdulhaybro.telegramauth.web.dto;

import java.util.Map;

public record WaitResponse(String status, Map<String, Object> payload) {
}
