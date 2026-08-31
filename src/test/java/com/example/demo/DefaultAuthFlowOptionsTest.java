package com.example.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dev_abdulhay.telegramauth.api.dto.AuthApproveResult;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBot;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.flow.CodeConfirmation;
import io.github.dev_abdulhay.telegramauth.flow.DefaultAuthFlow;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import io.github.dev_abdulhay.telegramauth.service.SessionRateLimitException;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers the opt-in contact-share / inline-approval flow and the hardening fixes. */
class DefaultAuthFlowOptionsTest {

    private static final ObjectMapper M = new ObjectMapper();

    static class RecordingBot extends TelegramBot {
        record Sent(long chatId, String text, String markup) {}
        final List<Sent> sent = new ArrayList<>();
        final List<String> answered = new ArrayList<>();
        final List<String> edited = new ArrayList<>();

        RecordingBot() { super(HttpClient.newHttpClient(), "x"); }

        @Override public void sendMessage(long chatId, String text) { sendMessage(chatId, text, null); }
        @Override public void sendMessage(long chatId, String text, String markup) { sent.add(new Sent(chatId, text, markup)); }
        @Override public void answerCallbackQuery(String id, String text) { answered.add(text); }
        @Override public void editMessageText(long chatId, long messageId, String text) { edited.add(text); }

        Sent last() { return sent.get(sent.size() - 1); }
    }

    record Env(RecordingBot bot, TelegramBotModule module, DemoUserService users,
               DemoSessionService sessions, StubUserRepo userRepo, StubSessionRepo sessionRepo) {}

