package io.github.dev_abdulhay.telegramauth.payments;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class StarPaymentReconcilerTest {

    static final Instant T0 = StarPaymentServiceTest.T0;

    final MutableClock clock = new MutableClock(T0);
    final FakeStarBot bot = new FakeStarBot();
    final TelegramBotModule main = FakeStarBot.module(bot, 1000L);
    final InMemoryStarOrderStore orders = new InMemoryStarOrderStore();
    final InMemoryStarSubscriptionStore subs = new InMemoryStarSubscriptionStore();
    final TelegramPaymentsProperties props = StarPaymentServiceTest.props();
    final List<String> events = new CopyOnWriteArrayList<>();
    volatile boolean failListener;

    final StarPaymentEvents recorder = new StarPaymentEvents() {
        @Override
        public void onPaid(StarOrder order) {
            if (failListener) {
                throw new IllegalStateException("listener down");
            }
            events.add("paid:" + order.id());
        }

        @Override
        public void onSubscriptionRenewed(StarSubscription sub, StarOrder renewal) {
            events.add("renewed:" + renewal.chargeId());
        }

        @Override
        public void onSubscriptionExpired(StarSubscription sub) {
            events.add("expired:" + sub.id() + ":" + sub.status());
        }
    };

    final StarPaymentService service = new StarPaymentService(main, new PropertiesStarCatalog(props), orders, subs,
            null, recorder, props, clock);
    final StarPaymentUpdateHandler handler = new StarPaymentUpdateHandler(service, orders, subs, null);
    final StarPaymentReconciler reconciler = new StarPaymentReconciler(service, handler, orders, subs);

    static OffsetDateTime at(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    @Test
    void missedPaymentIsRecovered() {
        StarOrder o = service.sendInvoice(7, 42, "pack");
        bot.addUserTransaction("ch-1", 42, o.payload(), 100, T0.getEpochSecond(), null);

        ReconcileReport r = reconciler.reconcile();

        assertThat(r.recovered()).isEqualTo(1);
        StarOrder stored = orders.findById(o.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(StarOrderStatus.PAID);
        assertThat(stored.chargeId()).isEqualTo("ch-1");
        assertThat(events).containsExactly("paid:" + o.id());
        assertThat(reconciler.reconcile().recovered()).isZero();
        assertThat(events).hasSize(1);
    }

    @Test
    void latePaymentOnExpiredOrderHonored() {
        StarOrder o = service.sendInvoice(7, 42, "pack");
        clock.advance(Duration.ofHours(1));
        orders.expireCreatedBefore(at(clock.instant()));
        bot.addUserTransaction("ch-1", 42, o.payload(), 100, T0.getEpochSecond(), null);

        assertThat(reconciler.reconcile().recovered()).isEqualTo(1);
        assertThat(orders.findById(o.id()).orElseThrow().status()).isEqualTo(StarOrderStatus.PAID);
        assertThat(events).containsExactly("paid:" + o.id());
    }

    @Test
    void alreadyRecordedChargeSkipped() {
        StarOrder o = service.sendInvoice(7, 42, "pack");
        handler.completePayment(1000L, 42, o.payload(), "ch-1", 100, null);
        bot.addUserTransaction("ch-1", 42, o.payload(), 100, T0.getEpochSecond(), null);
        bot.addUserTransaction("ch-out", 42, "not-ours", 5, T0.getEpochSecond(), null);

        ReconcileReport r = reconciler.reconcile();

        assertThat(r.recovered()).isZero();
        assertThat(events).containsExactly("paid:" + o.id());
    }

    @Test
    void missedRenewalIsRecoveredWithTransactionExpiry() {
        StarOrder o = service.createInvoiceLink(42, "vip").order();
        handler.completePayment(1000L, 42, o.payload(), "ch-1", 250, at(T0.plus(Duration.ofDays(30))));
        Instant renewedAt = T0.plus(Duration.ofDays(30));
        clock.advance(Duration.ofDays(31));
        bot.addUserTransaction("ch-1", 42, o.payload(), 250, T0.getEpochSecond(), 2592000);
        bot.addUserTransaction("ch-2", 42, o.payload(), 250, renewedAt.getEpochSecond(), 2592000);

        assertThat(reconciler.reconcile().recovered()).isEqualTo(1);

        StarSubscription sub = subs.findById(orders.findById(o.id()).orElseThrow().subscriptionId()).orElseThrow();
        assertThat(sub.lastChargeId()).isEqualTo("ch-2");
        assertThat(sub.expiresAt().toInstant()).isEqualTo(renewedAt.plus(Duration.ofDays(30)));
        assertThat(events).containsExactly("paid:" + o.id(), "renewed:ch-2");
    }

    @Test
    void transactionsArePagedAndCursorAdvancesOverFullPages() {
        for (int i = 0; i < 150; i++) {
            bot.addUserTransaction("noise-" + i, 1, "x", 1, T0.getEpochSecond(), null);
        }
        StarOrder o = service.sendInvoice(7, 42, "pack");
        bot.addUserTransaction("ch-1", 42, o.payload(), 100, T0.getEpochSecond(), null);

        assertThat(reconciler.reconcile().recovered()).isEqualTo(1);
        StarOrder o2 = service.sendInvoice(7, 42, "pack");
        bot.addUserTransaction("ch-2", 42, o2.payload(), 100, T0.getEpochSecond(), null);
        assertThat(reconciler.reconcile().recovered()).isEqualTo(1);
    }

    @Test
    void unfulfilledPaidOrderIsRedelivered() {
        StarOrder o = service.sendInvoice(7, 42, "pack");
        failListener = true;
        handler.completePayment(1000L, 42, o.payload(), "ch-1", 100, null);
        assertThat(events).isEmpty();

        failListener = false;
        ReconcileReport r = reconciler.reconcile();

        assertThat(r.redelivered()).isEqualTo(1);
        assertThat(orders.findById(o.id()).orElseThrow().fulfilledAt()).isNotNull();
        assertThat(events).containsExactly("paid:" + o.id());
        assertThat(reconciler.reconcile().redelivered()).isZero();
    }

    @Test
    void staleOrdersExpireAndPurge() {
        StarOrder old = service.sendInvoice(7, 42, "pack");
        clock.advance(Duration.ofDays(91));
        StarOrder fresh = service.sendInvoice(7, 42, "pack");
        clock.advance(Duration.ofHours(1));
        orders.expireCreatedBefore(at(T0.plus(Duration.ofHours(1))));   // old expired long ago

        ReconcileReport r = reconciler.reconcile();

        assertThat(r.purgedOrders()).isEqualTo(1);
        assertThat(r.expiredOrders()).isEqualTo(1);
        assertThat(orders.findById(old.id())).isEmpty();
        assertThat(orders.findById(fresh.id()).orElseThrow().status()).isEqualTo(StarOrderStatus.EXPIRED);
    }

    @Test
    void subscriptionExpiresAfterGrace() {
        subs.save(new StarSubscription("s1", 1000L, 42, "vip", "ch-1", "ch-1", StarSubscriptionStatus.ACTIVE,
                at(T0.plus(Duration.ofDays(30))), at(T0)));
        clock.advance(Duration.ofDays(30).plusMinutes(30));
        assertThat(reconciler.reconcile().expiredSubscriptions()).isZero();

        clock.advance(Duration.ofMinutes(31));
        assertThat(reconciler.reconcile().expiredSubscriptions()).isEqualTo(1);
        assertThat(reconciler.reconcile().expiredSubscriptions()).isZero();

        assertThat(subs.findById("s1").orElseThrow().status()).isEqualTo(StarSubscriptionStatus.EXPIRED);
        assertThat(events).containsExactly("expired:s1:EXPIRED");
    }

    @Test
    void oneBotFailingDoesNotStopOthers() {
        bot.failTransactions = true;
        FakeStarBot tenantBot = new FakeStarBot();
        service.registerBot(FakeStarBot.module(tenantBot, 2000L));
        StarOrder o = service.sendInvoice(2000L, 7, 42, "pack");
        tenantBot.addUserTransaction("ch-1", 42, o.payload(), 100, T0.getEpochSecond(), null);

        ReconcileReport r = reconciler.reconcile();

        assertThat(r.recovered()).isEqualTo(1);
        StarOrder stored = orders.findById(o.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(StarOrderStatus.PAID);
        assertThat(stored.botId()).isEqualTo(2000L);
    }

    @Test
    void lifecycleRunsOnDaemonThreadAndStops() throws Exception {
        props.getReconcile().setInterval(Duration.ofMillis(50));
        StarOrder o = service.sendInvoice(7, 42, "pack");
        bot.addUserTransaction("ch-1", 42, o.payload(), 100, T0.getEpochSecond(), null);

        reconciler.start();
        assertThat(reconciler.isRunning()).isTrue();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (orders.findById(o.id()).orElseThrow().status() != StarOrderStatus.PAID
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(Thread.getAllStackTraces().keySet()).filteredOn(t -> t.getName().equals("star-reconcile"))
                .allMatch(Thread::isDaemon).isNotEmpty();
        reconciler.stop();

        assertThat(reconciler.isRunning()).isFalse();
        assertThat(orders.findById(o.id()).orElseThrow().status()).isEqualTo(StarOrderStatus.PAID);
        Thread.sleep(100);
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(t -> t.getName().equals("star-reconcile"));
    }
}
