package io.github.dev_abdulhay.telegramauth.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession.Status;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.flow.FlowMessages.Key;
import io.github.dev_abdulhay.telegramauth.service.AbstractSessionService;
import io.github.dev_abdulhay.telegramauth.service.AbstractTelegramUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Default reg/auth flow. Self-registers its handlers into the module on
 * construction, so declaring this bean is enough to get a working login.
 * Subclass and {@code @Override} the {@code on*} methods to customise.
 *
 * <p>Behaviour is controlled by {@link Options}:
 * <ul>
 *   <li>{@code requireContact} (default off) — asks for the user's phone via
 *       Telegram contact-share when the user is unknown or has no phone;
 *       {@code /skip} continues without one (soft requirement).</li>
 *   <li>{@code requireApproval} (default off) — adds an inline ✅ / ❌
 *       confirmation showing the session's IP and device.</li>
 *   <li>{@code codeConfirmation} (default {@link CodeConfirmation#BUTTON}) —
 *       asks for the 2-digit number displayed in the browser. This is the step
 *       that actually stops device-code phishing: ✅ alone proves only that
 *       someone tapped a link, while the number proves they are looking at the
 *       screen that started the login.</li>
 * </ul>
 *
 * <p>The code is <b>not</b> a secret — it is derived from the token hash, and
 * anyone holding the deep link can compute it. Its value is that a victim who
 * is not at the browser cannot know it without being told, which turns a
 * one-tap attack into a live, interactive one that leaves {@code WARN} logs.
 *
 * <p><b>Private chats only.</b> Every handler ignores updates whose
 * {@code chat.id} differs from {@code from.id} (groups, channels, supergroups):
 * there the chat id is not a user id, and an inline confirmation posted to a
 * group could be tapped by a bystander, approving someone else's browser
 * session under their account.
 *
 * <p>The user record is created (or refreshed) only at the <em>final</em>
 * confirmation — a login rejected or abandoned at any earlier step leaves no
 * account behind.
 *
 * <p>Users with status {@code BLOCKED} are always denied and never re-activated
 * by this flow. Updates this flow does not own (foreign {@code callback_data},
 * a contact or text with no login in progress) are forwarded to the module
 * fallback, so the host can keep its own inline keyboards and text handling.
 * Bot texts are localized (uz/ru/en) via {@link FlowMessages}; override
 * {@link #msg(FlowMessages.Key, String)} to customise wording.
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
    /** Buttons per inline-keyboard row in {@code BUTTON} mode. */
    private static final int CODE_BUTTONS_PER_ROW = 5;
    /**
     * Hard ceiling on parked logins. Entries normally age out within one
     * {@code sessionTtl}, but a flood of {@code /start}s from abandoned logins
     * could outrun that; past this size the oldest entries are dropped so the
     * map cannot grow without bound.
     */
    private static final int MAX_PENDING_LOGINS = 10_000;

    private static final Set<Status> PENDING_ONLY = EnumSet.of(Status.PENDING);
    private static final Set<Status> AWAITING_ONLY = EnumSet.of(Status.AWAITING_CODE);

    /** Immutable opt-in flags for the flow. */
    public static final class Options {
        private final boolean requireContact;
        private final boolean requireApproval;
        private final CodeConfirmation codeConfirmation;
        private final int codeButtons;
        private final int maxCodeAttempts;
        private final Duration codeCooldown;
        private final Duration codeCooldownMax;
        private final int codeCooldownThreshold;

        private Options(Builder b) {
            this.requireContact = b.requireContact;
            this.requireApproval = b.requireApproval;
            this.codeConfirmation = b.codeConfirmation;
            this.codeButtons = b.codeButtons;
            this.maxCodeAttempts = b.maxCodeAttempts;
            this.codeCooldown = b.codeCooldown;
            this.codeCooldownMax = b.codeCooldownMax;
            this.codeCooldownThreshold = b.codeCooldownThreshold;
        }

        public boolean requireContact() { return requireContact; }
        public boolean requireApproval() { return requireApproval; }
        public CodeConfirmation codeConfirmation() { return codeConfirmation; }
        public int codeButtons() { return codeButtons; }
        public int maxCodeAttempts() { return maxCodeAttempts; }
        public Duration codeCooldown() { return codeCooldown; }
        public Duration codeCooldownMax() { return codeCooldownMax; }
        public int codeCooldownThreshold() { return codeCooldownThreshold; }

        /**
         * Wrong guesses allowed on one login. Resolves {@code maxCodeAttempts == 0}
         * to the per-mode default: one for {@code BUTTON}, where a wrong tap out of
         * {@code n} means a 1-in-{@code n} attack just succeeded, and three for
         * {@code TYPED}, where 100 candidates keep three tries under 3% and a single
         * misread should not cost the user their session.
         */
        public int effectiveMaxCodeAttempts() {
            if (maxCodeAttempts > 0) return maxCodeAttempts;
            return codeConfirmation == CodeConfirmation.TYPED ? 3 : 1;
        }

        public static Builder builder() { return new Builder(); }
        public static Options defaults() { return new Builder().build(); }

        public static final class Builder {
            private boolean requireContact;
            private boolean requireApproval;
            private CodeConfirmation codeConfirmation = CodeConfirmation.BUTTON;
            private int codeButtons = 3;
            private int maxCodeAttempts;
            private Duration codeCooldown = Duration.ofMinutes(5);
            private Duration codeCooldownMax = Duration.ofHours(1);
            private int codeCooldownThreshold = 1;

            public Builder requireContact(boolean v) { this.requireContact = v; return this; }
            public Builder requireApproval(boolean v) { this.requireApproval = v; return this; }

            /** Number-matching mode; {@code OFF} restores the pre-0.4.0 behaviour. */
            public Builder codeConfirmation(CodeConfirmation v) { this.codeConfirmation = v; return this; }

            /**
             * How many numbers the user picks from in {@code BUTTON} mode (3–10). A blind
             * guess succeeds once in this many, so 3 leaves a 33% single-shot chance —
             * raise it, or use {@code TYPED}, when that is too generous.
             */
            public Builder codeButtons(int v) { this.codeButtons = v; return this; }

            /** Wrong guesses allowed per login; {@code 0} picks the per-mode default. */
            public Builder maxCodeAttempts(int v) { this.maxCodeAttempts = v; return this; }

            /**
             * First cooldown applied to a Telegram user whose login died at the code step,
             * doubling on every further failure up to {@link #codeCooldownMax(Duration)}.
             * Without it, rejecting the session is no obstacle: the attacker simply opens
             * a new one, and the per-session odds compound over rounds.
             * {@code Duration.ZERO} disables cooldowns.
             */
            public Builder codeCooldown(Duration v) { this.codeCooldown = v; return this; }

            /** Ceiling for the doubling cooldown ladder. */
            public Builder codeCooldownMax(Duration v) { this.codeCooldownMax = v; return this; }

            /**
             * Failed logins tolerated before the first cooldown arms. {@code 1} (default)
             * punishes immediately; {@code 2} forgives one genuine misread at the cost of
             * giving an attacker a second free guess.
             */
            public Builder codeCooldownThreshold(int v) { this.codeCooldownThreshold = v; return this; }

            public Options build() {
                if (codeConfirmation == null) {
                    throw new IllegalArgumentException("codeConfirmation must not be null");
                }
                if (codeButtons < 3 || codeButtons > 10) {
                    throw new IllegalArgumentException(
                            "codeButtons must be between 3 and 10 but was " + codeButtons
                                    + "; fewer than 3 makes guessing trivial and more than 10 will not fit a keyboard");
                }
                if (maxCodeAttempts < 0) {
                    throw new IllegalArgumentException("maxCodeAttempts must not be negative");
                }
                if (codeCooldown == null || codeCooldown.isNegative()) {
                    throw new IllegalArgumentException("codeCooldown must not be null or negative");
                }
                if (codeCooldownMax == null || codeCooldownMax.compareTo(codeCooldown) < 0) {
                    throw new IllegalArgumentException("codeCooldownMax must not be null or below codeCooldown");
                }
                if (codeCooldownThreshold < 1) {
                    throw new IllegalArgumentException("codeCooldownThreshold must be at least 1");
                }
                return new Options(this);
            }
        }
    }

    /**
     * In-flight login for one Telegram user: the token, any phone collected on
     * the way, and how many confirmation-code guesses have been spent.
     */
    private record Pending(String rawToken, OffsetDateTime createdAt, String phone, int codeAttempts) {}

    protected final AbstractTelegramUserService<U> userService;
    protected final AbstractSessionService<U, S> sessionService;
    protected final TelegramBotModule module;
    protected final Options options;

    /**
     * telegram user id → in-flight login; correlates the contact, approval and
     * confirmation-code steps.
     *
     * <p>JVM-local and not replicated. Losing it (restart, or a second instance
     * taking over polling) is degraded but not broken: {@code ✅}/{@code ❌} and
     * the number buttons still work because the token travels in the callback
     * and the session lives in the DB — only a phone shared moments earlier is
     * forgotten, and the previously stored one is kept. A pending {@code /skip},
     * contact-share, or typed code, however, has nothing to correlate against
     * and the user starts over. The confirmation-code step widens this window:
     * an entry must now survive from the contact step all the way to the final
     * number. Override the {@code on*} methods with a shared store if a mid-flow
     * handover has to survive intact.
     */
    private final ConcurrentHashMap<Long, Pending> pendingLogins = new ConcurrentHashMap<>();

    /** Cools a Telegram user down after their login dies at the confirmation-code step. */
    private final CodeStrikeTracker codeStrikes;

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
        this.codeStrikes = new CodeStrikeTracker(
                options.codeCooldown(), options.codeCooldownMax(), options.codeCooldownThreshold());
        module.command("/start", this::onStart);
        if (options.requireContact()) {
            module.command("/skip", this::onSkip);
            module.onContact(this::onContact);
        }
        // the number buttons and the ❌ of the code step are callbacks too, so the
        // slot is needed whenever either confirmation step is on
        if (options.requireApproval() || codeStepEnabled()) {
            module.onCallbackQuery(this::onCallback);
        }
        if (options.codeConfirmation() == CodeConfirmation.TYPED) {
            module.onText(this::onText);
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

        Duration cooling = codeStrikes.remaining(userId);
        if (cooling != null) {
            log.warn("login refused: telegramId={} is cooling down for {} more minutes "
                    + "after failed confirmation codes", userId, minutesLeft(cooling));
            module.getBot().sendMessage(userId, cooldownText(cooling, lang));
            return;
        }

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
            parkPending(userId, new Pending(rawToken, OffsetDateTime.now(), null, 0));
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
     * Inline button press: ✅ / ❌ or a confirmation-code number. Callbacks
     * outside the {@code tgauth:} namespace go to the module fallback, and a
     * press coming from anywhere but the presser's own private chat is refused.
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

        if ("reject".equals(action)) {
            // never blocked by a cooldown: saying "this was not me" must always work
            boolean ok = sessionService.reject(sessionService.hash(rawToken));
            clearPending(userId, rawToken);
            finishCallback(callbackId, chatId, messageId,
                    msg(ok ? Key.REJECTED : Key.SESSION_EXPIRED, lang));
            return;
        }

        Duration cooling = codeStrikes.remaining(userId);
        if (cooling != null) {
            log.warn("tgauth callback refused: telegramId={} is cooling down for {} more minutes",
                    userId, minutesLeft(cooling));
            finishCallback(callbackId, chatId, messageId, cooldownText(cooling, lang));
            return;
        }

        if ("approve".equals(action)) {
            onApprovePressed(userId, from, callbackId, chatId, messageId, rawToken, lang);
            return;
        }

        Integer guess = parseCodeAction(action);
        if (guess != null) {
            if (options.codeConfirmation() != CodeConfirmation.BUTTON) {
                log.debug("code callback ignored: codeConfirmation is {}", options.codeConfirmation());
                return;
            }
            finishCallback(callbackId, chatId, messageId, handleGuess(userId, from, rawToken, guess, lang));
            return;
        }
        log.debug("unknown tgauth callback action: {}", action);
    }

    /**
     * Plain text update, registered only in {@code TYPED} mode. Anything that is
     * not this user's confirmation code is handed to the module fallback, so the
     * host keeps its own text handling.
     */
    public void onText(JsonNode update) {
        JsonNode message = update.path("message");
        JsonNode from = message.path("from");
        long userId = from.path("id").asLong();
        String lang = FlowMessages.resolveLang(from.path("language_code").asText(null));
        String text = message.path("text").asText("").trim();

        // an unregistered /command reaches us as text; it is never a code guess
        if (!isPrivateChat(message) || text.startsWith("/")) {
            delegateToFallback(update);
            return;
        }

        Pending pending = pendingLogins.get(userId);
        if (pending == null || awaitingCodeSession(pending.rawToken()).isEmpty()) {
            log.debug("text with no code step in progress, userId={} — passing to fallback", userId);
            delegateToFallback(update);
            return;
        }

        Duration cooling = codeStrikes.remaining(userId);
        if (cooling != null) {
            log.warn("code entry refused: telegramId={} is cooling down for {} more minutes",
                    userId, minutesLeft(cooling));
            module.getBot().sendMessage(userId, cooldownText(cooling, lang));
            return;
        }

        // non-numeric text costs no attempt: an attacker's guesses are numeric,
        // and a stray "hello" must not burn the user's only try
        if (!text.matches("\\d{1,2}")) {
            module.getBot().sendMessage(userId, msg(Key.CODE_NOT_A_NUMBER, lang));
            return;
        }

        module.getBot().sendMessage(userId,
                handleGuess(userId, from, pending.rawToken(), Integer.parseInt(text), lang));
    }

    /**
     * Identity is settled — run whichever confirmation steps are enabled.
     * With no confirmation at all the session is approved immediately, which is
     * the pre-0.4.0 behaviour.
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
            parkPending(userId, new Pending(rawToken, OffsetDateTime.now(), phone, 0));
            module.getBot().sendMessage(userId, confirmPrompt(session, lang), approveKeyboard(lang, rawToken));
            return;
        }
        if (codeStepEnabled()) {
            // no ✅ step ran, so this prompt carries the session details and the warning
            enterCodeStage(userId, rawToken, phone, lang, true);
            return;
        }
        pendingLogins.remove(userId);
        U user = registerFrom(userId, from, phone);
        boolean ok = sessionService.approve(sessionService.hash(rawToken), user);
        module.getBot().sendMessage(userId, msg(ok ? Key.APPROVED : Key.SESSION_EXPIRED, lang));
        log.debug("default flow {} userId={}", ok ? "approved" : "not-approved", userId);
    }

    /**
     * ✅ pressed. With a code step configured this only unlocks the second
     * question — no account is created and the host handler is not called yet.
     */
    protected void onApprovePressed(long userId, JsonNode from, String callbackId,
                                    long chatId, long messageId, String rawToken, String lang) {
        if (!codeStepEnabled()) {
            if (validPendingSession(rawToken).isEmpty()) {
                clearPending(userId, rawToken);
                finishCallback(callbackId, chatId, messageId, msg(Key.SESSION_EXPIRED, lang));
                return;
            }
            U approver = registerFrom(userId, from, phoneOf(userId, rawToken));
            boolean ok = sessionService.approve(sessionService.hash(rawToken), approver);
            clearPending(userId, rawToken);
            codeStrikes.clear(userId);
            finishCallback(callbackId, chatId, messageId, msg(ok ? Key.APPROVED : Key.SESSION_EXPIRED, lang));
            return;
        }

        boolean moved = sessionService.awaitCode(sessionService.hash(rawToken));
        // a second tap on ✅ is ordinary in Telegram: the session is already at the
        // code step, so re-ask instead of claiming the session expired
        if (!moved && awaitingCodeSession(rawToken).isEmpty()) {
            clearPending(userId, rawToken);
            finishCallback(callbackId, chatId, messageId, msg(Key.SESSION_EXPIRED, lang));
            return;
        }
        Pending pending = pendingLogins.get(userId);
        if (pending == null || !pending.rawToken().equals(rawToken)) {
            parkPending(userId, new Pending(rawToken, OffsetDateTime.now(), null, 0));
        }
        // the attempt count deliberately survives a re-tap, otherwise pressing ✅
        // again would hand an attacker a fresh set of guesses
        finishCallback(callbackId, chatId, messageId, msg(Key.CONFIRM_STEP_DONE, lang));
        sendCodePrompt(userId, rawToken, lang, null);
    }

    /**
     * Moves the session to {@code AWAITING_CODE} and asks the question.
     *
     * @param withDetails include the session's IP/device and the phishing warning,
     *                    which is needed when no ✅ step showed them already
     */
    protected void enterCodeStage(long userId, String rawToken, String phone, String lang, boolean withDetails) {
        S session = validPendingSession(rawToken).orElse(null);
        if (session == null || !sessionService.awaitCode(sessionService.hash(rawToken))) {
            pendingLogins.remove(userId);
            module.getBot().sendMessage(userId, msg(Key.SESSION_EXPIRED, lang));
            return;
        }
        parkPending(userId, new Pending(rawToken, OffsetDateTime.now(), phone, 0));
        sendCodePrompt(userId, rawToken, lang, withDetails ? session : null);
    }

    /** Sends (or re-sends) the code question with a freshly shuffled keyboard. */
    protected void sendCodePrompt(long userId, String rawToken, String lang, S detailsOf) {
        int realCode = module.getConfirmCodeGenerator().codeFor(sessionService.hash(rawToken));
        StringBuilder text = new StringBuilder(msg(
                options.codeConfirmation() == CodeConfirmation.TYPED
                        ? Key.CODE_PROMPT_TYPED : Key.CODE_PROMPT_BUTTON, lang));
        if (detailsOf != null) {
            text.append("\n\n").append(sessionDetails(detailsOf, lang))
                    .append("\n\n").append(msg(Key.CONFIRM_WARNING, lang));
        }
        module.getBot().sendMessage(userId, text.toString(), codeKeyboard(realCode, lang, rawToken));
    }

    /**
     * Final step: checks a guess against the session's confirmation code.
     * A correct one registers the user and approves; a wrong one burns an attempt
     * and, once they run out, rejects the session and starts the user cooling
     * down — telling them to "try again" instead would let an attacker simply tap
     * every button.
     *
     * @return the text to show the user
     */
    protected String handleGuess(long userId, JsonNode from, String rawToken, int guess, String lang) {
        String hash = sessionService.hash(rawToken);
        if (awaitingCodeSession(rawToken).isEmpty()) {
            clearPending(userId, rawToken);
            return msg(Key.SESSION_EXPIRED, lang);
        }

        if (guess == module.getConfirmCodeGenerator().codeFor(hash)) {
            U user = registerFrom(userId, from, phoneOf(userId, rawToken));
            boolean ok = sessionService.approve(hash, user);
            clearPending(userId, rawToken);
            codeStrikes.clear(userId);
            return msg(ok ? Key.APPROVED : Key.SESSION_EXPIRED, lang);
        }

        Pending pending = pendingLogins.get(userId);
        boolean matches = pending != null && pending.rawToken().equals(rawToken);
        int used = (matches ? pending.codeAttempts() : 0) + 1;
        int left = options.effectiveMaxCodeAttempts() - used;
        log.warn("wrong confirmation code from telegramId={} ({} of {} attempts used) — possible phishing",
                userId, used, options.effectiveMaxCodeAttempts());

        if (left > 0) {
            parkPending(userId, new Pending(rawToken,
                    matches ? pending.createdAt() : OffsetDateTime.now(),
                    matches ? pending.phone() : null,
                    used));
            if (options.codeConfirmation() == CodeConfirmation.BUTTON) {
                // reshuffle, otherwise the next tap is a free elimination
                sendCodePrompt(userId, rawToken, lang, null);
            }
            return String.format(msg(Key.CODE_WRONG, lang), left);
        }

        sessionService.reject(hash);
        clearPending(userId, rawToken);
        Duration cooldown = codeStrikes.strike(userId);
        return cooldown == null ? msg(Key.CODE_ATTEMPTS_EXHAUSTED, lang) : cooldownText(cooldown, lang);
    }

    /**
     * The numbers offered in {@code BUTTON} mode: the real code plus distinct
     * decoys, shuffled. Cryptographic randomness is pointless here — an attacker
     * can compute the real code from the link either way; what matters is that
     * the set contains it exactly once so a correct tap proves nothing but
     * having read the browser.
     */
    protected List<Integer> codeChoices(int realCode, int count) {
        LinkedHashSet<Integer> choices = new LinkedHashSet<>();
        choices.add(realCode);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        while (choices.size() < count) {
            choices.add(rnd.nextInt(100));
        }
        List<Integer> shuffled = new ArrayList<>(choices);
        Collections.shuffle(shuffled, rnd);
        return shuffled;
    }

    /** How a code is rendered on buttons and compared as {@code callback_data}. */
    protected String formatCode(int code) {
        return String.format("%02d", code);
    }

    /**
     * Confirmation text: the question, the session's IP and device so the user
     * can tell their own sign-in from one a phisher started for them, and the
     * warning that nobody should ever ask them to tap ✅.
     */
    protected String confirmPrompt(S session, String lang) {
        return msg(Key.CONFIRM_PROMPT, lang) + "\n\n" + sessionDetails(session, lang)
                + "\n\n" + msg(Key.CONFIRM_WARNING, lang);
    }

    /** The session's IP and device, formatted for a bot message. */
    protected String sessionDetails(S session, String lang) {
        String ip = isBlank(session.getIpAddress()) ? "—" : session.getIpAddress();
        String ua = isBlank(session.getUserAgent()) ? "—" : shorten(session.getUserAgent());
        return String.format(msg(Key.CONFIRM_DETAILS, lang), ip, ua);
    }

    /** Override point for custom wording; defaults to the built-in 3-language table. */
    protected String msg(FlowMessages.Key key, String lang) {
        return FlowMessages.text(key, lang);
    }

    private boolean codeStepEnabled() {
        return options.codeConfirmation() != CodeConfirmation.OFF;
    }

    private String cooldownText(Duration left, String lang) {
        return String.format(msg(Key.TOO_MANY_ATTEMPTS, lang), minutesLeft(left));
    }

    /** Rounds up, so a cooldown with seconds left never reads as "0 minutes". */
    private static long minutesLeft(Duration left) {
        return Math.max(1, (left.toSeconds() + 59) / 60);
    }

    /** {@code "c07"} → {@code 7}; {@code null} when the action is not a code guess. */
    private static Integer parseCodeAction(String action) {
        if (action.length() < 2 || action.charAt(0) != 'c') return null;
        String digits = action.substring(1);
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) return null;
        }
        return Integer.valueOf(digits);
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
        return liveSession(rawToken, PENDING_ONLY);
    }

    private Optional<S> awaitingCodeSession(String rawToken) {
        return liveSession(rawToken, AWAITING_ONLY);
    }

    private Optional<S> liveSession(String rawToken, Set<Status> allowed) {
        return sessionService.findByRawToken(rawToken)
                .filter(s -> allowed.contains(s.getStatus()))
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
        codeStrikes.purge();
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
     * Number buttons in {@code BUTTON} mode, always followed by ❌ on its own row.
     * {@code TYPED} gets only ❌ — the number arrives as text.
     */
    private String codeKeyboard(int realCode, String lang, String rawToken) {
        List<Map<String, String>> rejectRow = List.of(Map.of(
                "text", msg(Key.BTN_REJECT, lang),
                "callback_data", callbackData("reject", rawToken)));

        if (options.codeConfirmation() != CodeConfirmation.BUTTON) {
            return toJson(Map.of("inline_keyboard", List.of(rejectRow)));
        }

        List<List<Map<String, String>>> rows = new ArrayList<>();
        List<Map<String, String>> row = new ArrayList<>();
        for (int code : codeChoices(realCode, options.codeButtons())) {
            String label = formatCode(code);
            row.add(Map.of("text", label, "callback_data", callbackData("c" + label, rawToken)));
            if (row.size() == CODE_BUTTONS_PER_ROW) {
                rows.add(row);
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) rows.add(row);
        rows.add(rejectRow);
        return toJson(Map.of("inline_keyboard", rows));
    }

    /**
     * Builds {@code tgauth:<action>:<rawToken>} and fails fast when it would
     * exceed Telegram's 64-byte {@code callback_data} limit — otherwise the API
     * silently rejects the keyboard and the user sees nothing. The default
     * 32-byte token (43 chars) leaves 6 bytes of headroom on the longest action
     * ({@code approve}); a custom {@code TokenGenerator} must stay within that
     * budget.
     */
    private static String callbackData(String action, String rawToken) {
        String data = CALLBACK_PREFIX + action + ":" + rawToken;
        int bytes = data.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > CALLBACK_DATA_MAX_BYTES) {
            int budget = CALLBACK_DATA_MAX_BYTES - CALLBACK_PREFIX.length() - action.length() - 1;
            throw new IllegalStateException("callback_data is " + bytes + " bytes but Telegram allows "
                    + CALLBACK_DATA_MAX_BYTES + "; inline confirmation needs raw session tokens of at most "
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
