package io.github.abdulhaybro.telegramauth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Top-level configuration properties for the Telegram-auth starter.
 *
 * <p>Bound to the {@code telegram.auth} prefix in {@code application.yml}.
 * See {@code README.md} for a worked example.
 */
@ConfigurationProperties(prefix = "telegram.auth")
public class TelegramAuthProperties {

    /** Master switch; auto-config does nothing if {@code false}. */
    private boolean enabled = false;

    /** Base path for the REST API. */
    private String basePath = "/api/tg-auth";

    private Bot bot = new Bot();
    private Session session = new Session();
    private Transport transport = new Transport();
    private Db db = new Db();
    private I18n i18n = new I18n();
    private RateLimit rateLimit = new RateLimit();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath; }

    public Bot getBot() { return bot; }
    public void setBot(Bot bot) { this.bot = bot; }

    public Session getSession() { return session; }
    public void setSession(Session session) { this.session = session; }

    public Transport getTransport() { return transport; }
    public void setTransport(Transport transport) { this.transport = transport; }

    public Db getDb() { return db; }
    public void setDb(Db db) { this.db = db; }

    public I18n getI18n() { return i18n; }
    public void setI18n(I18n i18n) { this.i18n = i18n; }

    public RateLimit getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }

    public static class Bot {
        /** Bot token from @BotFather. Overridden by DB row if present. */
        private String token = "";
        /** Telegram username (no {@code @}) for building {@code t.me/<username>} deep-links. */
        private String username = "";
        private Duration pollingInterval = Duration.ofSeconds(1);
        private Duration pollingTimeout = Duration.ofSeconds(30);

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public Duration getPollingInterval() { return pollingInterval; }
        public void setPollingInterval(Duration pollingInterval) { this.pollingInterval = pollingInterval; }
        public Duration getPollingTimeout() { return pollingTimeout; }
        public void setPollingTimeout(Duration pollingTimeout) { this.pollingTimeout = pollingTimeout; }
    }

    public static class Session {
        private Duration ttl = Duration.ofSeconds(180);
        /** Spring cron expression for the expiration sweep job. */
        private String cleanupCron = "0 */5 * * * *";

        public Duration getTtl() { return ttl; }
        public void setTtl(Duration ttl) { this.ttl = ttl; }
        public String getCleanupCron() { return cleanupCron; }
        public void setCleanupCron(String cleanupCron) { this.cleanupCron = cleanupCron; }
    }

    public static class Transport {
        private Polling polling = new Polling();

        public Polling getPolling() { return polling; }
        public void setPolling(Polling polling) { this.polling = polling; }

        public static class Polling {
            private boolean enabled = true;
            private Duration maxWait = Duration.ofSeconds(30);

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public Duration getMaxWait() { return maxWait; }
            public void setMaxWait(Duration maxWait) { this.maxWait = maxWait; }
        }
    }

    public static class Db {
        private String schema = "public";
        private String tablePrefix = "tg_auth_";

        public String getSchema() { return schema; }
        public void setSchema(String schema) { this.schema = schema; }
        public String getTablePrefix() { return tablePrefix; }
        public void setTablePrefix(String tablePrefix) { this.tablePrefix = tablePrefix; }
    }

    public static class I18n {
        private String defaultLanguage = "uz";
        private List<String> supported = List.of("uz", "ru", "en");

        public String getDefaultLanguage() { return defaultLanguage; }
        public void setDefaultLanguage(String defaultLanguage) { this.defaultLanguage = defaultLanguage; }
        public List<String> getSupported() { return supported; }
        public void setSupported(List<String> supported) { this.supported = supported; }
    }

    public static class RateLimit {
        private boolean enabled = true;
        private int ipPerMinute = 5;
        private int ipPerHour = 30;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getIpPerMinute() { return ipPerMinute; }
        public void setIpPerMinute(int ipPerMinute) { this.ipPerMinute = ipPerMinute; }
        public int getIpPerHour() { return ipPerHour; }
        public void setIpPerHour(int ipPerHour) { this.ipPerHour = ipPerHour; }
    }
}
