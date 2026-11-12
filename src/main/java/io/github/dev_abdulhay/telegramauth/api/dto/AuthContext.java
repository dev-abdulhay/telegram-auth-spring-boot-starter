package io.github.dev_abdulhay.telegramauth.api.dto;

import java.util.HashMap;
import java.util.Map;

/**
 * Mutable context object passed into the host approve handler. Carries the
 * request-side details captured at session creation — IP, user agent and the
 * host's own {@code hostRef} — plus a free-form attribute map a host can decorate
 * before its handler reads it.
 */
public final class AuthContext {

    private final String ipAddress;
    private final String userAgent;
    private final String hostRef;
    private final AuthChannel channel;
    private final String startParam;
    private final Map<String, Object> attributes = new HashMap<>();

    public AuthContext(String ipAddress, String userAgent) {
        this(ipAddress, userAgent, null);
    }

    public AuthContext(String ipAddress, String userAgent, String hostRef) {
        this(ipAddress, userAgent, hostRef, AuthChannel.BOT_LOGIN, null);
    }

    public AuthContext(String ipAddress, String userAgent, String hostRef,
                       AuthChannel channel, String startParam) {
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.hostRef = hostRef;
        this.channel = channel == null ? AuthChannel.BOT_LOGIN : channel;
        this.startParam = startParam;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getHostRef() {
        return hostRef;
    }

    public AuthChannel getChannel() {
        return channel;
    }

    public String getStartParam() {
        return startParam;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public AuthContext setAttribute(String key, Object value) {
        attributes.put(key, value);
        return this;
    }
}
