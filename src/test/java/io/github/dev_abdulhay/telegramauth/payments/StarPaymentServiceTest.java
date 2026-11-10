package io.github.dev_abdulhay.telegramauth.payments;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StarPaymentServiceTest {

    static final Instant T0 = Instant.parse("2026-11-10T12:00:00Z");

    final MutableClock clock = new MutableClock(T0);
    final FakeStarBot mainBot = new FakeStarBot();
    final TelegramBotModule main = FakeStarBot.module(mainBot, 1000L);
    final InMemoryStarOrderStore orders = new InMemoryStarOrderStore();
    final InMemoryStarSubscriptionStore subs = new InMemoryStarSubscriptionStore();
    final List<String> events = new ArrayList<>();
    final TelegramPaymentsProperties props = props();
    StarRefundGuard guard = null;

    static TelegramPaymentsProperties props() {
        TelegramPaymentsProperties p = new TelegramPaymentsProperties();
        p.getProducts().put("pack", product("Pack", 100, 500, false));
        p.getProducts().put("vip", product("VIP", 250, 30, true));
        p.getTopUp().setEnabled(true);
        p.getTopUp().setCreditsPerStar(2);
        return p;
    }

    static TelegramPaymentsProperties.Product product(String title, int stars, long credits, boolean sub) {
        TelegramPaymentsProperties.Product p = new TelegramPaymentsProperties.Product();
        p.setTitle(title);
        p.setDescription(title + " desc");
        p.setStars(stars);
        p.setCredits(credits);
        p.setSubscription(sub);
        return p;
    }

    StarPaymentService service() {
        StarPaymentEvents recorder = new StarPaymentEvents() {
            @Override
            public void onRefunded(StarOrder order, RefundSource source) {
                events.add("refunded:" + order.id() + ":" + source + ":" + order.status());
            }
        };
        return new StarPaymentService(main, new PropertiesStarCatalog(props), orders, subs, order -> guard == null
                ? Optional.empty() : guard.check(order), recorder, props, clock);
    }

    StarOrder paidOrder() {
        OffsetDateTime now = OffsetDateTime.ofInstant(T0, ZoneOffset.UTC);
        StarOrder o = new StarOrder(StarOrderIds.newId(), 1000L, 42, "pack", StarOrderKind.PURCHASE, 100, 500,
                StarOrderStatus.PAID, "ch-1", null, now, now.plusMinutes(30), now, now, null);
        orders.save(o);
        return o;
    }

    @Test
    void sendInvoiceSavesOrderThenCallsApi() {
        StarOrder o = service().sendInvoice(7, 42, "pack");

        assertThat(mainBot.invoices).hasSize(1);
        FakeStarBot.Invoice inv = mainBot.invoices.get(0);
        assertThat(inv.payload()).isEqualTo("so:" + o.id());
        assertThat(inv.stars()).isEqualTo(100);
        assertThat(inv.chatId()).isEqualTo(7L);
        assertThat(inv.title()).isEqualTo("Pack");
        StarOrder stored = orders.findById(o.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(StarOrderStatus.CREATED);
        assertThat(stored.kind()).isEqualTo(StarOrderKind.PURCHASE);
        assertThat(stored.credits()).isEqualTo(500);
        assertThat(stored.botId()).isEqualTo(1000L);
        assertThat(stored.userId()).isEqualTo(42);
        assertThat(stored.expiresAt().toInstant()).isEqualTo(T0.plus(Duration.ofMinutes(30)));
        assertThat(o.id()).matches("[0-9a-f]{32}");
    }

    @Test
    void sendInvoiceSavesOrderBeforeApiCall() {
        StarOrder[] seen = new StarOrder[1];
        FakeStarBot bot = new FakeStarBot() {
            @Override
            public com.fasterxml.jackson.databind.JsonNode sendInvoice(long chatId, String title, String d,
                                                                       String payload, int stars) {
                seen[0] = orders.findById(payload.substring(3)).orElse(null);
                return super.sendInvoice(chatId, title, d, payload, stars);
            }
        };
        StarPaymentService s = new StarPaymentService(FakeStarBot.module(bot, 1000L),
                new PropertiesStarCatalog(props), orders, subs, null, new StarPaymentEvents() { }, props, clock);

        s.sendInvoice(7, 42, "pack");

        assertThat(seen[0]).isNotNull();
    }

    @Test
    void topUpComputesCredits() {
        StarOrder o = service().sendTopUpInvoice(7, 42, 50);

        StarOrder stored = orders.findById(o.id()).orElseThrow();
        assertThat(stored.credits()).isEqualTo(100);
        assertThat(stored.stars()).isEqualTo(50);
        assertThat(stored.kind()).isEqualTo(StarOrderKind.TOP_UP);
        assertThat(stored.productCode()).isEqualTo("top-up");
        assertThat(mainBot.invoices.get(0).stars()).isEqualTo(50);
        assertThat(mainBot.invoices.get(0).title()).isEqualTo(props.getTopUp().getTitle());
    }

    @Test
    void topUpOutOfRangeRejected() {
        StarPaymentService s = service();
        assertThatThrownBy(() -> s.sendTopUpInvoice(7, 42, 9)).isInstanceOf(StarPaymentException.class);
        assertThatThrownBy(() -> s.sendTopUpInvoice(7, 42, 10001)).isInstanceOf(StarPaymentException.class);
        props.getTopUp().setEnabled(false);
        assertThatThrownBy(() -> s.sendTopUpInvoice(7, 42, 50)).isInstanceOf(StarPaymentException.class);
        assertThat(mainBot.invoices).isEmpty();
    }

    @Test
    void unknownProductRejected() {
        assertThatThrownBy(() -> service().sendInvoice(7, 42, "nope"))
                .isInstanceOf(StarPaymentException.class).hasMessage("unknown product: nope");
    }

    @Test
    void subscriptionViaSendInvoiceRejected() {
        assertThatThrownBy(() -> service().sendInvoice(7, 42, "vip"))
                .isInstanceOf(StarPaymentException.class).hasMessageContaining("createInvoiceLink");
        assertThat(mainBot.invoices).isEmpty();
    }

    @Test
    void createInvoiceLinkPassesPeriodForSubscription() {
        StarPaymentService s = service();
        InvoiceLink sub = s.createInvoiceLink(42, "vip");
        InvoiceLink oneOff = s.createInvoiceLink(42, "pack");

        assertThat(sub.url()).isEqualTo("https://t.me/$link");
        assertThat(mainBot.invoices.get(0).period()).isEqualTo(2592000);
        assertThat(mainBot.invoices.get(0).payload()).isEqualTo("so:" + sub.order().id());
        assertThat(mainBot.invoices.get(1).period()).isNull();
        assertThat(orders.findById(oneOff.order().id())).isPresent();
    }

    @Test
    void tenantInvoiceUsesTenantBotAndStoresBotId() {
        FakeStarBot tenantBot = new FakeStarBot();
        StarPaymentService s = service();
        s.registerBot(FakeStarBot.module(tenantBot, 2000L));

        StarOrder o = s.sendInvoice(2000L, 7, 42, "pack");

        assertThat(tenantBot.invoices).hasSize(1);
        assertThat(mainBot.invoices).isEmpty();
        assertThat(orders.findById(o.id()).orElseThrow().botId()).isEqualTo(2000L);
        assertThat(s.tenantBots()).containsOnlyKeys(2000L);
        assertThat(s.moduleFor(null)).isSameAs(main);
        assertThat(s.moduleFor(1000L)).isSameAs(main);
    }

    @Test
    void unknownTenantRejected() {
        StarPaymentService s = service();
        assertThatThrownBy(() -> s.sendInvoice(3000L, 7, 42, "pack")).isInstanceOf(StarPaymentException.class);
        assertThatThrownBy(() -> s.moduleFor(3000L)).isInstanceOf(StarPaymentException.class);
    }

    @Test
    void refundCallsApiMarksRefundedFiresEvent() {
        StarOrder o = paidOrder();

        service().refund(o.id());

        assertThat(mainBot.refunds).containsExactly("42:ch-1");
        assertThat(orders.findById(o.id()).orElseThrow().status()).isEqualTo(StarOrderStatus.REFUNDED);
        assertThat(events).containsExactly("refunded:" + o.id() + ":BOT:REFUNDED");
    }

    @Test
    void refundVetoedByGuard() {
        StarOrder o = paidOrder();
        guard = order -> Optional.of("credits already spent");

        assertThatThrownBy(() -> service().refund(o.id()))
                .isInstanceOf(StarPaymentException.class).hasMessageContaining("credits already spent");
        assertThat(mainBot.refunds).isEmpty();
        assertThat(orders.findById(o.id()).orElseThrow().status()).isEqualTo(StarOrderStatus.PAID);
        assertThat(events).isEmpty();
    }

    @Test
    void refundOfUnpaidRejected() {
        StarOrder o = service().sendInvoice(7, 42, "pack");

        assertThatThrownBy(() -> service().refund(o.id())).isInstanceOf(StarPaymentException.class);
        assertThatThrownBy(() -> service().refund("missing")).isInstanceOf(StarPaymentException.class);
        assertThat(mainBot.refunds).isEmpty();
    }

    @Test
    void cancelSubscriptionUsesLastChargeId() {
        OffsetDateTime now = OffsetDateTime.ofInstant(T0, ZoneOffset.UTC);
        subs.save(new StarSubscription("s1", 1000L, 42, "vip", "ch-first", "ch-last",
                StarSubscriptionStatus.ACTIVE, now.plusDays(30), now));
        StarPaymentService s = service();

        s.cancelSubscription("s1");
        assertThat(mainBot.subscriptionEdits).containsExactly("42:ch-last:true");
        assertThat(subs.findById("s1").orElseThrow().status()).isEqualTo(StarSubscriptionStatus.CANCELED);

        s.resumeSubscription("s1");
        assertThat(mainBot.subscriptionEdits).containsExactly("42:ch-last:true", "42:ch-last:false");
        assertThat(subs.findById("s1").orElseThrow().status()).isEqualTo(StarSubscriptionStatus.ACTIVE);

        assertThatThrownBy(() -> s.cancelSubscription("missing")).isInstanceOf(StarPaymentException.class);
    }

    @Test
    void botStarBalanceUsesBotOfId() {
        mainBot.balance = 77;
        assertThat(service().botStarBalance(null)).isEqualTo(77);
    }

    @Test
    void compositeCallsAllAndRethrowsFirst() {
        List<String> calls = new ArrayList<>();
        RuntimeException first = new IllegalStateException("first");
        StarPaymentEvents a = new StarPaymentEvents() {
            @Override
            public void onPaid(StarOrder order) {
                calls.add("a");
                throw first;
            }
        };
        StarPaymentEvents b = new StarPaymentEvents() {
            @Override
            public void onPaid(StarOrder order) {
                calls.add("b");
                throw new IllegalStateException("second");
            }
        };
        StarPaymentEvents c = new StarPaymentEvents() {
            @Override
            public void onPaid(StarOrder order) {
                calls.add("c");
            }
        };
        CompositeStarPaymentEvents composite = new CompositeStarPaymentEvents(List.of(a, b, c));

        assertThatThrownBy(() -> composite.onPaid(paidOrder())).isSameAs(first);
        assertThat(calls).containsExactly("a", "b", "c");
    }
}
