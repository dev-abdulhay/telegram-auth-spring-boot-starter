package io.github.abdulhaybro.telegramauth.api.dto;

import java.util.HashMap;
import java.util.Map;

/**
 * Mutable context object passed into the host approve handler. Carries the
 * request-side details captured at session creation and a free-form attribute
 * map that {@code AuthContextEnricher} implementations can decorate.
 */
public final class AuthContext {

    private final String ipAddress;
    private final String userAgent;
    private final Map<String, Object> attributes = new HashMap<>();

    public AuthContext(String ipAddress, String userAgent) {
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public AuthContext setAttribute(String key, Object value) {
        attributes.put(key, value);
        return this;
    }
}
