package com.example.demo;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBot;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import io.github.dev_abdulhay.telegramauth.service.AuthEvent;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SessionServiceTest {

    private TelegramBotModule module() {
        TelegramBot fake = new TelegramBot(HttpClient.newHttpClient(), "x") {
            @Override public void sendMessage(long chatId, String text) { }
        };
        return TelegramBotModule.builder("123:ABC", "demo_bot")
                .bot(fake)
                .sessionTtl(Duration.ofMinutes(3))
                .approveHandler((info, ctx) -> new io.github.dev_abdulhay.telegramauth.api.dto.AuthApproveResult(
                        Map.of("tgId", info.telegramId())))
                .build();
    }

    // In-memory stub repo so this stays a fast unit test.
    @Test
    void approvePublishesPayloadOnTheModuleBus() {
        TelegramBotModule module = module();
        StubSessionRepo repo = new StubSessionRepo();
        DemoSessionService svc = new DemoSessionService(repo, new TokenGenerator(), module);

        var created = svc.create("1.2.3.4", "JUnit");
        String hash = svc.hash(created.rawToken());

        AtomicReference<AuthEvent> got = new AtomicReference<>();
        module.getBus().subscribe(hash, got::set);

        DemoUser u = new DemoUser();
        u.setTelegramId(99L);
        svc.approve(hash, u);

        assertThat(got.get()).isNotNull();
        assertThat(got.get().type()).isEqualTo(AuthEvent.Type.APPROVED);
        assertThat(got.get().payload()).containsEntry("tgId", 99L);
        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(BaseAuthSession.Status.APPROVED);
    }

    @Test
    void awaitCodeMovesPendingToAwaitingCodeWithoutCallingTheHostHandler() {
        AtomicReference<Boolean> handlerCalled = new AtomicReference<>(false);
        TelegramBot fake = new TelegramBot(HttpClient.newHttpClient(), "x") {
            @Override public void sendMessage(long chatId, String text) { }
        };
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "demo_bot")
                .bot(fake)
                .approveHandler((info, ctx) -> {
                    handlerCalled.set(true);
                    return new io.github.dev_abdulhay.telegramauth.api.dto.AuthApproveResult(Map.of());
                })
                .build();
        DemoSessionService svc = new DemoSessionService(new StubSessionRepo(), new TokenGenerator(), module);

        var created = svc.create("1.2.3.4", "JUnit");
        String hash = svc.hash(created.rawToken());

        AtomicReference<AuthEvent> got = new AtomicReference<>();
        module.getBus().subscribe(hash, got::set);

        assertThat(svc.awaitCode(hash)).isTrue();
        assertThat(((BaseAuthSession) created.entity()).getStatus())
                .isEqualTo(BaseAuthSession.Status.AWAITING_CODE);
        assertThat(got.get().type()).isEqualTo(AuthEvent.Type.AWAITING_CODE);
        // the host learns about a login exactly once, at final approval
        assertThat(handlerCalled.get()).isFalse();

        // not repeatable: the session is no longer PENDING
        assertThat(svc.awaitCode(hash)).isFalse();
    }

    @Test
    void approveAndRejectAlsoWorkFromAwaitingCode() {
        TelegramBotModule module = module();
        DemoSessionService svc = new DemoSessionService(new StubSessionRepo(), new TokenGenerator(), module);

        var a = svc.create("1.2.3.4", "JUnit");
        String hashA = svc.hash(a.rawToken());
        svc.awaitCode(hashA);
        DemoUser u = new DemoUser();
        u.setTelegramId(99L);
        assertThat(svc.approve(hashA, u)).isTrue();
        assertThat(((BaseAuthSession) a.entity()).getStatus()).isEqualTo(BaseAuthSession.Status.APPROVED);

        var b = svc.create("1.2.3.4", "JUnit");
        String hashB = svc.hash(b.rawToken());
        svc.awaitCode(hashB);
        assertThat(svc.reject(hashB)).isTrue();
        assertThat(((BaseAuthSession) b.entity()).getStatus()).isEqualTo(BaseAuthSession.Status.REJECTED);
    }
}
