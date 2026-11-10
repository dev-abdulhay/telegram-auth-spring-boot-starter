package com.example.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dev_abdulhay.telegramauth.bot.BotUpdateDispatcher;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBot;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.managedbots.InMemoryManagedBotStore;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotTokenStore;
import io.github.dev_abdulhay.telegramauth.managedbots.TelegramManagedBotsAutoConfiguration;
import io.github.dev_abdulhay.telegramauth.payments.StarOrder;
import io.github.dev_abdulhay.telegramauth.payments.StarPaymentException;
import io.github.dev_abdulhay.telegramauth.payments.StarPaymentService;
import io.github.dev_abdulhay.telegramauth.payments.StarPaymentUpdateHandler;
import io.github.dev_abdulhay.telegramauth.payments.StarRefundGuard;
import io.github.dev_abdulhay.telegramauth.payments.TelegramPaymentsAutoConfiguration;
import io.github.dev_abdulhay.telegramauth.payments.balance.BalanceAccountKey;
import io.github.dev_abdulhay.telegramauth.payments.balance.BalanceLedger;
import io.github.dev_abdulhay.telegramauth.payments.balance.InMemoryBalanceLedger;
import io.github.dev_abdulhay.telegramauth.payments.balance.TelegramPaymentsBalanceAutoConfiguration;
import io.github.dev_abdulhay.telegramauth.payments.balance.TopUpCreditor;
import io.github.dev_abdulhay.telegramauth.whitelabel.ManagedBotCustomizer;
import io.github.dev_abdulhay.telegramauth.whitelabel.TelegramWhiteLabelAutoConfiguration;
import io.github.dev_abdulhay.telegramauth.whitelabel.TenantBotFactory;
import io.github.dev_abdulhay.telegramauth.whitelabel.TenantBotRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import java.net.http.HttpClient;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole Stars flow through the auto-configured beans: invoice, pre-checkout, duplicate
 * payment delivery, balance credit and refund. Updates go through {@link BotUpdateDispatcher}
 * exactly as the polling runner would route them.
 */
class StarPaymentsEndToEndTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final long USER = 42L;

    /** Records the payment API calls instead of hitting Telegram. */
    static class RecordingBot extends TelegramBot {
        final List<String> payloads = new CopyOnWriteArrayList<>();
        final List<String> answers = new CopyOnWriteArrayList<>();
        final List<String> refunds = new CopyOnWriteArrayList<>();

        RecordingBot() {
            super(HttpClient.newHttpClient(), "test-token");
        }

        @Override
        public JsonNode sendInvoice(long chatId, String title, String description, String payload, int stars) {
            payloads.add(payload);
            return MAPPER.createObjectNode().put("message_id", 1);
        }

        @Override
        public void answerPreCheckoutQuery(String queryId, boolean ok, String errorMessage) {
            answers.add(queryId + ":" + ok);
        }

        @Override
        public void refundStarPayment(long userId, String chargeId) {
            refunds.add(userId + ":" + chargeId);
        }

        @Override
        public JsonNode getStarTransactions(int offset, int limit) {
            ObjectNode r = MAPPER.createObjectNode();
            r.putArray("transactions");
            return r;
        }
    }

    // @TestConfiguration so DemoApp's component scan does not pick the fixture up.
    @TestConfiguration
    static class HostBeans {
        @Bean TelegramBotModule module() {
            return TelegramBotModule.builder("test-token", "test_bot").bot(new RecordingBot()).botUserId(1000L)
                    .build();
        }
    }

    @TestConfiguration
    static class WhiteLabelBeans {
        @Bean ManagedBotTokenStore store() {
            return new InMemoryManagedBotStore();
        }
        @Bean TenantBotFactory<DemoUser, DemoSession> factory() {
            return (bot, token) -> {
                throw new UnsupportedOperationException("not invoked in this test");
            };
        }
    }

    @TestConfiguration
    static class HostLedger {
        static final BalanceLedger LEDGER = new InMemoryBalanceLedger();
        @Bean BalanceLedger hostLedger() {
            return LEDGER;
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TelegramPaymentsAutoConfiguration.class,
                    TelegramPaymentsBalanceAutoConfiguration.class))
            .withUserConfiguration(HostBeans.class)
            .withPropertyValues("telegram.payments.enabled=true",
                    "telegram.payments.top-up.enabled=true",
                    "telegram.payments.reconcile.enabled=false");

    private ApplicationContextRunner balance() {
        return runner.withPropertyValues("telegram.payments.balance.enabled=true");
    }

    static void dispatch(TelegramBotModule module, JsonNode update) {
        ObjectNode batch = MAPPER.createObjectNode().put("ok", true);
        batch.putArray("result").add(update);
        new BotUpdateDispatcher(module).dispatch(batch.toString());
    }

    static JsonNode preCheckout(String queryId, String payload, int amount) {
        ObjectNode u = MAPPER.createObjectNode().put("update_id", 1);
        ObjectNode q = u.putObject("pre_checkout_query").put("id", queryId).put("currency", "XTR")
                .put("total_amount", amount).put("invoice_payload", payload);
        q.putObject("from").put("id", USER);
        return u;
    }

    static JsonNode payment(String payload, String chargeId, int amount) {
        ObjectNode u = MAPPER.createObjectNode().put("update_id", 2);
        ObjectNode m = u.putObject("message").put("message_id", 5);
        m.putObject("from").put("id", USER);
        m.putObject("chat").put("id", USER);
        m.putObject("successful_payment").put("currency", "XTR").put("total_amount", amount)
                .put("invoice_payload", payload).put("telegram_payment_charge_id", chargeId);
        return u;
    }

    @Test
    void topUpIsCreditedOnceAndRefunded() {
        balance().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            StarPaymentService service = ctx.getBean(StarPaymentService.class);
            BalanceLedger ledger = ctx.getBean(BalanceLedger.class);
            TelegramBotModule main = ctx.getBean(TelegramBotModule.class);
            RecordingBot bot = (RecordingBot) main.getBot();
            BalanceAccountKey key = new BalanceAccountKey(null, USER);

            StarOrder order = service.sendTopUpInvoice(USER, USER, 100);
            assertThat(bot.payloads).containsExactly(order.payload());

            dispatch(main, preCheckout("q1", order.payload(), 100));
            assertThat(bot.answers).containsExactly("q1:true");

            dispatch(main, payment(order.payload(), "ch-1", 100));
            dispatch(main, payment(order.payload(), "ch-1", 100));
            assertThat(ledger.balance(key)).isEqualTo(100);
            assertThat(ledger.history(key, 0, 10)).hasSize(1);

            service.refund(order.id());
            assertThat(bot.refunds).containsExactly(USER + ":ch-1");
            assertThat(ledger.balance(key)).isZero();
        });
    }

    @Test
    void refundOfSpentCreditsIsRefused() {
        balance().run(ctx -> {
            StarPaymentService service = ctx.getBean(StarPaymentService.class);
            BalanceLedger ledger = ctx.getBean(BalanceLedger.class);
            TelegramBotModule main = ctx.getBean(TelegramBotModule.class);
            BalanceAccountKey key = new BalanceAccountKey(null, USER);
            StarOrder order = service.sendTopUpInvoice(USER, USER, 100);
            dispatch(main, preCheckout("q1", order.payload(), 100));
            dispatch(main, payment(order.payload(), "ch-1", 100));
            ledger.debit(key, 30, "spend:1", "spend");

            assertThatThrownBy(() -> service.refund(order.id()))
                    .isInstanceOf(StarPaymentException.class)
                    .hasMessageContaining("Credits from this payment were already spent");
            assertThat(((RecordingBot) main.getBot()).refunds).isEmpty();
            assertThat(ledger.balance(key)).isEqualTo(70);
        });
    }

    @Test
    void perBotScopeCreditsTheTenantBalance() {
        balance().withPropertyValues("telegram.payments.balance.scope=PER_BOT").run(ctx -> {
            StarPaymentService service = ctx.getBean(StarPaymentService.class);
            BalanceLedger ledger = ctx.getBean(BalanceLedger.class);
            RecordingBot tenantBot = new RecordingBot();
            TelegramBotModule tenant = TelegramBotModule.builder("9:XYZ", "tenant_bot").bot(tenantBot)
                    .botUserId(777L).build();
            service.registerBot(tenant);
            ctx.getBean(StarPaymentUpdateHandler.class).register(tenant);

            StarOrder order = service.sendTopUpInvoice(777L, USER, USER, 50);
            assertThat(tenantBot.payloads).containsExactly(order.payload());
            dispatch(tenant, preCheckout("q7", order.payload(), 50));
            assertThat(tenantBot.answers).containsExactly("q7:true");
            dispatch(tenant, payment(order.payload(), "ch-7", 50));

            assertThat(ledger.balance(new BalanceAccountKey(777L, USER))).isEqualTo(50);
            assertThat(ledger.balance(new BalanceAccountKey(null, USER))).isZero();
        });
    }

    @Test
    void balanceIsOffByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(BalanceLedger.class);
            assertThat(ctx).doesNotHaveBean(TopUpCreditor.class);
        });
    }

    @Test
    void balanceNeedsPaymentsEnabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TelegramPaymentsBalanceAutoConfiguration.class))
                .withPropertyValues("telegram.payments.balance.enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(BalanceLedger.class));
    }

    @Test
    void creditorIsTheRefundGuardAndAHostLedgerWins() {
        balance().withUserConfiguration(HostLedger.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(BalanceLedger.class);
            assertThat(ctx.getBean(BalanceLedger.class)).isSameAs(HostLedger.LEDGER);
            assertThat(ctx.getBean(StarRefundGuard.class)).isSameAs(ctx.getBean(TopUpCreditor.class));
        });
    }

    /** TopUpCreditor feeds the service while the tenant customizer consumes it: no bean cycle. */
    @Test
    void startsWithBalanceAndWhiteLabelTogether() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TelegramManagedBotsAutoConfiguration.class,
                        TelegramWhiteLabelAutoConfiguration.class, TelegramPaymentsAutoConfiguration.class,
                        TelegramPaymentsBalanceAutoConfiguration.class))
                .withUserConfiguration(HostBeans.class, WhiteLabelBeans.class)
                .withPropertyValues("telegram.managed-bots.enabled=true",
                        "telegram.managed-bots.encryption-key=" + key,
                        "telegram.white-label.enabled=true",
                        "telegram.payments.enabled=true",
                        "telegram.payments.balance.enabled=true",
                        "telegram.payments.reconcile.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(TenantBotRegistry.class);
                    assertThat(ctx).hasSingleBean(ManagedBotCustomizer.class);
                    assertThat(ctx).hasSingleBean(TopUpCreditor.class);
                    assertThat(ctx).hasSingleBean(StarPaymentService.class);
                });
    }
}
