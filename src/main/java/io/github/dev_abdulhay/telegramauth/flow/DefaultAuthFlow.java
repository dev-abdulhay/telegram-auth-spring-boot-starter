package io.github.dev_abdulhay.telegramauth.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.flow.FlowMessages.Key;
import io.github.dev_abdulhay.telegramauth.service.AbstractSessionService;
import io.github.dev_abdulhay.telegramauth.service.AbstractTelegramUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Default reg/auth flow. Self-registers its handlers into the module on
 * construction, so declaring this bean is enough to get a working login.
 * Subclass and {@code @Override} the {@code on*} methods to customise.
 *
 * <p>Behaviour is controlled by {@link Options} (both flags default to
 * {@code false}, which keeps the original auto-register + auto-approve flow):
 * <ul>
 *   <li>{@code requireContact} — asks for the user's phone via Telegram
 *       contact-share when the user is unknown or has no phone; {@code /skip}
 *       continues without one (soft requirement).</li>
 *   <li>{@code requireApproval} — replaces auto-approve with an inline
 *       ✅ / ❌ confirmation that shows the session's IP and device.
 *       <b>Strongly recommended for production</b>: without it, anyone who is
 *       tricked into tapping a login link approves the sender's browser session
 *       (phishing).</li>
 * </ul>
 *
 * <p><b>Private chats only.</b> Every handler ignores updates whose
 * {@code chat.id} differs from {@code from.id} (groups, channels, supergroups):
 * there the chat id is not a user id, and an inline confirmation posted to a
 * group could be tapped by a bystander, approving someone else's browser
 * session under their account.
 *
 * <p>With {@code requireApproval} the user record is created (or refreshed)
 * only when ✅ is pressed — a rejected login leaves no account behind.
 *
 * <p>Users with status {@code BLOCKED} are always denied and never re-activated
 * by this flow. Updates this flow does not own (foreign {@code callback_data},
 * a contact with no login in progress) are forwarded to the module fallback, so
 * the host can keep its own inline keyboards. Bot texts are localized
 * (uz/ru/en) via {@link FlowMessages}; override {@link #msg(FlowMessages.Key,
 * String)} to customise wording.
 */
public class DefaultAuthFlow<U extends BaseTelegramUser, S extends BaseAuthSession> {

    private static final Logger log = LoggerFactory.getLogger(DefaultAuthFlow.class);
    private static final String START = "/start ";
    private static final String CALLBACK_PREFIX = "tgauth:";
    private static final String REMOVE_KEYBOARD = "{\"remove_keyboard\":true}";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Telegram's hard limit for {@code callback_data}. */
    private static final int CALLBACK_DATA_MAX_BYTES = 64;
    /** User-agent is truncated to this many chars before it goes into the confirm prompt. */
    private static final int UA_PREVIEW_CHARS = 120;
    /**
     * Hard ceiling on parked logins. Entries normally age out within one
     * {@code sessionTtl}, but a flood of {@code /start}s from abandoned logins
     * could outrun that; past this size the oldest entries are dropped so the
     * map cannot grow without bound.
     */
    private static final int MAX_PENDING_LOGINS = 10_000;

    /** Immutable opt-in flags for the flow. */
    public static final class Options {
        private final boolean requireContact;
        private final boolean requireApproval;

        private Options(Builder b) {
            this.requireContact = b.requireContact;
            this.requireApproval = b.requireApproval;
        }

        public boolean requireContact() { return requireContact; }
        public boolean requireApproval() { return requireApproval; }

        public static Builder builder() { return new Builder(); }
        public static Options defaults() { return new Builder().build(); }

        public static final class Builder {
            private boolean requireContact;
            private boolean requireApproval;

            public Builder requireContact(boolean v) { this.requireContact = v; return this; }
            public Builder requireApproval(boolean v) { this.requireApproval = v; return this; }
            public Options build() { return new Options(this); }
        }
    }

