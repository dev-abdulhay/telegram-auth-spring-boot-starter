package io.github.dev_abdulhay.telegramauth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Global, type-agnostic starter properties. Per-type settings (bot token,
 * session TTL, command registry) live in code via {@code TelegramBotModule}.
 */
@ConfigurationProperties(prefix = "telegram.auth")
public class TelegramAuthProperties {

    /** Master switch; auto-config stays inert if {@code false}. */
    private boolean enabled = false;

    /** Spring cron for the per-module expired-session sweep. */
    private String cleanupCron = "0 */5 * * * *";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getCleanupCron() { return cleanupCron; }
    public void setCleanupCron(String cleanupCron) { this.cleanupCron = cleanupCron; }
}
