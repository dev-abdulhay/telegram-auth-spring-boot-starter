package com.example.demo;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.managedbots.InMemoryManagedBotIntentStore;
import io.github.dev_abdulhay.telegramauth.managedbots.InMemoryManagedBotStore;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotIntentFlow;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotIntentStore;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotService;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotTokenStore;
import io.github.dev_abdulhay.telegramauth.managedbots.TelegramManagedBotsAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedBotIntentsAutoConfigTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @TestConfiguration
    static class WithoutIntents {
        @Bean TelegramBotModule module() {
            return TelegramBotModule.builder("123:ABC", "manager_bot").build();
        }
        @Bean ManagedBotTokenStore store() { return new InMemoryManagedBotStore(); }
    }

    @TestConfiguration
    static class WithIntents {
        @Bean TelegramBotModule module() {
            return TelegramBotModule.builder("123:ABC", "manager_bot").build();
        }
        @Bean ManagedBotTokenStore store() { return new InMemoryManagedBotStore(); }
        @Bean ManagedBotIntentStore intents() { return new InMemoryManagedBotIntentStore(); }
    }

    private ApplicationContextRunner runner(Class<?> hostBeans) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TelegramManagedBotsAutoConfiguration.class))
                .withUserConfiguration(hostBeans)
                .withPropertyValues("telegram.managed-bots.enabled=true",
                        "telegram.managed-bots.encryption-key=" + KEY);
    }

    @Test
    void withoutAnIntentStoreTheFeatureIsSimplyAbsent() {
        runner(WithoutIntents.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(ManagedBotService.class);
            assertThat(ctx).doesNotHaveBean(ManagedBotIntentFlow.class);
            assertThatThrownBy(() -> ctx.getBean(ManagedBotService.class)
                    .createIntent("tenant_shop_bot", "Shop", "bot:1"))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void anIntentStoreBeanTurnsIntentsOnAndClaimsTheStartPayloadRoute() {
        runner(WithIntents.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(ManagedBotIntentFlow.class);
            assertThat(ctx.getBean(TelegramBotModule.class).getStartPayloadRoutes())
                    .containsOnlyKeys("mb_");
            assertThat(ctx.getBean(ManagedBotService.class)
                    .createIntent("tenant_shop_bot", "Shop", "bot:1").url())
                    .startsWith("https://t.me/manager_bot?start=mb_");
        });
    }

    @Test
    void theIntentTtlAndRetentionAreBindable() {
        runner(WithIntents.class)
                .withPropertyValues("telegram.managed-bots.intent-ttl=5m",
                        "telegram.managed-bots.intent-retention=2d")
                .run(ctx -> assertThat(ctx.getBean(ManagedBotService.class)
                        .createIntent("tenant_shop_bot", "Shop", "bot:1").expiresAt())
                        .isBefore(java.time.OffsetDateTime.now().plusMinutes(6)));
    }
}