    /** In-flight login for one Telegram user: the token plus any phone collected on the way. */
    private record Pending(String rawToken, OffsetDateTime createdAt, String phone) {}

    protected final AbstractTelegramUserService<U> userService;
    protected final AbstractSessionService<U, S> sessionService;
    protected final TelegramBotModule module;
    protected final Options options;

    /**
     * telegram user id → in-flight login; correlates the contact and approval
     * steps.
     *
     * <p>JVM-local and not replicated. Losing it (restart, or a second instance
     * taking over polling) is degraded but not broken: {@code ✅}/{@code ❌} still
     * work because the token travels in the callback and the session lives in
     * the DB — only a phone shared moments earlier is forgotten, and the
     * previously stored one is kept. A pending {@code /skip} or contact-share,
     * however, has nothing to correlate against and the user starts over.
     * Override the {@code on*} methods with a shared store if a mid-flow
     * handover has to survive intact.
     */
    private final ConcurrentHashMap<Long, Pending> pendingLogins = new ConcurrentHashMap<>();

    public DefaultAuthFlow(AbstractTelegramUserService<U> userService,
                           AbstractSessionService<U, S> sessionService,
                           TelegramBotModule module) {
        this(userService, sessionService, module, Options.defaults());
    }

    public DefaultAuthFlow(AbstractTelegramUserService<U> userService,
                           AbstractSessionService<U, S> sessionService,
                           TelegramBotModule module,
                           Options options) {
        this.userService = userService;
        this.sessionService = sessionService;
        this.module = module;
        this.options = options;
        module.command("/start", this::onStart);
        if (options.requireContact()) {
            module.command("/skip", this::onSkip);
            module.onContact(this::onContact);
        }
        if (options.requireApproval()) {
            module.onCallbackQuery(this::onCallback);
        }
    }

    public void onStart(JsonNode update) {
        JsonNode message = update.path("message");
        JsonNode from = message.path("from");
        long userId = from.path("id").asLong();
        String lang = FlowMessages.resolveLang(from.path("language_code").asText(null));
        if (!isPrivateChat(message)) {
            log.debug("/start outside a private chat ignored");
            return;
        }
        String text = message.path("text").asText("");
        String rawToken = text.length() > START.length() ? text.substring(START.length()).trim() : "";

        purgeStalePending();

        if (rawToken.isEmpty() || validPendingSession(rawToken).isEmpty()) {
            module.getBot().sendMessage(userId, msg(Key.INVALID_LINK, lang));
            return;
        }

        U user = userService.findByTelegramId(userId).orElse(null);
        if (isBlocked(user)) {
            module.getBot().sendMessage(userId, msg(Key.ACCESS_DENIED, lang));
            return;
        }

        if (options.requireContact() && (user == null || isBlank(user.getPhone()))) {
            parkPending(userId, new Pending(rawToken, OffsetDateTime.now(), null));
            module.getBot().sendMessage(userId, msg(Key.ASK_CONTACT, lang), contactKeyboard(lang));
            return;
        }

        proceedAfterIdentity(userId, from, rawToken, null, lang);
    }

