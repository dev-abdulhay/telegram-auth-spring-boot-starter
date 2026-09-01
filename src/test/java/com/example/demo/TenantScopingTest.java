package com.example.demo;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import io.github.dev_abdulhay.telegramauth.service.AuthEvent;
import io.github.dev_abdulhay.telegramauth.service.SessionRateLimitException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class TenantScopingTest {

    private static TelegramBotModule moduleFor(Long botUserId) {
        TelegramBotModule.Builder b = TelegramBotModule.builder("123:ABC", "tenant_bot")
                .maxPendingPerIp(2);
        if (botUserId != null) b.botUserId(botUserId);
        return b.build();
    }

    private static DemoSessionService serviceFor(Long botUserId, StubSessionRepo repo) {
        return new DemoSessionService(repo, new TokenGenerator(), moduleFor(botUserId));
    }

    private static DemoUser user(long telegramId) {
        DemoUser u = new DemoUser();
        u.setTelegramId(telegramId);
        return u;
    }

    @Test
    void aTenantSessionCarriesItsBotId() {
        var created = serviceFor(555L, new StubSessionRepo()).create("ip", "ua");
        assertThat(((BaseAuthSession) created.entity()).getBotUserId()).isEqualTo(555L);
    }

    @Test
    void aStaticModuleLeavesTheBotIdNull() {
        var created = serviceFor(null, new StubSessionRepo()).create("ip", "ua");
        assertThat(((BaseAuthSession) created.entity()).getBotUserId()).isNull();
    }

    @Test
    void aFloodAgainstOneTenantDoesNotLockOutAnother() {
        StubSessionRepo shared = new StubSessionRepo();
        DemoSessionService tenantA = serviceFor(555L, shared);
        DemoSessionService tenantB = serviceFor(556L, shared);

        tenantA.create("1.2.3.4", "ua");
        tenantA.create("1.2.3.4", "ua");
        assertThatThrownBy(() -> tenantA.create("1.2.3.4", "ua"))
                .isInstanceOf(SessionRateLimitException.class);

        // same IP, same table, different tenant — must still be allowed
        assertThatCode(() -> tenantB.create("1.2.3.4", "ua")).doesNotThrowAnyException();
    }

    @Test
    void aStaticModuleStillCountsAcrossTheWholeTable() {
        StubSessionRepo shared = new StubSessionRepo();
        DemoSessionService staticModule = serviceFor(null, shared);

        staticModule.create("1.2.3.4", "ua");
        staticModule.create("1.2.3.4", "ua");
        assertThatThrownBy(() -> staticModule.create("1.2.3.4", "ua"))
                .isInstanceOf(SessionRateLimitException.class);
    }

    @Test
    void aStaticModuleCountsRowsFromEveryTenant() {
        StubSessionRepo shared = new StubSessionRepo();
        DemoSessionService tenantA = serviceFor(555L, shared);
        DemoSessionService staticModule = serviceFor(null, shared);

        tenantA.create("1.2.3.4", "ua");
        tenantA.create("1.2.3.4", "ua");

        // the static module's limit is 2 and the table already holds 2 live rows on
        // this IP — a global count must refuse, a null-scoped count would allow
        assertThatThrownBy(() -> staticModule.create("1.2.3.4", "ua"))
                .isInstanceOf(SessionRateLimitException.class);
    }

    @Test
    void aTenantCannotSeeASessionMintedByAnother() {
        StubSessionRepo shared = new StubSessionRepo();
        DemoSessionService tenantA = serviceFor(555L, shared);
        DemoSessionService tenantB = serviceFor(556L, shared);

        var created = tenantA.create("1.2.3.4", "ua");

        assertThat(tenantA.findByRawToken(created.rawToken())).isPresent();
        assertThat(tenantB.findByRawToken(created.rawToken()))
                .as("one tenant's token must not resolve through another's service")
                .isEmpty();
    }

    /**
     * The harm is not that B learns something — the token is a secret the caller
     * already holds, and the Telegram identity is genuine. It is that B's service
     * moves A's row to a terminal state and publishes the event on <b>B's</b>
     * {@code AuthEventBus}: A's browser, subscribed to A's bus, waits forever on a
     * session the database says is already APPROVED. So this asserts both halves —
     * the transition is refused, and nothing lands on the wrong bus.
     */
    @Test
    void aTenantCannotCompleteASessionMintedByAnother() {
        StubSessionRepo shared = new StubSessionRepo();
        DemoSessionService tenantA = serviceFor(555L, shared);
        TelegramBotModule moduleB = moduleFor(556L);
        DemoSessionService tenantB = new DemoSessionService(shared, new TokenGenerator(), moduleB);

        var created = tenantA.create("1.2.3.4", "ua");
        String hash = tenantA.hash(created.rawToken());

        AtomicReference<AuthEvent> leaked = new AtomicReference<>();
        moduleB.getBus().subscribe(hash, leaked::set);

        assertThat(tenantB.approve(hash, user(99L))).isFalse();
        assertThat(tenantB.awaitCode(hash)).isFalse();
        assertThat(tenantB.reject(hash)).isFalse();

        assertThat(leaked.get())
                .as("no terminal event may be published on the wrong tenant's bus")
                .isNull();
        assertThat(((BaseAuthSession) created.entity()).getStatus())
                .isEqualTo(BaseAuthSession.Status.PENDING);

        // and the tenant that actually minted it is unaffected
        assertThat(tenantA.approve(hash, user(99L))).isTrue();
        assertThat(((BaseAuthSession) created.entity()).getStatus())
                .isEqualTo(BaseAuthSession.Status.APPROVED);
    }

    /**
     * Every host today has a module with no bot id, and for those the lookup must
     * stay exactly the unscoped query it has always been — a static module finds
     * and completes any row in the table, including rows a tenant bot wrote.
     */
    @Test
    void aStaticModuleStillFindsAndCompletesEveryRow() {
        StubSessionRepo shared = new StubSessionRepo();
        DemoSessionService tenantA = serviceFor(555L, shared);
        DemoSessionService staticModule = serviceFor(null, shared);

        var created = tenantA.create("1.2.3.4", "ua");

        assertThat(staticModule.findByRawToken(created.rawToken())).isPresent();
        assertThat(staticModule.approve(staticModule.hash(created.rawToken()), user(99L))).isTrue();
    }
}