    private Env env(DefaultAuthFlow.Options opts) {
        RecordingBot bot = new RecordingBot();
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "demo_bot")
                .bot(bot)
                .approveHandler((info, ctx) -> new AuthApproveResult(Map.of("phone", String.valueOf(info.phone()))))
                .build();
        StubUserRepo userRepo = new StubUserRepo();
        StubSessionRepo sessionRepo = new StubSessionRepo();
        DemoUserService users = new DemoUserService(userRepo);
        DemoSessionService sessions = new DemoSessionService(sessionRepo, new TokenGenerator(), module);
        new DefaultAuthFlow<>(users, sessions, module, opts);
        return new Env(bot, module, users, sessions, userRepo, sessionRepo);
    }

    /**
     * Pre-0.4.0 behaviour: the number-matching step off, so these tests keep
     * asserting the flow as it was before the confirmation code existed.
     */
    private static DefaultAuthFlow.Options.Builder legacy() {
        return DefaultAuthFlow.Options.builder().codeConfirmation(CodeConfirmation.OFF);
    }

    private static JsonNode start(long userId, String raw, String lang) throws Exception {
        return startIn(userId, userId, raw, lang);
    }

    /** {@code chatId != userId} models a group/supergroup, where the deep link must be ignored. */
    private static JsonNode startIn(long chatId, long userId, String raw, String lang) throws Exception {
        return M.readTree(M.writeValueAsString(Map.of("message", Map.of(
                "text", "/start " + raw,
                "chat", Map.of("id", chatId),
                "from", Map.of("id", userId, "first_name", "Ali", "language_code", lang)))));
    }

    private static JsonNode contact(long userId, long contactOwnerId, String phone) throws Exception {
        return M.readTree(M.writeValueAsString(Map.of("message", Map.of(
                "chat", Map.of("id", userId),
                "from", Map.of("id", userId, "first_name", "Ali", "language_code", "uz"),
                "contact", Map.of("user_id", contactOwnerId, "phone_number", phone)))));
    }

    private static JsonNode skip(long userId) throws Exception {
        return M.readTree(M.writeValueAsString(Map.of("message", Map.of(
                "text", "/skip",
                "chat", Map.of("id", userId),
                "from", Map.of("id", userId, "first_name", "Ali", "language_code", "uz")))));
    }

    private static JsonNode callback(long userId, String data, String lang) throws Exception {
        return callbackIn(userId, userId, data, lang);
    }

    /** {@code chatId != userId} models a button tapped in a group chat. */
    private static JsonNode callbackIn(long chatId, long userId, String data, String lang) throws Exception {
        return M.readTree(M.writeValueAsString(Map.of("callback_query", Map.of(
                "id", "cb1",
                "data", data,
                "from", Map.of("id", userId, "language_code", lang),
                "message", Map.of("chat", Map.of("id", chatId), "message_id", 42)))));
    }

    // --- options surface ---

    @Test
    void codeButtonsMustStayBetweenThreeAndTen() {
        assertThatThrownBy(() -> DefaultAuthFlow.Options.builder().codeButtons(2).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("codeButtons");
        assertThatThrownBy(() -> DefaultAuthFlow.Options.builder().codeButtons(11).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("codeButtons");
        assertThat(DefaultAuthFlow.Options.builder().codeButtons(3).build().codeButtons()).isEqualTo(3);
        assertThat(DefaultAuthFlow.Options.builder().codeButtons(10).build().codeButtons()).isEqualTo(10);
    }

    @Test
    void codeConfirmationDefaultsToButtonAndAttemptsFollowTheMode() {
        DefaultAuthFlow.Options d = DefaultAuthFlow.Options.defaults();
        assertThat(d.codeConfirmation()).isEqualTo(CodeConfirmation.BUTTON);
        assertThat(d.codeButtons()).isEqualTo(3);
        assertThat(d.effectiveMaxCodeAttempts()).isEqualTo(1);
        assertThat(d.codeCooldown()).isEqualTo(Duration.ofMinutes(5));
        assertThat(d.codeCooldownMax()).isEqualTo(Duration.ofHours(1));
        assertThat(d.codeCooldownThreshold()).isEqualTo(1);

        assertThat(DefaultAuthFlow.Options.builder().codeConfirmation(CodeConfirmation.TYPED)
                .build().effectiveMaxCodeAttempts()).isEqualTo(3);
        // an explicit value always wins over the per-mode default
        assertThat(DefaultAuthFlow.Options.builder().codeConfirmation(CodeConfirmation.TYPED)
                .maxCodeAttempts(2).build().effectiveMaxCodeAttempts()).isEqualTo(2);
    }

    @Test
    void cooldownSettingsAreValidated() {
        assertThatThrownBy(() -> DefaultAuthFlow.Options.builder()
                .codeCooldown(Duration.ofMinutes(10)).codeCooldownMax(Duration.ofMinutes(5)).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("codeCooldownMax");
        assertThatThrownBy(() -> DefaultAuthFlow.Options.builder().codeCooldownThreshold(0).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("codeCooldownThreshold");
        assertThatThrownBy(() -> DefaultAuthFlow.Options.builder().maxCodeAttempts(-1).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxCodeAttempts");
        assertThatThrownBy(() -> DefaultAuthFlow.Options.builder().codeConfirmation(null).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("codeConfirmation");
    }

    @Test
    void everyMessageKeyIsTranslatedIntoAllThreeLanguages() {
        for (io.github.dev_abdulhay.telegramauth.flow.FlowMessages.Key key
                : io.github.dev_abdulhay.telegramauth.flow.FlowMessages.Key.values()) {
            for (String lang : List.of("uz", "ru", "en")) {
                assertThat(io.github.dev_abdulhay.telegramauth.flow.FlowMessages.text(key, lang))
                        .as("%s/%s", key, lang).isNotBlank();
            }
        }
        assertThat(io.github.dev_abdulhay.telegramauth.flow.FlowMessages.text(
                io.github.dev_abdulhay.telegramauth.flow.FlowMessages.Key.CODE_WRONG, "uz")).contains("%d");
        assertThat(io.github.dev_abdulhay.telegramauth.flow.FlowMessages.text(
                io.github.dev_abdulhay.telegramauth.flow.FlowMessages.Key.TOO_MANY_ATTEMPTS, "ru")).contains("%d");
        assertThat(io.github.dev_abdulhay.telegramauth.flow.FlowMessages.text(
                io.github.dev_abdulhay.telegramauth.flow.FlowMessages.Key.CONFIRM_WARNING, "en")).contains("❌");
    }

    // --- requireApproval ---

    @Test
    void approvalFlowPromptsInsteadOfAutoApproving() throws Exception {
        Env e = env(legacy().requireApproval(true).build());
        var created = e.sessions().create("ip", "ua");

        e.module().getCommands().get("/start").accept(start(555L, created.rawToken(), "uz"));

        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(BaseAuthSession.Status.PENDING);
        assertThat(e.bot().last().text()).startsWith("Saytga kirishni tasdiqlaysizmi?");
        assertThat(e.bot().last().markup()).contains("tgauth:approve:" + created.rawToken());
        // the account is not created until the user actually confirms
        assertThat(e.users().findByTelegramId(555L)).isEmpty();

        e.module().getCallbackHandler().accept(callback(555L, "tgauth:approve:" + created.rawToken(), "uz"));

        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(BaseAuthSession.Status.APPROVED);
        assertThat(e.users().findByTelegramId(555L)).isPresent();
        assertThat(e.bot().edited).hasSize(1);
        assertThat(e.bot().edited.get(0)).contains("Tasdiqlandi");
    }

    @Test
    void confirmPromptShowsSessionIpAndDevice() throws Exception {
        Env e = env(legacy().requireApproval(true).build());
        var created = e.sessions().create("203.0.113.7", "Mozilla/5.0 (Macintosh) Safari/605");

        e.module().getCommands().get("/start").accept(start(555L, created.rawToken(), "uz"));

        assertThat(e.bot().last().text())
                .contains("IP: 203.0.113.7")
                .contains("Qurilma: Mozilla/5.0 (Macintosh) Safari/605");
    }

    @Test
    void rejectLeavesNoAccountBehind() throws Exception {
        Env e = env(legacy().requireApproval(true).build());
        var created = e.sessions().create("ip", "ua");

        e.module().getCommands().get("/start").accept(start(555L, created.rawToken(), "uz"));
        e.module().getCallbackHandler().accept(callback(555L, "tgauth:reject:" + created.rawToken(), "uz"));

        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(BaseAuthSession.Status.REJECTED);
        assertThat(e.users().findByTelegramId(555L)).isEmpty();
    }

    // --- private-chat guard ---

    @Test
    void startInGroupChatIsIgnored() throws Exception {
        Env e = env(legacy().build());
        var created = e.sessions().create("ip", "ua");

        // deep link pasted into a group: chat.id is the group, not the user
        e.module().getCommands().get("/start").accept(startIn(-100200L, 555L, created.rawToken(), "uz"));

        assertThat(e.bot().sent).isEmpty();
        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(BaseAuthSession.Status.PENDING);
        assertThat(e.users().findByTelegramId(-100200L)).isEmpty();
        assertThat(e.users().findByTelegramId(555L)).isEmpty();
    }

    @Test
    void callbackFromAnotherChatIsRefused() throws Exception {
        Env e = env(legacy().requireApproval(true).build());
        var created = e.sessions().create("ip", "ua");
        e.module().getCommands().get("/start").accept(start(555L, created.rawToken(), "uz"));

        // a bystander taps ✅ on a button that is not in their own private chat
        e.module().getCallbackHandler()
                .accept(callbackIn(-100200L, 777L, "tgauth:approve:" + created.rawToken(), "uz"));

        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(BaseAuthSession.Status.PENDING);
        assertThat(e.bot().answered).containsExactly("Kirish taqiqlangan.");
        assertThat(e.bot().edited).isEmpty();
    }

    // --- co-existing with host handlers ---

    @Test
    void foreignCallbackAndOrphanContactGoToTheHostFallback() throws Exception {
        Env e = env(legacy().requireApproval(true).requireContact(true).build());
        List<String> fallback = new ArrayList<>();
        e.module().fallback(u -> fallback.add(u.has("callback_query") ? "cb" : "msg"));

        e.module().getCallbackHandler().accept(callback(555L, "shop:buy:42", "uz"));
        e.module().getContactHandler().accept(contact(555L, 555L, "+998901234567"));

        assertThat(fallback).containsExactly("cb", "msg");
    }

    @Test
    void oversizedTokenFailsFastInsteadOfSilentlyBreakingTheKeyboard() throws Exception {
        Env e = env(legacy().requireApproval(true).build());
        DemoSession s = new DemoSession();
        String longToken = "t".repeat(60);
        s.setTokenHash(new TokenGenerator().hash(longToken));
        s.setStatus(BaseAuthSession.Status.PENDING);
        s.setCreatedAt(OffsetDateTime.now());
        s.setExpiresAt(OffsetDateTime.now().plusMinutes(3));
        e.sessionRepo().save(s);

        assertThatThrownBy(() -> e.module().getCommands().get("/start").accept(start(555L, longToken, "uz")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Telegram allows 64");
    }

    @Test
    void rejectCallbackRejectsSession() throws Exception {
        Env e = env(legacy().requireApproval(true).build());
        var created = e.sessions().create("ip", "ua");

        e.module().getCommands().get("/start").accept(start(555L, created.rawToken(), "uz"));
        e.module().getCallbackHandler().accept(callback(555L, "tgauth:reject:" + created.rawToken(), "uz"));

        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(BaseAuthSession.Status.REJECTED);
        assertThat(e.bot().edited.get(0)).contains("rad etildi");
    }

    @Test
    void foreignCallbackDataIsIgnored() throws Exception {
        Env e = env(legacy().requireApproval(true).build());
        var created = e.sessions().create("ip", "ua");

        e.module().getCallbackHandler().accept(callback(555L, "other:whatever", "uz"));

        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(BaseAuthSession.Status.PENDING);
        assertThat(e.bot().edited).isEmpty();
    }

    // --- requireContact ---

    @Test
    void contactStepAsksThenRegistersOwnPhoneAndApproves() throws Exception {
        Env e = env(legacy().requireContact(true).build());
        var created = e.sessions().create("ip", "ua");

        e.module().getCommands().get("/start").accept(start(555L, created.rawToken(), "uz"));
        assertThat(e.bot().last().markup()).contains("request_contact");
        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(BaseAuthSession.Status.PENDING);

        e.module().getContactHandler().accept(contact(555L, 555L, "+998901234567"));

        assertThat(e.users().findByTelegramId(555L)).isPresent();
        assertThat(e.users().findByTelegramId(555L).get().getPhone()).isEqualTo("998901234567");
        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(BaseAuthSession.Status.APPROVED);
    }

    @Test
    void spoofedContactIsRefusedAndRetryWorks() throws Exception {
        Env e = env(legacy().requireContact(true).build());
        var created = e.sessions().create("ip", "ua");

        e.module().getCommands().get("/start").accept(start(555L, created.rawToken(), "uz"));
        e.module().getContactHandler().accept(contact(555L, 999L, "+998900000000"));

        assertThat(e.bot().last().text()).isEqualTo("Iltimos, faqat o'z raqamingizni ulashing.");
        assertThat(e.users().findByTelegramId(555L)).isEmpty();
        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(BaseAuthSession.Status.PENDING);

        // pending correlation survives, so the user's own contact still completes the login
        e.module().getContactHandler().accept(contact(555L, 555L, "+998901234567"));
        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(BaseAuthSession.Status.APPROVED);
    }

    @Test
    void skipContinuesWithoutPhone() throws Exception {
        Env e = env(legacy().requireContact(true).build());
        var created = e.sessions().create("ip", "ua");

        e.module().getCommands().get("/start").accept(start(555L, created.rawToken(), "uz"));
        e.module().getCommands().get("/skip").accept(skip(555L));

        assertThat(e.users().findByTelegramId(555L)).isPresent();
        assertThat(e.users().findByTelegramId(555L).get().getPhone()).isNull();
        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(BaseAuthSession.Status.APPROVED);
    }

    // --- blocked users ---

    @Test
    void blockedUserIsDeniedAndNeverReactivated() throws Exception {
        Env e = env(legacy().build());
        DemoUser u = e.users().register(555L, null, "Ali", null, "ali", "uz");
        u.setStatus(BaseTelegramUser.Status.BLOCKED);
        e.userRepo().save(u);

        var created = e.sessions().create("ip", "ua");
        e.module().getCommands().get("/start").accept(start(555L, created.rawToken(), "uz"));

        assertThat(e.bot().last().text()).isEqualTo("Kirish taqiqlangan.");
        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(BaseAuthSession.Status.PENDING);
        assertThat(e.users().findByTelegramId(555L).get().getStatus()).isEqualTo(BaseTelegramUser.Status.BLOCKED);
    }

    @Test
    void registerNeverLiftsBlockAndKeepsStoredPhone() {
        Env e = env(legacy().build());
        e.users().register(7L, "998901112233", "Ali", null, "ali", "uz");

        // metadata-only re-login must not erase the stored phone
        e.users().register(7L, null, "Ali", null, "ali", "uz");
        assertThat(e.users().findByTelegramId(7L).get().getPhone()).isEqualTo("998901112233");

        DemoUser u = e.users().findByTelegramId(7L).get();
        u.setStatus(BaseTelegramUser.Status.BLOCKED);
        e.userRepo().save(u);

        DemoUser after = e.users().register(7L, null, "Ali", null, "ali", "uz");
        assertThat(after.getStatus()).isEqualTo(BaseTelegramUser.Status.BLOCKED);
    }

    // --- localization ---

    @Test
    void invalidLinkMessageIsLocalizedWithUzFallback() throws Exception {
        Env e = env(legacy().build());

        e.module().getCommands().get("/start").accept(start(1L, "bogus", "ru"));
        assertThat(e.bot().last().text()).isEqualTo("Ссылка недействительна или устарела.");

        e.module().getCommands().get("/start").accept(start(2L, "bogus", "en"));
        assertThat(e.bot().last().text()).isEqualTo("The link is invalid or expired.");

        e.module().getCommands().get("/start").accept(start(3L, "bogus", "de"));
        assertThat(e.bot().last().text()).isEqualTo("Havola yaroqsiz yoki muddati tugagan.");
    }

    // --- session service hardening ---

    @Test
    void approvePersistsPayloadOnSessionRow() throws Exception {
        Env e = env(legacy().build());
        var created = e.sessions().create("ip", "ua");

        e.module().getCommands().get("/start").accept(start(555L, created.rawToken(), "uz"));

        assertThat(((BaseAuthSession) created.entity()).getApprovePayload()).contains("\"phone\"");
    }

    @Test
    void createIsRateLimitedPerIp() {
        RecordingBot bot = new RecordingBot();
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "demo_bot")
                .bot(bot)
                .maxPendingPerIp(2)
                .build();
        DemoSessionService sessions = new DemoSessionService(new StubSessionRepo(), new TokenGenerator(), module);

        sessions.create("9.9.9.9", "ua");
        sessions.create("9.9.9.9", "ua");
        assertThatThrownBy(() -> sessions.create("9.9.9.9", "ua"))
                .isInstanceOf(SessionRateLimitException.class);
        // other IPs unaffected
        sessions.create("8.8.8.8", "ua");
    }

    @Test
    void overduePendingSessionsDoNotHoldTheIpLimit() {
        RecordingBot bot = new RecordingBot();
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "demo_bot")
                .bot(bot)
                .maxPendingPerIp(1)
                .build();
        DemoSessionService sessions = new DemoSessionService(new StubSessionRepo(), new TokenGenerator(), module);

        var first = sessions.create("9.9.9.9", "ua");
        assertThatThrownBy(() -> sessions.create("9.9.9.9", "ua"))
                .isInstanceOf(SessionRateLimitException.class);

        // still PENDING because the sweeper has not run yet — an overdue row must not lock the IP out
        ((BaseAuthSession) first.entity()).setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        sessions.create("9.9.9.9", "ua");
    }

    @Test
    void awaitingCodeSessionsCountTowardTheIpLimit() {
        RecordingBot bot = new RecordingBot();
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "demo_bot")
                .bot(bot)
                .maxPendingPerIp(1)
                .build();
        DemoSessionService sessions = new DemoSessionService(new StubSessionRepo(), new TokenGenerator(), module);

        var first = sessions.create("9.9.9.9", "ua");
        ((BaseAuthSession) first.entity()).setStatus(BaseAuthSession.Status.AWAITING_CODE);

        // half-finished logins must still hold their slot, else the limit is trivially bypassed
        assertThatThrownBy(() -> sessions.create("9.9.9.9", "ua"))
                .isInstanceOf(SessionRateLimitException.class);
    }

    @Test
    void sweepExpiresOverdueAwaitingCodeSessions() {
        RecordingBot bot = new RecordingBot();
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "demo_bot").bot(bot).build();
        StubSessionRepo repo = new StubSessionRepo();
        DemoSessionService sessions = new DemoSessionService(repo, new TokenGenerator(), module);

        DemoSession stuck = new DemoSession();
        stuck.setTokenHash("h-awaiting");
        stuck.setStatus(BaseAuthSession.Status.AWAITING_CODE);
        stuck.setCreatedAt(OffsetDateTime.now().minusMinutes(10));
        stuck.setExpiresAt(OffsetDateTime.now().minusMinutes(5));
        repo.save(stuck);

        sessions.sweepExpired();

        assertThat(repo.findByTokenHash("h-awaiting").orElseThrow().getStatus())
                .isEqualTo(BaseAuthSession.Status.EXPIRED);
    }

    @Test
    void sweepDeletesOldTerminalSessionsAndKeepsRecentOnes() {
        RecordingBot bot = new RecordingBot();
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "demo_bot")
                .bot(bot)
                .sessionRetention(Duration.ofHours(1))
                .build();
        StubSessionRepo repo = new StubSessionRepo();
        DemoSessionService sessions = new DemoSessionService(repo, new TokenGenerator(), module);

        DemoSession old = new DemoSession();
        old.setTokenHash("h-old");
        old.setStatus(BaseAuthSession.Status.APPROVED);
        old.setCreatedAt(OffsetDateTime.now().minusHours(5));
        old.setExpiresAt(OffsetDateTime.now().minusHours(4));
        repo.save(old);

        DemoSession recent = new DemoSession();
        recent.setTokenHash("h-recent");
        recent.setStatus(BaseAuthSession.Status.REJECTED);
        recent.setCreatedAt(OffsetDateTime.now());
        recent.setExpiresAt(OffsetDateTime.now().plusMinutes(3));
        repo.save(recent);

        sessions.sweepExpired();

        assertThat(repo.findByTokenHash("h-old")).isEmpty();
        assertThat(repo.findByTokenHash("h-recent")).isPresent();
    }
}