    /** Contact-share step: accepts only the sender's own contact (anti-spoofing). */
    public void onContact(JsonNode update) {
        JsonNode message = update.path("message");
        JsonNode from = message.path("from");
        long userId = from.path("id").asLong();
        String lang = FlowMessages.resolveLang(from.path("language_code").asText(null));
        if (!isPrivateChat(message)) {
            log.debug("contact outside a private chat ignored");
            return;
        }

        Pending pending = pendingLogins.get(userId);
        if (pending == null) {
            log.debug("contact with no login in progress, userId={} — passing to fallback", userId);
            delegateToFallback(update);
            return;
        }

        JsonNode contact = message.path("contact");
        if (contact.path("user_id").asLong() != userId) {
            module.getBot().sendMessage(userId, msg(Key.CONTACT_NOT_OWN, lang));
            return; // pending stays — user may retry with their own contact
        }

        if (validPendingSession(pending.rawToken()).isEmpty()) {
            pendingLogins.remove(userId);
            module.getBot().sendMessage(userId, msg(Key.INVALID_LINK, lang), REMOVE_KEYBOARD);
            return;
        }
        if (isBlocked(userService.findByTelegramId(userId).orElse(null))) {
            pendingLogins.remove(userId);
            module.getBot().sendMessage(userId, msg(Key.ACCESS_DENIED, lang), REMOVE_KEYBOARD);
            return;
        }

        String phone = contact.path("phone_number").asText("");
        if (phone.startsWith("+")) phone = phone.substring(1);

        module.getBot().sendMessage(userId, msg(Key.CONTACT_SAVED, lang), REMOVE_KEYBOARD);
        proceedAfterIdentity(userId, from, pending.rawToken(), phone.isEmpty() ? null : phone, lang);
    }

    /** {@code /skip} — continue the contact step without a phone number. */
    public void onSkip(JsonNode update) {
        JsonNode message = update.path("message");
        JsonNode from = message.path("from");
        long userId = from.path("id").asLong();
        String lang = FlowMessages.resolveLang(from.path("language_code").asText(null));
        if (!isPrivateChat(message)) {
            log.debug("/skip outside a private chat ignored");
            return;
        }

        Pending pending = pendingLogins.get(userId);
        if (pending == null) {
            log.debug("/skip with no login in progress, userId={}", userId);
            return;
        }
        if (validPendingSession(pending.rawToken()).isEmpty()) {
            pendingLogins.remove(userId);
            module.getBot().sendMessage(userId, msg(Key.INVALID_LINK, lang), REMOVE_KEYBOARD);
            return;
        }
        if (isBlocked(userService.findByTelegramId(userId).orElse(null))) {
            pendingLogins.remove(userId);
            module.getBot().sendMessage(userId, msg(Key.ACCESS_DENIED, lang), REMOVE_KEYBOARD);
            return;
        }

        module.getBot().sendMessage(userId, msg(Key.CONTACT_SKIPPED, lang), REMOVE_KEYBOARD);
        proceedAfterIdentity(userId, from, pending.rawToken(), null, lang);
    }

    /**
     * Inline ✅/❌ press. Callbacks outside the {@code tgauth:} namespace go to
     * the module fallback, and a press coming from anywhere but the presser's
     * own private chat is refused.
     */
    public void onCallback(JsonNode update) {
        JsonNode cq = update.path("callback_query");
        String data = cq.path("data").asText("");
        if (!data.startsWith(CALLBACK_PREFIX)) {
            log.debug("foreign callback_data passed to fallback");
            delegateToFallback(update);
            return;
        }
        JsonNode from = cq.path("from");
        long userId = from.path("id").asLong();
        String lang = FlowMessages.resolveLang(from.path("language_code").asText(null));
        String callbackId = cq.path("id").asText("");
        JsonNode message = cq.path("message");
        long chatId = message.path("chat").path("id").asLong();
        long messageId = message.path("message_id").asLong();

        if (userId == 0 || chatId != userId) {
            log.warn("tgauth callback refused: chatId={} is not the presser's private chat (userId={})",
                    chatId, userId);
            module.getBot().answerCallbackQuery(callbackId, msg(Key.ACCESS_DENIED, lang));
            return;
        }

        String[] parts = data.split(":", 3);
        if (parts.length != 3) return;
        String action = parts[1];
        String rawToken = parts[2];

        U user = userService.findByTelegramId(userId).orElse(null);
        if (isBlocked(user)) {
            clearPending(userId, rawToken);
            finishCallback(callbackId, chatId, messageId, msg(Key.ACCESS_DENIED, lang));
            return;
        }

        if ("approve".equals(action)) {
            if (validPendingSession(rawToken).isEmpty()) {
                clearPending(userId, rawToken);
                finishCallback(callbackId, chatId, messageId, msg(Key.SESSION_EXPIRED, lang));
                return;
            }
            // Registration happens here, not on /start: a rejected or abandoned
            // login must not leave an ACTIVE account behind.
            U approver = registerFrom(userId, from, phoneOf(userId, rawToken));
            boolean ok = sessionService.approve(sessionService.hash(rawToken), approver);
            clearPending(userId, rawToken);
            finishCallback(callbackId, chatId, messageId,
                    msg(ok ? Key.APPROVED : Key.SESSION_EXPIRED, lang));
        } else if ("reject".equals(action)) {
            boolean ok = sessionService.reject(sessionService.hash(rawToken));
            clearPending(userId, rawToken);
            finishCallback(callbackId, chatId, messageId,
                    msg(ok ? Key.REJECTED : Key.SESSION_EXPIRED, lang));
        } else {
            log.debug("unknown tgauth callback action: {}", action);
        }
    }

