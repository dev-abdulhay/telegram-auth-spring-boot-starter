package com.example.demo;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import io.github.dev_abdulhay.telegramauth.service.SessionRateLimitException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class TenantScopingTest {

    private static DemoSessionService serviceFor(Long botUserId, StubSessionRepo repo) {
        TelegramBotModule.Builder b = TelegramBotModule.builder("123:ABC", "tenant_bot")
                .maxPendingPerIp(2);
        if (botUserId != null) b.botUserId(botUserId);
        return new DemoSessionService(repo, new TokenGenerator(), b.build());
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
}
