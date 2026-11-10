package com.example.demo;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.payments.InMemoryStarOrderStore;
import io.github.dev_abdulhay.telegramauth.payments.PropertiesStarCatalog;
import io.github.dev_abdulhay.telegramauth.payments.StarCatalog;
import io.github.dev_abdulhay.telegramauth.payments.StarOrderStore;
import io.github.dev_abdulhay.telegramauth.payments.TelegramPaymentsProperties;
import io.github.dev_abdulhay.telegramauth.payments.StarPaymentEvents;
import io.github.dev_abdulhay.telegramauth.payments.StarPaymentReconciler;
import io.github.dev_abdulhay.telegramauth.payments.StarPaymentService;
import io.github.dev_abdulhay.telegramauth.payments.StarPaymentUpdateHandler;
import io.github.dev_abdulhay.telegramauth.payments.StarSubscriptionStore;
import io.github.dev_abdulhay.telegramauth.payments.TelegramPaymentsAutoConfiguration;
import io.github.dev_abdulhay.telegramauth.whitelabel.ManagedBotCustomizer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentsAutoConfigTest {

    // @TestConfiguration so DemoApp's component scan does not pick the fixture up.
    @TestConfiguration
    static class HostBeans {
        @Bean TelegramBotModule module() {
            return TelegramBotModule.builder("123:ABC", "manager_bot").build();
        }
    }

    @TestConfiguration
    static class HostEvents {
        static final AtomicInteger CALLS = new AtomicInteger();
        @Bean StarPaymentEvents hostEvents() {
            return new StarPaymentEvents() {
                @Override public void onSubscriptionExpired(io.github.dev_abdulhay.telegramauth.payments.StarSubscription s) {
                    CALLS.incrementAndGet();
                }
            };
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TelegramPaymentsAutoConfiguration.class))
            .withUserConfiguration(HostBeans.class);

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues("telegram.payments.enabled=true",
                "telegram.payments.products.basic.title=Basic",
                "telegram.payments.products.basic.description=Basic plan",
                "telegram.payments.products.basic.stars=5");
    }

    @Test
    void offByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(StarPaymentService.class));
    }

    @Test
    void enabledWiresServiceHandlerAndHooks() {
        enabled().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(StarPaymentService.class);
            assertThat(ctx).hasSingleBean(StarPaymentUpdateHandler.class);
            assertThat(ctx).hasSingleBean(StarPaymentReconciler.class);
            assertThat(ctx).doesNotHaveBean(ManagedBotCustomizer.class);
            TelegramBotModule m = ctx.getBean(TelegramBotModule.class);
            assertThat(m.getPreCheckoutHandler()).isNotNull();
        });
    }

    @Test
    void contextCloseStopsReconcilerAndHandlerExecutor() {
        StarPaymentReconciler[] rec = new StarPaymentReconciler[1];
        StarPaymentUpdateHandler[] h = new StarPaymentUpdateHandler[1];
        enabled().run(ctx -> {
            rec[0] = ctx.getBean(StarPaymentReconciler.class);
            h[0] = ctx.getBean(StarPaymentUpdateHandler.class);
            assertThat(rec[0].isRunning()).isTrue();
            assertThat(h[0].isShutdown()).isFalse();
            ctx.close();
        });
        assertThat(rec[0].isRunning()).isFalse();
        assertThat(h[0].isShutdown()).isTrue();
    }

    @Test
    void invalidSettingsFailEvenWithHostCatalog() {
        enabled().withBean(StarCatalog.class, () -> new PropertiesStarCatalog(new TelegramPaymentsProperties()))
                .withPropertyValues("telegram.payments.pre-checkout-timeout=15s")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void tenantCustomizerWiresTenantModule() {
        enabled().withPropertyValues("telegram.white-label.enabled=true").run(ctx -> {
            TelegramBotModule tenant = TelegramBotModule.builder("9:XYZ", "tenant_bot").botUserId(777L).build();
            var bot = new io.github.dev_abdulhay.telegramauth.managedbots.ManagedBot(
                    777L, "tenant_bot", "T", 1L, "enc", null, null);
            ctx.getBean(ManagedBotCustomizer.class).customize(tenant, bot);
            assertThat(tenant.getPreCheckoutHandler()).isNotNull();
            assertThat(tenant.getCommands()).containsKey("/paysupport");
            assertThat(ctx.getBean(StarPaymentService.class).tenantBots()).containsKey(777L);
        });
    }

    @Test
    void invalidProductFailsStartup() {
        runner.withPropertyValues("telegram.payments.enabled=true",
                        "telegram.payments.products.bad.title=Bad",
                        "telegram.payments.products.bad.description=Bad",
                        "telegram.payments.products.bad.stars=0")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void missingModuleFailsWithClearMessage() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TelegramPaymentsAutoConfiguration.class))
                .withPropertyValues("telegram.payments.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().hasMessageContaining("TelegramBotModule"));
    }

    @Test
    void hostStoreReplacesInMemory() {
        InMemoryStarOrderStore mine = new InMemoryStarOrderStore();
        enabled().withBean(StarOrderStore.class, () -> mine).run(ctx -> {
            assertThat(ctx.getBean(StarOrderStore.class)).isSameAs(mine);
            assertThat(ctx).hasSingleBean(StarSubscriptionStore.class);
        });
    }

    @Test
    void reconcilerCanBeDisabled() {
        enabled().withPropertyValues("telegram.payments.reconcile.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(StarPaymentReconciler.class));
    }

    @Test
    void paysupportRegisteredUnlessHostHasOne() {
        enabled().run(ctx -> {
            var cmds = ctx.getBean(TelegramBotModule.class).getCommands();
            assertThat(cmds).containsKeys("/paysupport", "/terms");
        });
        java.util.function.Consumer<com.fasterxml.jackson.databind.JsonNode> hostOwn = u -> { };
        TelegramBotModule mod = TelegramBotModule.builder("123:ABC", "manager_bot").build();
        mod.command("/paysupport", hostOwn);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TelegramPaymentsAutoConfiguration.class))
                .withBean(TelegramBotModule.class, () -> mod)
                .withPropertyValues("telegram.payments.enabled=true")
                .run(ctx -> assertThat(mod.getCommands().get("/paysupport")).isSameAs(hostOwn));
    }

    @Test
    void supportCanBeDisabled() {
        enabled().withPropertyValues("telegram.payments.support.enabled=false").run(ctx ->
                assertThat(ctx.getBean(TelegramBotModule.class).getCommands())
                        .doesNotContainKey("/paysupport"));
    }

    @Test
    void hostEventsBeanReceivesCallbacks() {
        HostEvents.CALLS.set(0);
        enabled().withUserConfiguration(HostEvents.class).run(ctx -> {
            ctx.getBean(StarPaymentService.class).events().onSubscriptionExpired(null);
            assertThat(HostEvents.CALLS.get()).isEqualTo(1);
        });
    }

    @Test
    void tenantCustomizerOnlyWithWhiteLabel() {
        enabled().withPropertyValues("telegram.white-label.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(ManagedBotCustomizer.class));
    }
}
