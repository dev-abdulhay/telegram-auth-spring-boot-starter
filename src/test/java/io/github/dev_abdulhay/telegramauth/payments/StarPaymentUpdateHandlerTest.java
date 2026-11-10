package io.github.dev_abdulhay.telegramauth.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class StarPaymentUpdateHandlerTest {

    static final Instant T0 = StarPaymentServiceTest.T0;
    static final String REJECT = new TelegramPaymentsProperties().getPreCheckoutRejectMessage();

    final MutableClock clock = new MutableClock(T0);
    final FakeStarBot bot = new FakeStarBot();
    final TelegramBotModule main = FakeStarBot.module(bot, 1000L);
    final InMemoryStarOrderStore orders = new InMemoryStarOrderStore();
    final InMemoryStarSubscriptionStore subs = new InMemoryStarSubscriptionStore();
    final TelegramPaymentsProperties props = StarPaymentServiceTest.props();
    final List<String> events = new CopyOnWriteArrayList<>();
    volatile boolean failListener;
    PreCheckoutValidator validator;

    final StarPaymentEvents recorder = new StarPaymentEvents() {
        @Override
        public void onPaid(StarOrder order) {
            if (failListener) {
                throw new IllegalStateException("listener down");
            }
            events.add("paid:" + order.id());
        }

        @Override
        public void onRefunded(StarOrder order, RefundSource source) {
            events.add("refunded:" + order.id() + ":" + source);
        }

        @Override
        public void onSubscriptionRenewed(StarSubscription sub, StarOrder renewal) {
            events.add("renewed:" + sub.id() + ":" + renewal.chargeId());
        }
    };

    StarPaymentService service;
    StarPaymentUpdateHandler handler;

    StarPaymentUpdateHandler handler() {
        service = new StarPaymentService(main, new PropertiesStarCatalog(props), orders, subs, null, recorder,
                props, clock);
        handler = new StarPaymentUpdateHandler(service, orders, subs,
                validator == null ? null : (o, q) -> validator.validate(o, q));
        handler.register(main);
        return handler;
    }

    static OffsetDateTime at(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    StarOrder createdOrder(String product) {
        if (handler == null) {
            handler();
        }
        return product.equals("vip") ? service.createInvoiceLink(42, "vip").order()
                : service.sendInvoice(7, 42, product);
    }

    static JsonNode preCheckout(String payload, long fromId, String currency, int amount) {
        ObjectNode u = FakeStarBot.MAPPER.createObjectNode();
        ObjectNode q = u.putObject("pre_checkout_query").put("id", "q1").put("currency", currency)
                .put("total_amount", amount).put("invoice_payload", payload);
        q.putObject("from").put("id", fromId);
        return u;
    }

    static JsonNode payment(String payload, String chargeId, int amount, Long subscriptionExpiry) {
        ObjectNode u = FakeStarBot.MAPPER.createObjectNode();
        ObjectNode m = u.putObject("message");
        m.putObject("from").put("id", 42);
        ObjectNode sp = m.putObject("successful_payment").put("currency", "XTR").put("total_amount", amount)
                .put("invoice_payload", payload).put("telegram_payment_charge_id", chargeId);
        if (subscriptionExpiry != null) {
            sp.put("subscription_expiration_date", subscriptionExpiry);
        }
        return u;
    }

    static JsonNode refunded(String payload, String chargeId) {
        ObjectNode u = FakeStarBot.MAPPER.createObjectNode();
        ObjectNode m = u.putObject("message");
        m.putObject("from").put("id", 42);
        m.putObject("refunded_payment").put("currency", "XTR").put("total_amount", 100)
                .put("invoice_payload", payload).put("telegram_payment_charge_id", chargeId);
        return u;
    }

    void preCheckout(JsonNode update) {
        main.getPreCheckoutHandler().accept(update);
    }

    void pay(JsonNode update) {
        main.getSuccessfulPaymentHandler().accept(update);
    }

    FakeStarBot.Answer onlyAnswer() {
        assertThat(bot.answers).hasSize(1);
        return bot.answers.get(0);
    }

    void assertRejected(String message) {
        FakeStarBot.Answer a = onlyAnswer();
        assertThat(a.ok()).isFalse();
        assertThat(a.errorMessage()).isEqualTo(message);
    }

    // --- pre-checkout ---

    @Test
    void validPreCheckoutAnsweredOk() {
        StarOrder o = createdOrder("pack");
        preCheckout(preCheckout(o.payload(), 42, "XTR", 100));
        FakeStarBot.Answer a = onlyAnswer();
        assertThat(a.ok()).isTrue();
        assertThat(a.queryId()).isEqualTo("q1");
    }

    @Test
    void tamperedAmount() {
        StarOrder o = createdOrder("pack");
        preCheckout(preCheckout(o.payload(), 42, "XTR", 1));
        assertRejected(REJECT);
    }

    @Test
    void foreignUser() {
        StarOrder o = createdOrder("pack");
        preCheckout(preCheckout(o.payload(), 43, "XTR", 100));
        assertRejected(REJECT);
    }

    @Test
    void expiredOrder() {
        StarOrder o = createdOrder("pack");
        clock.advance(Duration.ofMinutes(31));
        preCheckout(preCheckout(o.payload(), 42, "XTR", 100));
        assertRejected(REJECT);
    }

    @Test
    void alreadyPaid() {
        StarOrder o = createdOrder("pack");
        orders.markPaid(o.id(), "ch-1", at(T0));
        preCheckout(preCheckout(o.payload(), 42, "XTR", 100));
        assertRejected(REJECT);
    }

    @Test
    void wrongCurrency() {
        StarOrder o = createdOrder("pack");
        preCheckout(preCheckout(o.payload(), 42, "USD", 100));
        assertRejected(REJECT);
    }

    @Test
    void unknownPayload() {
        handler();
        preCheckout(preCheckout("garbage", 42, "XTR", 100));
        preCheckout(preCheckout("so:" + StarOrderIds.newId(), 42, "XTR", 100));
        assertThat(bot.answers).hasSize(2).allSatisfy(a -> assertThat(a.ok()).isFalse());
    }

    @Test
    void validatorVetoUsesItsMessage() {
        validator = (o, q) -> Optional.of("sold out");
        StarOrder o = createdOrder("pack");
        preCheckout(preCheckout(o.payload(), 42, "XTR", 100));
        assertRejected("sold out");
    }

    @Test
    void slowValidatorTimesOut() {
        props.setPreCheckoutTimeout(Duration.ofMillis(200));
        validator = (o, q) -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        };
        StarOrder o = createdOrder("pack");
        long start = System.nanoTime();
        preCheckout(preCheckout(o.payload(), 42, "XTR", 100));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        assertRejected(REJECT);
    }

    @Test
    void validatorThrowsRejects() {
        validator = (o, q) -> {
            throw new IllegalStateException("db down");
        };
        StarOrder o = createdOrder("pack");
        preCheckout(preCheckout(o.payload(), 42, "XTR", 100));
        assertRejected(REJECT);
    }

    // --- successful payment ---

    @Test
    void successfulPaymentMarksPaidAndFiresOnPaidOnce() {
        StarOrder o = createdOrder("pack");
        pay(payment(o.payload(), "ch-1", 100, null));
        pay(payment(o.payload(), "ch-1", 100, null));

        StarOrder stored = orders.findById(o.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(StarOrderStatus.PAID);
        assertThat(stored.chargeId()).isEqualTo("ch-1");
        assertThat(stored.fulfilledAt()).isNotNull();
        assertThat(events).containsExactly("paid:" + o.id());
        assertThat(handler.completePayment(1000L, 42, o.payload(), "ch-1", 100, null)).isFalse();
    }

    @Test
    void paymentForUnknownOrderIsIgnored() {
        handler();
        assertThat(handler.completePayment(1000L, 42, "so:" + StarOrderIds.newId(), "ch-x", 100, null)).isFalse();
        assertThat(handler.completePayment(1000L, 42, "garbage", "ch-y", 100, null)).isFalse();
        assertThat(events).isEmpty();
    }

    @Test
    void firstSubscriptionPaymentCreatesSubscription() {
        StarOrder o = createdOrder("vip");
        Instant expiry = T0.plus(Duration.ofDays(30));
        pay(payment(o.payload(), "ch-1", 250, expiry.getEpochSecond()));

        StarOrder stored = orders.findById(o.id()).orElseThrow();
        assertThat(stored.subscriptionId()).isNotNull();
        StarSubscription sub = subs.findById(stored.subscriptionId()).orElseThrow();
        assertThat(sub.status()).isEqualTo(StarSubscriptionStatus.ACTIVE);
        assertThat(sub.firstChargeId()).isEqualTo("ch-1");
        assertThat(sub.lastChargeId()).isEqualTo("ch-1");
        assertThat(sub.expiresAt().toInstant()).isEqualTo(expiry);
        assertThat(sub.userId()).isEqualTo(42);
        assertThat(sub.botId()).isEqualTo(1000L);
        assertThat(sub.productCode()).isEqualTo("vip");
        assertThat(events).containsExactly("paid:" + o.id());
    }

    @Test
    void renewalCreatesNewOrderAndFiresRenewed() {
        StarOrder o = createdOrder("vip");
        pay(payment(o.payload(), "ch-1", 250, T0.plus(Duration.ofDays(30)).getEpochSecond()));
        StarOrder original = orders.findById(o.id()).orElseThrow();

        clock.advance(Duration.ofDays(30));
        Instant newExpiry = T0.plus(Duration.ofDays(60));
        pay(payment(o.payload(), "ch-2", 250, newExpiry.getEpochSecond()));
        pay(payment(o.payload(), "ch-2", 250, newExpiry.getEpochSecond()));

        assertThat(orders.findById(o.id()).orElseThrow()).isEqualTo(original);
        StarOrder renewal = orders.findByChargeId("ch-2").orElseThrow();
        assertThat(renewal.id()).isNotEqualTo(o.id());
        assertThat(renewal.kind()).isEqualTo(StarOrderKind.RENEWAL);
        assertThat(renewal.status()).isEqualTo(StarOrderStatus.PAID);
        assertThat(renewal.credits()).isEqualTo(30);
        assertThat(renewal.productCode()).isEqualTo("vip");
        assertThat(renewal.subscriptionId()).isEqualTo(original.subscriptionId());
        assertThat(renewal.fulfilledAt()).isNotNull();
        StarSubscription sub = subs.findById(original.subscriptionId()).orElseThrow();
        assertThat(sub.lastChargeId()).isEqualTo("ch-2");
        assertThat(sub.firstChargeId()).isEqualTo("ch-1");
        assertThat(sub.expiresAt().toInstant()).isEqualTo(newExpiry);
        assertThat(events).containsExactly("paid:" + o.id(), "renewed:" + sub.id() + ":ch-2");
    }

    @Test
    void renewalAfterExpiryReactivates() {
        StarOrder o = createdOrder("vip");
        pay(payment(o.payload(), "ch-1", 250, T0.plus(Duration.ofDays(30)).getEpochSecond()));
        String sid = orders.findById(o.id()).orElseThrow().subscriptionId();
        subs.save(subs.findById(sid).orElseThrow().withStatus(StarSubscriptionStatus.EXPIRED));

        clock.advance(Duration.ofDays(40));
        assertThat(handler.completePayment(1000L, 42, o.payload(), "ch-2", 250, null)).isTrue();

        StarSubscription sub = subs.findById(sid).orElseThrow();
        assertThat(sub.status()).isEqualTo(StarSubscriptionStatus.ACTIVE);
        assertThat(sub.expiresAt().toInstant()).isEqualTo(T0.plus(Duration.ofDays(70)));
    }

    @Test
    void renewalUsesOriginalCreditsWhenProductRemoved() {
        StarOrder o = createdOrder("vip");
        pay(payment(o.payload(), "ch-1", 250, T0.plus(Duration.ofDays(30)).getEpochSecond()));
        props.getProducts().remove("vip");
        StarPaymentService noVip = new StarPaymentService(main, new PropertiesStarCatalog(props), orders, subs,
                null, recorder, props, clock);
        StarPaymentUpdateHandler h = new StarPaymentUpdateHandler(noVip, orders, subs, null);

        assertThat(h.completePayment(1000L, 42, o.payload(), "ch-2", 250, null)).isTrue();
        assertThat(orders.findByChargeId("ch-2").orElseThrow().credits()).isEqualTo(30);
    }

    @Test
    void lateRecoveredRenewalDoesNotShortenSubscription() {
        StarOrder o = createdOrder("vip");
        pay(payment(o.payload(), "ch-1", 250, T0.plus(Duration.ofDays(30)).getEpochSecond()));
        pay(payment(o.payload(), "ch-3", 250, T0.plus(Duration.ofDays(90)).getEpochSecond()));
        String sid = orders.findById(o.id()).orElseThrow().subscriptionId();

        // ch-2 was missed live and is recovered after ch-3
        assertThat(handler.completePayment(1000L, 42, o.payload(), "ch-2", 250,
                at(T0.plus(Duration.ofDays(60))))).isTrue();

        StarSubscription sub = subs.findById(sid).orElseThrow();
        assertThat(sub.expiresAt().toInstant()).isEqualTo(T0.plus(Duration.ofDays(90)));
        assertThat(sub.lastChargeId()).isEqualTo("ch-3");
        assertThat(events).contains("renewed:" + sid + ":ch-2");
    }

    @Test
    void subscriptionRowMissingAfterCrashIsCreatedOnRedelivery() {
        StarOrder o = createdOrder("vip");
        orders.markPaid(o.id(), "ch-1", at(T0));   // crashed right after markPaid

        handler.deliver(orders.findById(o.id()).orElseThrow());

        StarOrder stored = orders.findById(o.id()).orElseThrow();
        assertThat(stored.subscriptionId()).isNotNull();
        assertThat(stored.fulfilledAt()).isNotNull();
        StarSubscription sub = subs.findById(stored.subscriptionId()).orElseThrow();
        assertThat(sub.expiresAt().toInstant()).isEqualTo(T0.plus(Duration.ofDays(30)));
        assertThat(events).containsExactly("paid:" + o.id());
    }

    // --- refunds and delivery ---

    @Test
    void externalRefundFiresEventOnce() {
        StarOrder o = createdOrder("pack");
        pay(payment(o.payload(), "ch-1", 100, null));

        main.getRefundedPaymentHandler().accept(refunded(o.payload(), "ch-1"));
        main.getRefundedPaymentHandler().accept(refunded(o.payload(), "ch-1"));
        main.getRefundedPaymentHandler().accept(refunded(o.payload(), "ch-unknown"));

        assertThat(orders.findById(o.id()).orElseThrow().status()).isEqualTo(StarOrderStatus.REFUNDED);
        assertThat(events).containsExactly("paid:" + o.id(), "refunded:" + o.id() + ":EXTERNAL");
    }

    @Test
    void listenerFailureLeavesOrderUnfulfilled() {
        StarOrder o = createdOrder("pack");
        failListener = true;
        pay(payment(o.payload(), "ch-1", 100, null));

        StarOrder stored = orders.findById(o.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(StarOrderStatus.PAID);
        assertThat(stored.fulfilledAt()).isNull();
        assertThat(orders.findPaidUnfulfilled(10)).extracting(StarOrder::id).containsExactly(o.id());

        failListener = false;
        handler.deliver(stored);
        assertThat(orders.findById(o.id()).orElseThrow().fulfilledAt()).isNotNull();
        assertThat(events).containsExactly("paid:" + o.id());
    }

    @Test
    void refundLandingBeforeSubscriptionLinkIsNotReverted() {
        InMemoryStarOrderStore racing = new InMemoryStarOrderStore() {
            private void refundFirst(String id) {
                markRefunded(id, at(clock.instant()));   // refunded_payment processed concurrently
            }

            @Override
            public synchronized void save(StarOrder o) {
                if (o.status() == StarOrderStatus.PAID && o.subscriptionId() != null) {
                    refundFirst(o.id());
                }
                super.save(o);
            }

            @Override
            public synchronized boolean linkSubscription(String id, String subscriptionId) {
                refundFirst(id);
                return super.linkSubscription(id, subscriptionId);
            }
        };
        StarPaymentService s = new StarPaymentService(main, new PropertiesStarCatalog(props), racing, subs, null,
                recorder, props, clock);
        StarPaymentUpdateHandler h = new StarPaymentUpdateHandler(s, racing, subs, null);
        StarOrder o = s.createInvoiceLink(42, "vip").order();

        assertThat(h.completePayment(1000L, 42, o.payload(), "ch-1", 250, at(T0.plus(Duration.ofDays(30)))))
                .isTrue();

        StarOrder stored = racing.findById(o.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(StarOrderStatus.REFUNDED);
        assertThat(stored.subscriptionId()).isNotNull();
        assertThat(racing.findPaidUnfulfilled(10)).isEmpty();
        assertThat(events).isEmpty();
    }

    /** A redelivery whose existence check is stale must not reset a subscription canceled meanwhile. */
    @Test
    void redeliveryWithAStaleCheckDoesNotOverwriteTheSubscription() {
        AtomicBoolean stale = new AtomicBoolean();
        InMemoryStarSubscriptionStore racingSubs = new InMemoryStarSubscriptionStore() {
            @Override
            public Optional<StarSubscription> findById(String id) {
                return stale.getAndSet(false) ? Optional.empty() : super.findById(id);
            }
        };
        StarPaymentService s = new StarPaymentService(main, new PropertiesStarCatalog(props), orders, racingSubs,
                null, recorder, props, clock);
        StarPaymentUpdateHandler h = new StarPaymentUpdateHandler(s, orders, racingSubs, null);
        StarOrder o = s.createInvoiceLink(42, "vip").order();
        failListener = true;
        h.completePayment(1000L, 42, o.payload(), "ch-1", 250, at(T0.plus(Duration.ofDays(30))));
        StarOrder stored = orders.findById(o.id()).orElseThrow();
        racingSubs.updateLiveStatus(stored.subscriptionId(), StarSubscriptionStatus.CANCELED);

        failListener = false;
        stale.set(true);
        h.deliver(stored);

        StarSubscription sub = racingSubs.findById(stored.subscriptionId()).orElseThrow();
        assertThat(sub.status()).isEqualTo(StarSubscriptionStatus.CANCELED);
        assertThat(orders.findById(o.id()).orElseThrow().fulfilledAt()).isNotNull();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void externalRefundStoreFailureIsLoggedWithTheChargeId(CapturedOutput output) {
        InMemoryStarOrderStore broken = new InMemoryStarOrderStore() {
            @Override
            public synchronized boolean markRefunded(String id, OffsetDateTime at) {
                throw new IllegalStateException("db down");
            }
        };
        StarPaymentService s = new StarPaymentService(main, new PropertiesStarCatalog(props), broken, subs, null,
                recorder, props, clock);
        StarPaymentUpdateHandler h = new StarPaymentUpdateHandler(s, broken, subs, null);
        h.register(main);
        StarOrder o = s.sendInvoice(7, 42, "pack");
        h.completePayment(1000L, 42, o.payload(), "ch-9", 100, null);

        assertThatCode(() -> main.getRefundedPaymentHandler().accept(refunded(o.payload(), "ch-9")))
                .doesNotThrowAnyException();

        assertThat(output).contains("ERROR").contains("ch-9");
    }
}
