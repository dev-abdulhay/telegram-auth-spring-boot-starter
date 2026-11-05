package io.github.dev_abdulhay.telegramauth.whitelabel;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * White-label runtime settings. A separate namespace from {@code telegram.auth}
 * and {@code telegram.managed-bots}: the runtime is opt-in on top of both.
 */
@ConfigurationProperties(prefix = "telegram.white-label")
public class TelegramWhiteLabelProperties {

    /** Opt-in switch for the whole runtime. */
    private boolean enabled = false;

    /** Start every stored tenant bot when the application becomes ready. */
    private boolean restoreOnStartup = true;

    /**
     * How long a tenant bot may fail to poll continuously before it is stopped and
     * deregistered — usually a token the owner revoked in BotFather. Measured in
     * time rather than attempts so a brief network outage never kills a bot.
     */
    private Duration pollFailureBudget = Duration.ofMinutes(5);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isRestoreOnStartup() { return restoreOnStartup; }
    public void setRestoreOnStartup(boolean restoreOnStartup) { this.restoreOnStartup = restoreOnStartup; }
    public Duration getPollFailureBudget() { return pollFailureBudget; }
    public void setPollFailureBudget(Duration pollFailureBudget) { this.pollFailureBudget = pollFailureBudget; }
}
