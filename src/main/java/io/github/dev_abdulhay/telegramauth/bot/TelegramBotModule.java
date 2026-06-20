package io.github.dev_abdulhay.telegramauth.bot;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dev_abdulhay.telegramauth.api.TelegramAuthApproveHandler;
import io.github.dev_abdulhay.telegramauth.api.dto.AuthApproveResult;
import io.github.dev_abdulhay.telegramauth.service.AuthEventBus;
import io.github.dev_abdulhay.telegramauth.service.InMemoryAuthEventBus;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Per-user-type configuration object. The host builds one bean per type. It
 * owns that type's bot instance, its isolated event bus, and its command
 * registry. Routing prefix and table name are NOT here — they live on the host
 * subclass {@code @RequestMapping} / {@code @Table}.
 */
public final class TelegramBotModule {

    private final String botToken;
    private final String username;
    private final Duration sessionTtl;
    private final Duration pollingTimeout;
    private final Duration pollingInterval;
    private final TelegramAuthApproveHandler approveHandler;
    private final TelegramBot bot;
    private final AuthEventBus bus;

    private final Map<String, Consumer<JsonNode>> commands = new ConcurrentHashMap<>();
    private volatile Consumer<JsonNode> fallback;

    private TelegramBotModule(Builder b) {
        this.botToken = b.botToken;
        this.username = b.username;
        this.sessionTtl = b.sessionTtl;
        this.pollingTimeout = b.pollingTimeout;
        this.pollingInterval = b.pollingInterval;
        this.approveHandler = b.approveHandler;
        this.bot = (b.bot != null) ? b.bot : new TelegramBot(HttpClient.newHttpClient(), b.botToken);
        this.bus = (b.bus != null) ? b.bus : new InMemoryAuthEventBus();
    }

    public static Builder builder(String botToken, String username) {
        return new Builder(botToken, username);
    }

    /** Register or replace a command handler (e.g. {@code "/start"}). */
    public void command(String command, Consumer<JsonNode> handler) {
        commands.put(command, handler);
    }

    /** Handler for updates with no matching command (callback_query, contact, plain text). */
    public void fallback(Consumer<JsonNode> handler) {
        this.fallback = handler;
    }

    public String getBotToken() { return botToken; }
    public String getUsername() { return username; }
    public Duration getSessionTtl() { return sessionTtl; }
    public Duration getPollingTimeout() { return pollingTimeout; }
    public Duration getPollingInterval() { return pollingInterval; }
    public TelegramAuthApproveHandler getApproveHandler() { return approveHandler; }
    public TelegramBot getBot() { return bot; }
    public AuthEventBus getBus() { return bus; }
    public Map<String, Consumer<JsonNode>> getCommands() { return commands; }
    public Consumer<JsonNode> getFallback() { return fallback; }

    public static final class Builder {
        private final String botToken;
        private final String username;
        private Duration sessionTtl = Duration.ofMinutes(3);
        private Duration pollingTimeout = Duration.ofSeconds(30);
        private Duration pollingInterval = Duration.ofSeconds(1);
        private TelegramAuthApproveHandler approveHandler = (info, ctx) -> new AuthApproveResult(Map.of());
        private TelegramBot bot;
        private AuthEventBus bus;

        private Builder(String botToken, String username) {
            this.botToken = botToken;
            this.username = username;
        }

        public Builder sessionTtl(Duration v) { this.sessionTtl = v; return this; }
        public Builder pollingTimeout(Duration v) { this.pollingTimeout = v; return this; }
        public Builder pollingInterval(Duration v) { this.pollingInterval = v; return this; }
        public Builder approveHandler(TelegramAuthApproveHandler v) { this.approveHandler = v; return this; }
        public Builder bot(TelegramBot v) { this.bot = v; return this; }
        public Builder eventBus(AuthEventBus v) { this.bus = v; return this; }

        public TelegramBotModule build() { return new TelegramBotModule(this); }
    }
}