    /**
     * Approval step. With {@code requireApproval} the inline confirmation is
     * sent and the login is parked until ✅/❌ (no user row is written yet);
     * otherwise the user is registered and the session approved right away.
     *
     * @param phone phone collected during the contact step, or {@code null}
     */
    protected void proceedAfterIdentity(long userId, JsonNode from, String rawToken, String phone, String lang) {
        if (options.requireApproval()) {
            S session = validPendingSession(rawToken).orElse(null);
            if (session == null) {
                pendingLogins.remove(userId);
                module.getBot().sendMessage(userId, msg(Key.INVALID_LINK, lang));
                return;
            }
            parkPending(userId, new Pending(rawToken, OffsetDateTime.now(), phone));
            module.getBot().sendMessage(userId, confirmPrompt(session, lang), approveKeyboard(lang, rawToken));
            return;
        }
        pendingLogins.remove(userId);
        U user = registerFrom(userId, from, phone);
        boolean ok = sessionService.approve(sessionService.hash(rawToken), user);
        module.getBot().sendMessage(userId, msg(ok ? Key.APPROVED : Key.SESSION_EXPIRED, lang));
        log.debug("default flow {} userId={}", ok ? "approved" : "not-approved", userId);
    }

    /**
     * Confirmation text: the question plus the session's IP and device, so the
     * user can tell their own sign-in from one a phisher started for them.
     */
    protected String confirmPrompt(S session, String lang) {
        String ip = isBlank(session.getIpAddress()) ? "—" : session.getIpAddress();
        String ua = isBlank(session.getUserAgent()) ? "—" : shorten(session.getUserAgent());
        return msg(Key.CONFIRM_PROMPT, lang) + "\n\n" + String.format(msg(Key.CONFIRM_DETAILS, lang), ip, ua);
    }

    /** Override point for custom wording; defaults to the built-in 3-language table. */
    protected String msg(FlowMessages.Key key, String lang) {
        return FlowMessages.text(key, lang);
    }

    private U registerFrom(long userId, JsonNode from, String phone) {
        return userService.register(
                userId,
                phone,
                from.path("first_name").asText(null),
                from.path("last_name").asText(null),
                from.path("username").asText(null),
                from.path("language_code").asText("uz"));
    }

    private Optional<S> validPendingSession(String rawToken) {
        return sessionService.findByRawToken(rawToken)
                .filter(s -> s.getStatus() == BaseAuthSession.Status.PENDING)
                .filter(s -> s.getExpiresAt() == null || s.getExpiresAt().isAfter(OffsetDateTime.now()));
    }

    /**
     * A Telegram private chat always has {@code chat.id == from.id}; anywhere
     * else (group, supergroup, channel) the chat id is not a user id.
     */
    private static boolean isPrivateChat(JsonNode message) {
        long userId = message.path("from").path("id").asLong();
        long chatId = message.path("chat").path("id").asLong();
        String type = message.path("chat").path("type").asText("");
        return userId != 0 && chatId == userId && (type.isEmpty() || "private".equals(type));
    }

