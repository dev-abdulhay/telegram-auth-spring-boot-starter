package io.github.dev_abdulhay.telegramauth.web.dto;

import java.util.Map;

public record WaitResponse(String status, Map<String, Object> payload) {
}
