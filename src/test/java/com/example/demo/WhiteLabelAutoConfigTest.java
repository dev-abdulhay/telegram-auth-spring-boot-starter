package com.example.demo;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.managedbots.InMemoryManagedBotStore;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotEvents;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotTokenStore;
import io.github.dev_abdulhay.telegramauth.managedbots.TelegramManagedBotsAutoConfiguration;
import io.github.dev_abdulhay.telegramauth.whitelabel.RunningBot;
import io.github.dev_abdulhay.telegramauth.whitelabel.TelegramWhiteLabelAutoConfiguration;
import io.github.dev_abdulhay.telegramauth.whitelabel.TenantBotEventBridge;
import io.github.dev_abdulhay.telegramauth.whitelabel.TenantBotFactory;
import io.github.dev_abdulhay.telegramauth.whitelabel.TenantBotRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class WhiteLabelAutoConfigTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    // @TestConfiguration (not @Configuration): this class lives in the same
    // package as DemoApp, whose bare @ComponentScan sweeps that whole package.
    // @TestConfiguration is excluded from that scan, so this fixture cannot leak
    // a stray TelegramBotModule/ManagedBotTokenStore/TenantBotFactory bean into
    // unrelated @SpringBootTest(classes = DemoApp.class) tests. See
    // ManagedBotsAutoConfigTest for the same idiom.
    @TestConfiguration
    static class HostBeans {
        @Bean TelegramBotModule module() {
            return TelegramBotModule.builder("123:ABC", "manager_bot").build();
        }
        @Bean ManagedBotTokenStore store() {
            return new InMemoryManagedBotStore();
        }
        @Bean TenantBotFactory<DemoUser, DemoSession> factory() {
            return (bot, token) -> new RunningBot<>(
                    TelegramBotModule.builder(token, bot.username()).botUserId(bot.botUserId()).build(),
                    null);
        }
    }

    // Same as HostBeans but deliberately missing factory() — used only by
    // enablingItWithoutAFactoryFailsTheContext, so that test's context is
    // missing exactly one bean (TenantBotFactory) rather than three, and a
    // failure there can only be attributed to the fail-fast check under test.
    @TestConfiguration
    static class HostBeansWithoutFactory {
        @Bean TelegramBotModule module() {
            return TelegramBotModule.builder("123:ABC", "manager_bot").build();
        }
        @Bean ManagedBotTokenStore store() {
            return new InMemoryManagedBotStore();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TelegramWhiteLabelAutoConfiguration.class,
                    TelegramManagedBotsAutoConfiguration.class))
            .withUserConfiguration(HostBeans.class)
            .withPropertyValues("telegram.managed-bots.enabled=true",
                    "telegram.managed-bots.encryption-key=" + KEY);

    @Test
    void theRuntimeIsOffByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(TenantBotRegistry.class));
    }

    @Test
    void enablingItWiresTheRegistryAndOwnsTheEventsBean() {
        runner.withPropertyValues("telegram.white-label.enabled=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(TenantBotRegistry.class);
            // the bridge must beat managed-bots' no-op ManagedBotEvents
            assertThat(ctx.getBean(ManagedBotEvents.class)).isInstanceOf(TenantBotEventBridge.class);
        });
    }

    @Test
    void enablingItWithoutAFactoryFailsTheContext() {
        // HostBeansWithoutFactory still supplies module() and store(), so
        // managedBotService(...) and tenantBotLifecycle(...) have everything
        // else they need — the only thing missing is TenantBotFactory. If the
        // fail-fast check were deleted, this context would start fine.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        TelegramWhiteLabelAutoConfiguration.class,
                        TelegramManagedBotsAutoConfiguration.class))
                .withUserConfiguration(HostBeansWithoutFactory.class)
                .withPropertyValues("telegram.white-label.enabled=true",
                        "telegram.managed-bots.enabled=true",
                        "telegram.managed-bots.encryption-key=" + KEY)
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().hasMessageContaining("TenantBotFactory"));
    }

    @Test
    void propertiesBindWithTheirDefaults() {
        runner.withPropertyValues("telegram.white-label.enabled=true").run(ctx -> {
            var props = ctx.getBean(io.github.dev_abdulhay.telegramauth.whitelabel
                    .TelegramWhiteLabelProperties.class);
            assertThat(props.isRestoreOnStartup()).isTrue();
            assertThat(props.getPollFailureBudget()).isEqualTo(java.time.Duration.ofMinutes(5));
        });
    }

    @Test
    void propertiesBindOverrides() {
        runner.withPropertyValues("telegram.white-label.enabled=true",
                "telegram.white-label.poll-failure-budget=30s").run(ctx -> {
            var props = ctx.getBean(io.github.dev_abdulhay.telegramauth.whitelabel
                    .TelegramWhiteLabelProperties.class);
            assertThat(props.getPollFailureBudget()).isEqualTo(java.time.Duration.ofSeconds(30));
        });
    }
}
