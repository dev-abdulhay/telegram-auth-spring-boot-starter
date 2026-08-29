package com.example.demo;

import com.example.demo.DefaultAuthFlowOptionsTest.RecordingBot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dev_abdulhay.telegramauth.api.dto.AuthApproveResult;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession.Status;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.flow.CodeConfirmation;
import io.github.dev_abdulhay.telegramauth.flow.DefaultAuthFlow;
import io.github.dev_abdulhay.telegramauth.security.ConfirmCode;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the number-matching confirmation step: the {@code AWAITING_CODE} stage,
 * both collection modes, the deliberate absence of a "try again" affordance, and
 * the cooldown that keeps a rejected session from being a mere inconvenience.
 */
class CodeConfirmationFlowTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final long USER = 555L;

    record Env(RecordingBot bot, TelegramBotModule module, DemoUserService users,
               DemoSessionService sessions) {}

    private static Env env(DefaultAuthFlow.Options opts) {
        RecordingBot bot = new RecordingBot();
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "demo_bot")
                .bot(bot)
                .approveHandler((info, ctx) -> new AuthApproveResult(Map.of("tgId", info.telegramId())))
                .build();
        DemoUserService users = new DemoUserService(new StubUserRepo());
        DemoSessionService sessions = new DemoSessionService(new StubSessionRepo(), new TokenGenerator(), module);
        new DefaultAuthFlow<>(users, sessions, module, opts);
        return new Env(bot, module, users, sessions);
    }

    private static DefaultAuthFlow.Options.Builder buttons() {
        return DefaultAuthFlow.Options.builder().codeConfirmation(CodeConfirmation.BUTTON);
    }

    private static DefaultAuthFlow.Options.Builder typed() {
        return DefaultAuthFlow.Options.builder().codeConfirmation(CodeConfirmation.TYPED);
    }

    // --- update builders ---

    private static JsonNode start(String raw) throws Exception {
        return M.readTree(M.writeValueAsString(Map.of("message", Map.of(
                "text", "/start " + raw,
                "chat", Map.of("id", USER),
                "from", Map.of("id", USER, "first_name", "Ali", "language_code", "uz")))));
    }

    private static JsonNode text(String body) throws Exception {
        return M.readTree(M.writeValueAsString(Map.of("message", Map.of(
                "text", body,
                "chat", Map.of("id", USER),
                "from", Map.of("id", USER, "first_name", "Ali", "language_code", "uz")))));
    }

    private static JsonNode callback(String data) throws Exception {
        return M.readTree(M.writeValueAsString(Map.of("callback_query", Map.of(
                "id", "cb1",
                "data", data,
                "from", Map.of("id", USER, "first_name", "Ali", "language_code", "uz"),
                "message", Map.of("chat", Map.of("id", USER), "message_id", 42)))));
    }

    // --- helpers ---

    private static int codeOf(Env e, String rawToken) {
        return ConfirmCode.of(e.sessions().hash(rawToken));
    }

    /** The two-digit numbers offered by an inline keyboard, in display order. */
    private static List<Integer> offeredCodes(String markup) throws Exception {
        List<Integer> codes = new ArrayList<>();
        for (JsonNode row : M.readTree(markup).path("inline_keyboard")) {
            for (JsonNode button : row) {
                String[] parts = button.path("callback_data").asText("").split(":", 3);
                if (parts.length == 3 && parts[1].length() == 3 && parts[1].charAt(0) == 'c') {
                    codes.add(Integer.parseInt(parts[1].substring(1)));
                }
            }
        }
        return codes;
    }

    private static String guessData(int code, String rawToken) {
        return "tgauth:c" + String.format("%02d", code) + ":" + rawToken;
    }

    /** Any number that is not the real one, so the guess is guaranteed wrong. */
    private static int wrongCode(int realCode) {
        return (realCode + 1) % 100;
    }

    // --- BUTTON mode ---

    @Test
    void approvePressOnlyUnlocksTheCodeQuestionAndCreatesNoAccount() throws Exception {
        Env e = env(buttons().requireApproval(true).build());
        var created = e.sessions().create("ip", "ua");

        e.module().getCommands().get("/start").accept(start(created.rawToken()));
        e.module().getCallbackHandler().accept(callback("tgauth:approve:" + created.rawToken()));

        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(Status.AWAITING_CODE);
        // ✅ used to be the whole confirmation; now it must not register anything
        assertThat(e.users().findByTelegramId(USER)).isEmpty();
        assertThat(e.bot().last().text()).startsWith("Brauzeringiz ekranida ko'rsatilgan raqamni tanlang.");
        assertThat(offeredCodes(e.bot().last().markup())).contains(codeOf(e, created.rawToken()));
    }

    @Test
    void correctNumberCompletesTheTwoTouchLogin() throws Exception {
        Env e = env(buttons().requireApproval(true).build());
        var created = e.sessions().create("ip", "ua");

        e.module().getCommands().get("/start").accept(start(created.rawToken()));
        e.module().getCallbackHandler().accept(callback("tgauth:approve:" + created.rawToken()));
        e.module().getCallbackHandler()
                .accept(callback(guessData(codeOf(e, created.rawToken()), created.rawToken())));

        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(Status.APPROVED);
        assertThat(e.users().findByTelegramId(USER)).isPresent();
        assertThat(e.bot().edited).last().asString().contains("Tasdiqlandi");
    }

    @Test
    void oneWrongTapEndsTheLoginAndLeavesNoAccount() throws Exception {
        Env e = env(buttons().requireApproval(true).build());
        var created = e.sessions().create("ip", "ua");

        e.module().getCommands().get("/start").accept(start(created.rawToken()));
        e.module().getCallbackHandler().accept(callback("tgauth:approve:" + created.rawToken()));
        e.module().getCallbackHandler()
                .accept(callback(guessData(wrongCode(codeOf(e, created.rawToken())), created.rawToken())));

        // no "try again": three buttons plus retries would hand the attacker every option
        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(Status.REJECTED);
        assertThat(e.users().findByTelegramId(USER)).isEmpty();
    }

    @Test
    void decoysAreDistinctAndAlwaysIncludeTheRealCodeExactlyOnce() throws Exception {
        Env e = env(buttons().requireApproval(true).codeButtons(10).build());

        for (int i = 0; i < 200; i++) {
            var created = e.sessions().create("ip", "ua");
            int real = codeOf(e, created.rawToken());

            e.module().getCommands().get("/start").accept(start(created.rawToken()));
            e.module().getCallbackHandler().accept(callback("tgauth:approve:" + created.rawToken()));

            List<Integer> offered = offeredCodes(e.bot().last().markup());
            assertThat(offered).hasSize(10).doesNotHaveDuplicates();
            assertThat(offered.stream().filter(c -> c == real).count()).isEqualTo(1);
            assertThat(offered).allSatisfy(c -> assertThat(c).isBetween(0, 99));

            e.module().getCallbackHandler().accept(callback("tgauth:reject:" + created.rawToken()));
        }
    }

    @Test
    void withoutTheApprovalStepTheCodeQuestionCarriesTheSessionDetailsAndWarning() throws Exception {
        Env e = env(buttons().build());
        var created = e.sessions().create("203.0.113.7", "Mozilla/5.0 (Macintosh) Safari/605");

        e.module().getCommands().get("/start").accept(start(created.rawToken()));

        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(Status.AWAITING_CODE);
        assertThat(e.bot().last().text())
                .contains("IP: 203.0.113.7")
                .contains("Qurilma: Mozilla/5.0 (Macintosh) Safari/605")
                .contains("❌");
        assertThat(e.users().findByTelegramId(USER)).isEmpty();
    }

    @Test
    void tappingApproveTwiceReissuesTheQuestionInsteadOfClaimingTheSessionExpired() throws Exception {
        Env e = env(buttons().requireApproval(true).build());
        var created = e.sessions().create("ip", "ua");

        e.module().getCommands().get("/start").accept(start(created.rawToken()));
        e.module().getCallbackHandler().accept(callback("tgauth:approve:" + created.rawToken()));
        e.module().getCallbackHandler().accept(callback("tgauth:approve:" + created.rawToken()));

        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(Status.AWAITING_CODE);
        assertThat(e.bot().last().text()).startsWith("Brauzeringiz ekranida ko'rsatilgan raqamni tanlang.");
        assertThat(e.bot().edited).last().asString().doesNotContain("muddati tugagan");

        // and the login still finishes normally
        e.module().getCallbackHandler()
                .accept(callback(guessData(codeOf(e, created.rawToken()), created.rawToken())));
        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(Status.APPROVED);
    }

    @Test
    void anExtraAttemptReshufflesTheKeyboardBeforeTheSecondTry() throws Exception {
        Env e = env(buttons().requireApproval(true).codeButtons(10).maxCodeAttempts(2).build());
        var created = e.sessions().create("ip", "ua");
        int real = codeOf(e, created.rawToken());

        e.module().getCommands().get("/start").accept(start(created.rawToken()));
        e.module().getCallbackHandler().accept(callback("tgauth:approve:" + created.rawToken()));
        List<Integer> first = offeredCodes(e.bot().last().markup());

        e.module().getCallbackHandler().accept(callback(guessData(wrongCode(real), created.rawToken())));

        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(Status.AWAITING_CODE);
        List<Integer> second = offeredCodes(e.bot().last().markup());
        assertThat(second).hasSize(10).doesNotHaveDuplicates().contains(real);
        // reusing the same set would let the attacker eliminate candidates for free
        assertThat(second).isNotEqualTo(first);

        e.module().getCallbackHandler().accept(callback(guessData(wrongCode(real), created.rawToken())));
        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(Status.REJECTED);
    }

    @Test
    void offModeKeepsThePreviousOneTouchBehaviour() throws Exception {
        Env e = env(DefaultAuthFlow.Options.builder()
                .codeConfirmation(CodeConfirmation.OFF).requireApproval(true).build());
        var created = e.sessions().create("ip", "ua");

        e.module().getCommands().get("/start").accept(start(created.rawToken()));
        e.module().getCallbackHandler().accept(callback("tgauth:approve:" + created.rawToken()));

        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(Status.APPROVED);
        assertThat(e.users().findByTelegramId(USER)).isPresent();
    }

    // --- TYPED mode ---

    @Test
    void typedModeApprovesOnTheCorrectNumber() throws Exception {
        Env e = env(typed().build());
        var created = e.sessions().create("ip", "ua");

        e.module().getCommands().get("/start").accept(start(created.rawToken()));
        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(Status.AWAITING_CODE);

        e.module().getTextHandler().accept(text(String.format("%02d", codeOf(e, created.rawToken()))));

        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(Status.APPROVED);
        assertThat(e.users().findByTelegramId(USER)).isPresent();
    }

    @Test
    void typedModeRejectsOnlyAfterTheThirdWrongNumber() throws Exception {
        Env e = env(typed().build());
        var created = e.sessions().create("ip", "ua");
        int wrong = wrongCode(codeOf(e, created.rawToken()));

        e.module().getCommands().get("/start").accept(start(created.rawToken()));

        e.module().getTextHandler().accept(text(String.valueOf(wrong)));
        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(Status.AWAITING_CODE);
        assertThat(e.bot().last().text()).contains("Qolgan urinish: 2");

        e.module().getTextHandler().accept(text(String.valueOf(wrong)));
        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(Status.AWAITING_CODE);
        assertThat(e.bot().last().text()).contains("Qolgan urinish: 1");

        e.module().getTextHandler().accept(text(String.valueOf(wrong)));
        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(Status.REJECTED);
        assertThat(e.users().findByTelegramId(USER)).isEmpty();
    }

    @Test
    void textWithNoCodeStepInProgressGoesToTheHostFallback() throws Exception {
        Env e = env(typed().build());
        List<String> fallback = new ArrayList<>();
        e.module().fallback(u -> fallback.add(u.path("message").path("text").asText()));

        e.module().getTextHandler().accept(text("42"));
        e.module().getTextHandler().accept(text("hello there"));

        assertThat(fallback).containsExactly("42", "hello there");
    }

    @Test
    void unregisteredCommandsGoToTheFallbackAndBurnNoAttempt() throws Exception {
        Env e = env(typed().build());
        List<String> fallback = new ArrayList<>();
        e.module().fallback(u -> fallback.add(u.path("message").path("text").asText()));
        var created = e.sessions().create("ip", "ua");
        e.module().getCommands().get("/start").accept(start(created.rawToken()));

        e.module().getTextHandler().accept(text("/help"));

        assertThat(fallback).containsExactly("/help");
        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(Status.AWAITING_CODE);
        // all three attempts must still be there
        assertThat(e.bot().last().text()).doesNotContain("Qolgan urinish");
    }

    @Test
    void nonNumericTextBurnsNoAttempt() throws Exception {
        Env e = env(typed().maxCodeAttempts(1).build());
        var created = e.sessions().create("ip", "ua");
        e.module().getCommands().get("/start").accept(start(created.rawToken()));

        e.module().getTextHandler().accept(text("salom"));

        assertThat(e.bot().last().text()).isEqualTo("00 dan 99 gacha bo'lgan raqam yuboring.");
        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(Status.AWAITING_CODE);

        // the single attempt was never spent, so the correct number still works
        e.module().getTextHandler().accept(text(String.format("%02d", codeOf(e, created.rawToken()))));
        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(Status.APPROVED);
    }

    @Test
    void aUserBlockedMidFlowCannotFinishTheTypedCodeStep() throws Exception {
        Env e = env(typed().build());
        // the user exists from an earlier login, so /start passes the entry check
        e.users().register(USER, null, "Ali", null, null, "uz");
        var created = e.sessions().create("ip", "ua");
        e.module().getCommands().get("/start").accept(start(created.rawToken()));
        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(Status.AWAITING_CODE);

        // admin blocks them while the code question is on screen
        e.users().findByTelegramId(USER).orElseThrow()
                .setStatus(BaseTelegramUser.Status.BLOCKED);

        e.module().getTextHandler().accept(text(String.format("%02d", codeOf(e, created.rawToken()))));

        assertThat(((BaseAuthSession) created.entity()).getStatus()).isNotEqualTo(Status.APPROVED);
        assertThat(e.bot().last().text()).isEqualTo("Kirish taqiqlangan.");
    }

    // --- cooldown ---

    @Test
    void aFailedCodeCoolsTheUserDownBeforeTheyCanTryAnotherLogin() throws Exception {
        Env e = env(buttons().requireApproval(true).build());
        var first = e.sessions().create("ip", "ua");

        e.module().getCommands().get("/start").accept(start(first.rawToken()));
        e.module().getCallbackHandler().accept(callback("tgauth:approve:" + first.rawToken()));
        e.module().getCallbackHandler()
                .accept(callback(guessData(wrongCode(codeOf(e, first.rawToken())), first.rawToken())));

        // rejecting the session alone would not help: the attacker just opens a new one
        var second = e.sessions().create("ip", "ua");
        e.module().getCommands().get("/start").accept(start(second.rawToken()));

        assertThat(e.bot().last().text()).startsWith("Juda ko'p noto'g'ri urinish.");
        assertThat(((BaseAuthSession) second.entity()).getStatus()).isEqualTo(Status.PENDING);
    }

    @Test
    void rejectStillWorksWhileTheUserIsCoolingDown() throws Exception {
        Env e = env(buttons().requireApproval(true).build());
        var first = e.sessions().create("ip", "ua");

        e.module().getCommands().get("/start").accept(start(first.rawToken()));
        e.module().getCallbackHandler().accept(callback("tgauth:approve:" + first.rawToken()));
        e.module().getCallbackHandler()
                .accept(callback(guessData(wrongCode(codeOf(e, first.rawToken())), first.rawToken())));

        var second = e.sessions().create("ip", "ua");
        e.module().getCallbackHandler().accept(callback("tgauth:reject:" + second.rawToken()));

        // saying "this was not me" must never be blocked
        assertThat(((BaseAuthSession) second.entity()).getStatus()).isEqualTo(Status.REJECTED);
    }

    @Test
    void aSuccessfulLoginClearsTheStrikeLadder() throws Exception {
        // threshold 2 leaves the first failure unpunished, which is what lets this
        // test observe the counter rather than the cooldown
        Env e = env(buttons().requireApproval(true).codeCooldownThreshold(2).build());

        var a = e.sessions().create("ip", "ua");
        e.module().getCommands().get("/start").accept(start(a.rawToken()));
        e.module().getCallbackHandler().accept(callback("tgauth:approve:" + a.rawToken()));
        e.module().getCallbackHandler()
                .accept(callback(guessData(wrongCode(codeOf(e, a.rawToken())), a.rawToken())));

        var b = e.sessions().create("ip", "ua");
        e.module().getCommands().get("/start").accept(start(b.rawToken()));
        e.module().getCallbackHandler().accept(callback("tgauth:approve:" + b.rawToken()));
        e.module().getCallbackHandler().accept(callback(guessData(codeOf(e, b.rawToken()), b.rawToken())));
        assertThat(((BaseAuthSession) b.entity()).getStatus()).isEqualTo(Status.APPROVED);

        // the next failure must count as the first one again, so no cooldown yet
        var c = e.sessions().create("ip", "ua");
        e.module().getCommands().get("/start").accept(start(c.rawToken()));
        e.module().getCallbackHandler().accept(callback("tgauth:approve:" + c.rawToken()));
        e.module().getCallbackHandler()
                .accept(callback(guessData(wrongCode(codeOf(e, c.rawToken())), c.rawToken())));

        var d = e.sessions().create("ip", "ua");
        e.module().getCommands().get("/start").accept(start(d.rawToken()));
        assertThat(e.bot().last().text()).doesNotStartWith("Juda ko'p noto'g'ri urinish.");
        assertThat(e.bot().last().markup()).contains("tgauth:approve:" + d.rawToken());
    }

    @Test
    void allThreeTypedGuessesOnOneLoginCountAsASingleStrike() throws Exception {
        Env e = env(typed().codeCooldownThreshold(2).codeCooldown(Duration.ofMinutes(5)).build());

        var first = e.sessions().create("ip", "ua");
        int wrong = wrongCode(codeOf(e, first.rawToken()));
        e.module().getCommands().get("/start").accept(start(first.rawToken()));
        for (int i = 0; i < 3; i++) {
            e.module().getTextHandler().accept(text(String.valueOf(wrong)));
        }
        assertThat(((BaseAuthSession) first.entity()).getStatus()).isEqualTo(Status.REJECTED);

        // one dead login is one strike, not three — still below the threshold
        var second = e.sessions().create("ip", "ua");
        e.module().getCommands().get("/start").accept(start(second.rawToken()));
        assertThat(((BaseAuthSession) second.entity()).getStatus()).isEqualTo(Status.AWAITING_CODE);

        int wrong2 = wrongCode(codeOf(e, second.rawToken()));
        for (int i = 0; i < 3; i++) {
            e.module().getTextHandler().accept(text(String.valueOf(wrong2)));
        }

        // the second dead login crosses the threshold
        var third = e.sessions().create("ip", "ua");
        e.module().getCommands().get("/start").accept(start(third.rawToken()));
        assertThat(e.bot().last().text()).startsWith("Juda ko'p noto'g'ri urinish.");
        assertThat(((BaseAuthSession) third.entity()).getStatus()).isEqualTo(Status.PENDING);
    }
}
