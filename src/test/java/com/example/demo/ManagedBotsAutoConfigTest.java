package com.example.demo;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.managedbots.InMemoryManagedBotStore;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotService;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotTokenStore;
import io.github.dev_abdulhay.telegramauth.managedbots.TelegramManagedBotsAutoConfiguration;
import io.github.dev_abdulhay.telegramauth.managedbots.TokenEncryptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class ManagedBotsAutoConfigTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    // @TestConfiguration (not @Configuration): this class lives in the same
    // package as DemoApp, whose bare @ComponentScan sweeps that whole package.
    // @TestConfiguration is excluded from that scan, so this fixture cannot leak
    // a stray TelegramBotModule/ManagedBotTokenStore bean into unrelated
    // @SpringBootTest(classes = DemoApp.class) tests. ApplicationContextRunner
    // registers it explicitly via withUserConfiguration(...) either way, so the
    // four tests below behave identically.
    @TestConfiguration
    static class HostBeans {
        @Bean TelegramBotModule module() {
            return TelegramBotModule.builder("123:ABC", "manager_bot").build();
        }
        @Bean ManagedBotTokenStore store() {
            return new InMemoryManagedBotStore();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TelegramManagedBotsAutoConfiguration.class))
            .withUserConfiguration(HostBeans.class);

    @Test
    void theFeatureIsOffByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(ManagedBotService.class));
    }

    @Test
    void enablingItWiresTheServiceAndClaimsTheUpdateSlot() {
        runner.withPropertyValues("telegram.managed-bots.enabled=true",
                        "telegram.managed-bots.encryption-key=" + KEY)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ManagedBotService.class);
                    assertThat(ctx).hasSingleBean(TokenEncryptor.class);
                    assertThat(ctx.getBean(TelegramBotModule.class).getManagedBotHandler()).isNotNull();
                });
    }

    @Test
    void enablingItWithoutAKeyFailsTheContext() {
        runner.withPropertyValues("telegram.managed-bots.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().hasMessageContaining("encryption-key"));
    }

    @Test
    void aHostSuppliedEncryptorReplacesTheDefaultAndNeedsNoKey() {
        runner.withPropertyValues("telegram.managed-bots.enabled=true")
                .withBean(TokenEncryptor.class, () -> new TokenEncryptor() {
                    @Override public String encrypt(String p) { return p; }
                    @Override public String decrypt(String c) { return c; }
                })
                .run(ctx -> assertThat(ctx).hasSingleBean(ManagedBotService.class));
    }
}
