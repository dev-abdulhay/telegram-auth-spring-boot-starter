package io.github.dev_abdulhay.telegramauth.miniapp;

import com.example.demo.DemoUser;
import com.example.demo.DemoUserService;
import com.example.demo.StubUserRepo;
import io.github.dev_abdulhay.telegramauth.api.TelegramAuthApproveHandler;
import io.github.dev_abdulhay.telegramauth.api.dto.AuthApproveResult;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBot;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MiniAppLoginServiceTest {

    private static final String TOKEN = "123:ABC";

    private final Instant now = Instant.parse("2026-11-13T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final StubUserRepo repo = new StubUserRepo();
    private final AtomicInteger calls = new AtomicInteger();

    private MiniAppLoginService<DemoUser> service(TelegramAuthApproveHandler handler) {
        return service(handler, new InMemoryInitDataReplayStore(clock));
    }

    private MiniAppLoginService<DemoUser> service(TelegramAuthApproveHandler handler, InitDataReplayStore store) {
        TelegramBotModule module = TelegramBotModule.builder(TOKEN, "demo_bot")
                .bot(new TelegramBot(HttpClient.newHttpClient(), TOKEN))
                .miniApp(MiniAppOptions.defaults())
                .approveHandler((info, ctx) -> {
                    calls.incrementAndGet();
                    return handler.onApprove(info, ctx);
                })
                .build();
        return new MiniAppLoginService<>(module, new DemoUserService(repo), store, clock);
    }

    private static AuthApproveResult ok(long telegramId) {
        return new AuthApproveResult(Map.of("tgId", telegramId));
    }

    private String initData(long telegramId) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("user", "{\"id\":" + telegramId + ",\"first_name\":\"Ali\",\"username\":\"ali\"}");
        f.put("auth_date", String.valueOf(now.getEpochSecond()));
        return MiniAppTestSigner.sign(TOKEN, f);
    }

    private static void assertError(Runnable call, MiniAppError expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(MiniAppAuthException.class,
                e -> assertThat(e.error()).isEqualTo(expected));
    }

    @Test
    void handlerFailureDoesNotBurnHash() {
        MiniAppLoginService<DemoUser> service = service((info, ctx) -> {
            if (calls.get() == 1) {
                throw new RuntimeException("host handler down");
            }
            return ok(info.telegramId());
        });
        String raw = initData(42);

        assertThatThrownBy(() -> service.login(raw, "1.1.1.1", "ua", null))
                .isInstanceOf(RuntimeException.class).hasMessage("host handler down");

        assertThat(service.login(raw, "1.1.1.1", "ua", null)).containsEntry("tgId", 42L);

        // a successful login does burn it
        assertError(() -> service.login(raw, "1.1.1.1", "ua", null), MiniAppError.INIT_DATA_EXPIRED);
    }

    @Test
    void releaseFailureIsSuppressedAndOriginalRethrown() {
        InitDataReplayStore brokenRelease = new InitDataReplayStore() {
            @Override
            public boolean claim(String hash, Instant expiresAt) {
                return true;
            }

            @Override
            public void release(String hash) {
                throw new IllegalStateException("store down");
            }
        };
        MiniAppLoginService<DemoUser> service = service((info, ctx) -> {
            throw new RuntimeException("host handler down");
        }, brokenRelease);

        assertThatThrownBy(() -> service.login(initData(42), "1.1.1.1", "ua", null))
                .isInstanceOf(RuntimeException.class).hasMessage("host handler down")
                .satisfies(e -> assertThat(e.getSuppressed()).singleElement()
                        .isInstanceOf(IllegalStateException.class)
                        .extracting(Throwable::getMessage).isEqualTo("store down"));
    }

    @Test
    void blockedUserHandlerNotCalled() {
        DemoUser blocked = new DemoUser();
        blocked.setTelegramId(77L);
        blocked.setStatus(BaseTelegramUser.Status.BLOCKED);
        repo.save(blocked);
        MiniAppLoginService<DemoUser> service = service((info, ctx) -> ok(info.telegramId()));
        String raw = initData(77);

        assertError(() -> service.login(raw, "1.1.1.1", "ua", null), MiniAppError.USER_BLOCKED);
        // the claim was released, so a retry reaches the BLOCKED check again rather than reading as a replay
        assertError(() -> service.login(raw, "1.1.1.1", "ua", null), MiniAppError.USER_BLOCKED);
        assertThat(calls).hasValue(0);
    }

    @Test
    void concurrentLoginsWithSameInitDataOnlyOneSucceeds() throws Exception {
        CountDownLatch inHandler = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        MiniAppLoginService<DemoUser> service = service((info, ctx) -> {
            inHandler.countDown();
            try {
                if (!proceed.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test latch timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return ok(info.telegramId());
        });
        String raw = initData(42);

        CompletableFuture<Map<String, Object>> first =
                CompletableFuture.supplyAsync(() -> service.login(raw, "1.1.1.1", "ua", null));
        assertThat(inHandler.await(5, TimeUnit.SECONDS)).isTrue();

        // the first login is still inside the handler: the second must not get through
        assertError(() -> service.login(raw, "2.2.2.2", "ua", null), MiniAppError.INIT_DATA_EXPIRED);

        proceed.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS)).containsEntry("tgId", 42L);
        assertThat(calls).hasValue(1);
    }
}