    private boolean isBlocked(U user) {
        return user != null && user.getStatus() == BaseTelegramUser.Status.BLOCKED;
    }

    /** Phone collected earlier for this exact login, or {@code null}. */
    private String phoneOf(long userId, String rawToken) {
        Pending pending = pendingLogins.get(userId);
        return (pending != null && pending.rawToken().equals(rawToken)) ? pending.phone() : null;
    }

    /** Drops the parked login only if it is still the one this update refers to. */
    private void clearPending(long userId, String rawToken) {
        Pending pending = pendingLogins.get(userId);
        if (pending != null && pending.rawToken().equals(rawToken)) {
            pendingLogins.remove(userId, pending);
        }
    }

    /**
     * Parks an in-flight login, purging aged-out entries first and evicting the
     * oldest if the map is still at its ceiling.
     */
    private void parkPending(long userId, Pending pending) {
        purgeStalePending();
        if (pendingLogins.size() >= MAX_PENDING_LOGINS && !pendingLogins.containsKey(userId)) {
            pendingLogins.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().createdAt()))
                    .ifPresent(oldest -> {
                        log.warn("pending-login map at capacity ({}), evicting the oldest entry", MAX_PENDING_LOGINS);
                        pendingLogins.remove(oldest.getKey(), oldest.getValue());
                    });
        }
        pendingLogins.put(userId, pending);
    }

    private void purgeStalePending() {
        if (pendingLogins.isEmpty()) return;
        OffsetDateTime cutoff = OffsetDateTime.now().minus(module.getSessionTtl());
        pendingLogins.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(cutoff));
    }

    private void delegateToFallback(JsonNode update) {
        Consumer<JsonNode> fallback = module.getFallback();
        if (fallback != null) fallback.accept(update);
    }

    private void finishCallback(String callbackId, long chatId, long messageId, String text) {
        module.getBot().answerCallbackQuery(callbackId, text);
        module.getBot().editMessageText(chatId, messageId, text);
    }

    private String contactKeyboard(String lang) {
        return toJson(Map.of(
                "keyboard", List.of(List.of(Map.of(
                        "text", msg(Key.BTN_SHARE_CONTACT, lang),
                        "request_contact", true))),
                "resize_keyboard", true,
                "one_time_keyboard", true));
    }

    private String approveKeyboard(String lang, String rawToken) {
        return toJson(Map.of("inline_keyboard", List.of(List.of(
                Map.of("text", msg(Key.BTN_APPROVE, lang),
                        "callback_data", callbackData("approve", rawToken)),
                Map.of("text", msg(Key.BTN_REJECT, lang),
                        "callback_data", callbackData("reject", rawToken))))));
    }

    /**
     * Builds {@code tgauth:<action>:<rawToken>} and fails fast when it would
     * exceed Telegram's 64-byte {@code callback_data} limit — otherwise the API
     * silently rejects the keyboard and the user sees nothing. The default
     * 32-byte token (43 chars) leaves 6 bytes of headroom; a custom
     * {@code TokenGenerator} must stay within that budget.
     */
    private static String callbackData(String action, String rawToken) {
        String data = CALLBACK_PREFIX + action + ":" + rawToken;
        int bytes = data.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > CALLBACK_DATA_MAX_BYTES) {
            int budget = CALLBACK_DATA_MAX_BYTES - CALLBACK_PREFIX.length() - action.length() - 1;
            throw new IllegalStateException("callback_data is " + bytes + " bytes but Telegram allows "
                    + CALLBACK_DATA_MAX_BYTES + "; requireApproval needs raw session tokens of at most "
                    + budget + " chars — shorten TokenGenerator#newToken");
        }
        return data;
    }

    private static String shorten(String s) {
        return s.length() <= UA_PREVIEW_CHARS ? s : s.substring(0, UA_PREVIEW_CHARS) + "…";
    }

    private static String toJson(Map<String, Object> value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("keyboard serialization failed", e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
