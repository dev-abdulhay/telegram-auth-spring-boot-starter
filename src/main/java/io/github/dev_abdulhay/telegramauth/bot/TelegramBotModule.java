package io.github.dev_abdulhay.telegramauth.bot;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dev_abdulhay.telegramauth.api.TelegramAuthApproveHandler;
import io.github.dev_abdulhay.telegramauth.api.dto.AuthApproveResult;
import io.github.dev_abdulhay.telegramauth.security.ConfirmCode;
import io.github.dev_abdulhay.telegramauth.security.ConfirmCodeGenerator;
import io.github.dev_abdulhay.telegramauth.service.AuthEventBus;
import io.github.dev_abdulhay.telegramauth.service.InMemoryAuthEventBus;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collections;
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
    private final Duration sessionRetention;
    private final int maxPendingPerIp;
    private final boolean trustProxyHeaders;
    private final int trustedProxyHops;
    private final TelegramAuthApproveHandler approveHandler;
    private final TelegramBot bot;
    private final AuthEventBus bus;
    private final ConfirmCodeGenerator confirmCodeGenerator;

    private final Map<String, Consumer<JsonNode>> commands = new ConcurrentHashMap<>();
    private volatile Consumer<JsonNode> fallback;
    private volatile Consumer<JsonNode> callbackHandler;
    private volatile Consumer<JsonNode> contactHandler;
    private volatile Consumer<JsonNode> textHandler;

    private TelegramBotModule(Builder b) {
        this.botToken = b.botToken;
        this.username = b.username;
        this.sessionTtl = b.sessionTtl;
        this.pollingTimeout = b.pollingTimeout;
        this.pollingInterval = b.pollingInterval;
        this.sessionRetention = b.sessionRetention;
        this.maxPendingPerIp = b.maxPendingPerIp;
        this.trustProxyHeaders = b.trustProxyHeaders;
        this.trustedProxyHops = Math.max(1, b.trustedProxyHops);
        this.approveHandler = b.approveHandler;
        this.bot = (b.bot != null) ? b.bot : new TelegramBot(HttpClient.newHttpClient(), b.botToken);
        this.bus = (b.bus != null) ? b.bus : new InMemoryAuthEventBus();
        this.confirmCodeGenerator =
                (b.confirmCodeGenerator != null) ? b.confirmCodeGenerator : new ConfirmCode();
    }

    public static Builder builder(String botToken, String username) {
        return new Builder(botToken, username);
    }

    /** Register or replace a command handler (e.g. {@code "/start"}). */
    public void command(String command, Consumer<JsonNode> handler) {
        commands.put(command, handler);
    }

    /** Handler for updates with no matching dedicated route (plain text, unknown commands). */
    public void fallback(Consumer<JsonNode> handler) {
        this.fallback = handler;
    }

    /**
     * Handler for {@code callback_query} updates (inline-button presses).
     *
     * <p>Single-slot, and registering twice throws instead of silently replacing:
     * {@code DefaultAuthFlow} claims this slot when {@code requireApproval} is on,
     * and a host overwriting it would disable login approval with no visible
     * error. Hosts keep their own inline keyboards by registering a
     * {@link #fallback(Consumer)} — the flow forwards every callback outside its
     * own {@code tgauth:} namespace there.
     *
     * @throws IllegalStateException if a different handler is already registered
     */
    public void onCallbackQuery(Consumer<JsonNode> handler) {
        this.callbackHandler = claimSlot("callback_query", this.callbackHandler, handler);
    }

    /**
     * Handler for updates whose message carries a {@code contact} (shared phone
     * number). Single-slot with the same replace-guard as
     * {@link #onCallbackQuery(Consumer)}; contacts with no login in progress are
     * forwarded to the {@link #fallback(Consumer)}.
     *
     * @throws IllegalStateException if a different handler is already registered
     */
    public void onContact(Consumer<JsonNode> handler) {
        this.contactHandler = claimSlot("contact", this.contactHandler, handler);
    }

    /**
     * Handler for message updates carrying plain {@code text} that matched no
     * registered command. Single-slot with the same replace-guard as
     * {@link #onCallbackQuery(Consumer)}: {@code DefaultAuthFlow} claims it when
     * {@code codeConfirmation} is {@code TYPED}.
     *
     * <p>It also receives <b>unregistered {@code /commands}</b> — once the command
     * registry misses, the dispatcher cannot tell them from ordinary text. A
     * handler that does not own such an update should forward it to the
     * {@link #fallback(Consumer)}.
     *
     * @throws IllegalStateException if a different handler is already registered
     */
    public void onText(Consumer<JsonNode> handler) {
        this.textHandler = claimSlot("text", this.textHandler, handler);
    }

    private static Consumer<JsonNode> claimSlot(String slot, Consumer<JsonNode> current, Consumer<JsonNode> handler) {
        if (current != null && current != handler) {
            throw new IllegalStateException("a " + slot + " handler is already registered on this module; "
                    + "route additional updates through fallback(...) instead of replacing it");
        }
        return handler;
    }

    public String getBotToken() { return botToken; }
    public String getUsername() { return username; }
    public Duration getSessionTtl() { return sessionTtl; }
    public Duration getPollingTimeout() { return pollingTimeout; }
    public Duration getPollingInterval() { return pollingInterval; }
    public Duration getSessionRetention() { return sessionRetention; }
    public int getMaxPendingPerIp() { return maxPendingPerIp; }
    public boolean isTrustProxyHeaders() { return trustProxyHeaders; }
    public int getTrustedProxyHops() { return trustedProxyHops; }
    public TelegramAuthApproveHandler getApproveHandler() { return approveHandler; }
    public TelegramBot getBot() { return bot; }
    public AuthEventBus getBus() { return bus; }
    public ConfirmCodeGenerator getConfirmCodeGenerator() { return confirmCodeGenerator; }
    public Map<String, Consumer<JsonNode>> getCommands() { return Collections.unmodifiableMap(commands); }
    public Consumer<JsonNode> getFallback() { return fallback; }
    public Consumer<JsonNode> getCallbackHandler() { return callbackHandler; }
    public Consumer<JsonNode> getContactHandler() { return contactHandler; }
    public Consumer<JsonNode> getTextHandler() { return textHandler; }

    public static final class Builder {
        private final String botToken;
        private final String username;
        private Duration sessionTtl = Duration.ofMinutes(5);
        private Duration pollingTimeout = Duration.ofSeconds(30);
        private Duration pollingInterval = Duration.ofSeconds(1);
        private Duration sessionRetention = Duration.ofDays(1);
        private int maxPendingPerIp = 50;
        private boolean trustProxyHeaders = false;
        private int trustedProxyHops = 1;
        private TelegramAuthApproveHandler approveHandler = (info, ctx) -> new AuthApproveResult(Map.of());
        private TelegramBot bot;
        private AuthEventBus bus;
        private ConfirmCodeGenerator confirmCodeGenerator;

        private Builder(String botToken, String username) {
            this.botToken = botToken;
            this.username = username;
        }

        /**
         * How long a login link stays usable. Default 5 minutes — the contact and
         * confirmation-code steps both happen inside this window.
         */
        public Builder sessionTtl(Duration v) { this.sessionTtl = v; return this; }
        public Builder pollingTimeout(Duration v) { this.pollingTimeout = v; return this; }
        public Builder pollingInterval(Duration v) { this.pollingInterval = v; return this; }
        /** How long terminal (approved/rejected/expired) sessions are kept before the sweeper deletes them. {@code Duration.ZERO} disables deletion. */
        public Builder sessionRetention(Duration v) { this.sessionRetention = v; return this; }
        /**
         * Max PENDING sessions per client IP before session creation is throttled with 429.
         * {@code 0} disables the limit. Best-effort: count and insert are not atomic, so a
         * simultaneous burst can slip a few rows past the limit — it brakes floods, it is not
         * an exact quota.
         *
         * <p>The default (50) leaves room for many users sharing one address —
         * office NAT, mobile carrier CGNAT, a CDN egress IP. Lowering it makes a
         * shared address easier to lock out for the whole {@code sessionTtl}
         * window, so tune it against how your users actually reach the app.
         */
        public Builder maxPendingPerIp(int v) { this.maxPendingPerIp = v; return this; }
        /** Trust {@code X-Forwarded-For} when resolving the client IP. Enable only behind a proxy you control. */
        public Builder trustProxyHeaders(boolean v) { this.trustProxyHeaders = v; return this; }
        /**
         * How many trusted proxies sit between the client and this app — 1 for a
         * single reverse proxy, 2 for CDN + reverse proxy, and so on. Only read
         * when {@link #trustProxyHeaders(boolean)} is on.
         *
         * <p>The client IP is taken this many entries from the right of
         * {@code X-Forwarded-For}, because each trusted hop appends the peer it
         * received from and everything further left came from the client and can
         * be forged. Setting this too low reads a forged entry; setting it too
         * high reads your own proxy's address and collapses every user onto one
         * IP, which the per-IP limit would then throttle as a flood. Values below
         * 1 are clamped to 1.
         */
        public Builder trustedProxyHops(int v) { this.trustedProxyHops = v; return this; }
        public Builder approveHandler(TelegramAuthApproveHandler v) { this.approveHandler = v; return this; }
        public Builder bot(TelegramBot v) { this.bot = v; return this; }
        public Builder eventBus(AuthEventBus v) { this.bus = v; return this; }
        /**
         * Replaces the default two-digit confirmation-code scheme. The implementation
         * must be a pure function of the token hash — see {@link ConfirmCodeGenerator}.
         */
        public Builder confirmCodeGenerator(ConfirmCodeGenerator v) { this.confirmCodeGenerator = v; return this; }

        public TelegramBotModule build() { return new TelegramBotModule(this); }
    }
}
