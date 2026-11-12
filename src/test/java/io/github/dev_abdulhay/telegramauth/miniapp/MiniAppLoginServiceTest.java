package io.github.dev_abdulhay.telegramauth.miniapp;

import com.example.demo.DemoUser;
import com.example.demo.DemoUserService;
import com.example.demo.StubUserRepo;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MiniAppLoginServiceTest {

    private static final String TOKEN = "123:ABC";

    private final Instant now = Instant.parse("2026-11-13T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final StubUserRepo repo = new StubUserRepo();
    private final AtomicInteger calls = new AtomicInteger();

    private MiniAppLoginService<DemoUser> service(boolean failFirst) {
        TelegramBotModule module = TelegramBotModule.builder(TOKEN, "demo_bot")
                .bot(new TelegramBot(HttpClient.newHttpClient(), TOKEN))
                .miniApp(MiniAppOptions.defaults())
                .approveHandler((info, ctx) -> {
                    if (calls.incrementAndGet() == 1 && failFirst) {
                        throw new RuntimeException("host handler down");
                    }
                    return new AuthApproveResult(Map.of("tgId", info.telegramId()));
                })
                .build();
        return new MiniAppLoginService<>(module, new DemoUserService(repo),
                new InMemoryInitDataReplayStore(clock), clock);
    }

    private String initData(long telegramId) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("user", "{\"id\":" + telegramId + ",\"first_name\":\"Ali\",\"username\":\"ali\"}");
        f.put("auth_date", String.valueOf(now.getEpochSecond()));
        return MiniAppTestSigner.sign(TOKEN, f);
    }

    @Test
    void handlerFailureDoesNotBurnHash() {
        MiniAppLoginService<DemoUser> service = service(true);
        String raw = initData(42);

        assertThatThrownBy(() -> service.login(raw, "1.1.1.1", "ua", null))
                .isInstanceOf(RuntimeException.class).hasMessage("host handler down");

        assertThat(service.login(raw, "1.1.1.1", "ua", null)).containsEntry("tgId", 42L);

        // a successful login does burn it
        assertThatThrownBy(() -> service.login(raw, "1.1.1.1", "ua", null))
                .isInstanceOfSatisfying(MiniAppAuthException.class,
                        e -> assertThat(e.error()).isEqualTo(MiniAppError.INIT_DATA_EXPIRED));
    }

    @Test
    void blockedUserHandlerNotCalled() {
        DemoUser blocked = new DemoUser();
        blocked.setTelegramId(77L);
        blocked.setStatus(BaseTelegramUser.Status.BLOCKED);
        repo.save(blocked);
        MiniAppLoginService<DemoUser> service = service(false);

        assertThatThrownBy(() -> service.login(initData(77), "1.1.1.1", "ua", null))
                .isInstanceOfSatisfying(MiniAppAuthException.class,
                        e -> assertThat(e.error()).isEqualTo(MiniAppError.USER_BLOCKED));
        assertThat(calls).hasValue(0);
    }
}
