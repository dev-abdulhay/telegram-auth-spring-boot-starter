package io.github.dev_abdulhay.telegramauth.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Poll result. {@code confirmCode} is set only on an {@code AWAITING_CODE}
 * response and is omitted from the JSON otherwise — it is the browser-side half
 * of the number-matching check, not part of the host's approve payload.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WaitResponse(String status, Map<String, Object> payload, Integer confirmCode) {

    public WaitResponse(String status, Map<String, Object> payload) {
        this(status, payload, null);
    }
}
