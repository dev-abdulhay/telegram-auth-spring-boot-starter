package io.github.dev_abdulhay.telegramauth.payments.balance;

import io.github.dev_abdulhay.telegramauth.payments.BalanceScope;
import io.github.dev_abdulhay.telegramauth.payments.RefundPolicy;
import io.github.dev_abdulhay.telegramauth.payments.RefundSource;
import io.github.dev_abdulhay.telegramauth.payments.StarOrder;
import io.github.dev_abdulhay.telegramauth.payments.StarOrderKind;
import io.github.dev_abdulhay.telegramauth.payments.StarOrderStatus;
import io.github.dev_abdulhay.telegramauth.payments.StarSubscription;
import io.github.dev_abdulhay.telegramauth.payments.StarSubscriptionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TopUpCreditorTest {

    static final BalanceAccountKey GLOBAL_42 = new BalanceAccountKey(null, 42L);
    static final String SPENT = "Credits from this payment were already spent";

    final InMemoryBalanceLedger ledger = new InMemoryBalanceLedger();

    static StarOrder paid(Long botId, StarOrderKind kind, String product, long credits, String chargeId) {
        OffsetDateTime now = OffsetDateTime.now();
        return new StarOrder("o-" + chargeId, botId, 42L, product, kind, 50, credits, StarOrderStatus.PAID,
                chargeId, null, now, now.plusMinutes(30), now, null, null);
    }

    TopUpCreditor creditor(BalanceScope scope, RefundPolicy policy) {
        return new TopUpCreditor(ledger, scope, policy);
    }

    @Test
    void paidCreditsOnce() {
        TopUpCreditor c = creditor(BalanceScope.GLOBAL, RefundPolicy.REQUIRE_BALANCE);
        StarOrder o = paid(5L, StarOrderKind.TOP_UP, "top-up", 100, "ch1");

        c.onPaid(o);
        c.onPaid(o);

        assertThat(ledger.balance(GLOBAL_42)).isEqualTo(100);
        assertThat(ledger.history(GLOBAL_42, 0, 10)).singleElement().satisfies(e -> {
            assertThat(e.refKey()).isEqualTo("pay:ch1");
            assertThat(e.reason()).isEqualTo("TOP_UP:top-up");
        });
        assertThat(c.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void renewalCredits() {
        TopUpCreditor c = creditor(BalanceScope.GLOBAL, RefundPolicy.REQUIRE_BALANCE);
        StarSubscription sub = new StarSubscription("s1", null, 42L, "vip", "ch1", "ch2",
                StarSubscriptionStatus.ACTIVE, OffsetDateTime.now().plusDays(30), OffsetDateTime.now());
        StarOrder renewal = paid(null, StarOrderKind.RENEWAL, "vip", 30, "ch2");

        c.onSubscriptionRenewed(sub, renewal);
        c.onSubscriptionRenewed(sub, renewal);

        assertThat(ledger.balance(GLOBAL_42)).isEqualTo(30);
        assertThat(ledger.history(GLOBAL_42, 0, 10)).extracting(LedgerEntry::refKey).containsExactly("pay:ch2");
    }

    @Test
    void zeroCreditsSkipsLedger() {
        BalanceLedger untouchable = (BalanceLedger) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{BalanceLedger.class}, (p, m, a) -> {
                    throw new AssertionError("ledger must not be called: " + m.getName());
                });
        TopUpCreditor c = new TopUpCreditor(untouchable, BalanceScope.GLOBAL, RefundPolicy.REQUIRE_BALANCE);
        StarOrder o = paid(null, StarOrderKind.PURCHASE, "badge", 0, "ch1");

        c.onPaid(o);
        c.onRefunded(o, RefundSource.BOT);
        c.onRefunded(o, RefundSource.EXTERNAL);

        assertThat(c.check(o)).isEmpty();
    }

    @Test
    void perBotScopeUsesBotId() {
        TopUpCreditor c = creditor(BalanceScope.PER_BOT, RefundPolicy.REQUIRE_BALANCE);

        c.onPaid(paid(5L, StarOrderKind.TOP_UP, "top-up", 100, "ch1"));
        c.onPaid(paid(null, StarOrderKind.TOP_UP, "top-up", 7, "ch2"));

        assertThat(ledger.balance(new BalanceAccountKey(5L, 42L))).isEqualTo(100);
        assertThat(ledger.balance(GLOBAL_42)).isEqualTo(7);
        assertThat(c.keyFor(5L, 42L)).isEqualTo(new BalanceAccountKey(5L, 42L));
        assertThat(creditor(BalanceScope.GLOBAL, RefundPolicy.REQUIRE_BALANCE).keyFor(5L, 42L))
                .isEqualTo(GLOBAL_42);
    }

    @Test
    void guardVetoesWhenSpent() {
        TopUpCreditor c = creditor(BalanceScope.GLOBAL, RefundPolicy.REQUIRE_BALANCE);
        StarOrder o = paid(null, StarOrderKind.TOP_UP, "top-up", 100, "ch1");
        c.onPaid(o);

        assertThat(c.check(o)).isEmpty();
        ledger.debit(GLOBAL_42, 1, "spend:1", "spend");
        assertThat(c.check(o)).contains(SPENT);
        assertThat(creditor(BalanceScope.GLOBAL, RefundPolicy.ALLOW_NEGATIVE).check(o)).isEmpty();
    }

    @Test
    void botRefundDebitsBalanceOnce() {
        TopUpCreditor c = creditor(BalanceScope.GLOBAL, RefundPolicy.REQUIRE_BALANCE);
        StarOrder o = paid(null, StarOrderKind.TOP_UP, "top-up", 100, "ch1");
        c.onPaid(o);

        c.onRefunded(o, RefundSource.BOT);
        c.onRefunded(o, RefundSource.BOT);

        assertThat(ledger.balance(GLOBAL_42)).isZero();
        assertThat(ledger.history(GLOBAL_42, 0, 1)).first().extracting(LedgerEntry::refKey).isEqualTo("refund:ch1");
    }

    @Test
    void botRefundRacingASpendStillRecordsTheRefund() {
        TopUpCreditor c = creditor(BalanceScope.GLOBAL, RefundPolicy.REQUIRE_BALANCE);
        StarOrder o = paid(null, StarOrderKind.TOP_UP, "top-up", 100, "ch1");
        c.onPaid(o);
        ledger.debit(GLOBAL_42, 40, "spend:1", "spend");   // spent after the guard passed

        c.onRefunded(o, RefundSource.BOT);

        assertThat(ledger.balance(GLOBAL_42)).isEqualTo(-40);
    }

    @Test
    void externalRefundGoesNegative() {
        TopUpCreditor c = creditor(BalanceScope.GLOBAL, RefundPolicy.REQUIRE_BALANCE);
        StarOrder o = paid(null, StarOrderKind.TOP_UP, "top-up", 100, "ch1");
        c.onPaid(o);
        ledger.debit(GLOBAL_42, 60, "spend:1", "spend");

        c.onRefunded(o, RefundSource.EXTERNAL);
        c.onRefunded(o, RefundSource.EXTERNAL);

        assertThat(ledger.balance(GLOBAL_42)).isEqualTo(-60);
    }

    @Test
    void allowNegativePolicyRefunds() {
        TopUpCreditor c = creditor(BalanceScope.GLOBAL, RefundPolicy.ALLOW_NEGATIVE);
        StarOrder o = paid(null, StarOrderKind.TOP_UP, "top-up", 100, "ch1");
        c.onPaid(o);
        ledger.debit(GLOBAL_42, 100, "spend:1", "spend");

        assertThat(c.check(o)).isEmpty();
        c.onRefunded(o, RefundSource.BOT);

        assertThat(ledger.balance(GLOBAL_42)).isEqualTo(-100);
    }

    /** The credit failed in onPaid, so the order was never credited: a refund must not take other credits. */
    @Test
    void refundOfAnUncreditedOrderLeavesTheBalanceAlone() {
        TopUpCreditor c = creditor(BalanceScope.GLOBAL, RefundPolicy.REQUIRE_BALANCE);
        ledger.credit(GLOBAL_42, 500, "pay:other", "TOP_UP:top-up");
        StarOrder o = paid(null, StarOrderKind.TOP_UP, "top-up", 100, "ch1");

        assertThat(c.check(o)).isEmpty();
        c.onRefunded(o, RefundSource.BOT);
        c.onRefunded(o, RefundSource.EXTERNAL);
        creditor(BalanceScope.GLOBAL, RefundPolicy.ALLOW_NEGATIVE).onRefunded(o, RefundSource.BOT);

        assertThat(ledger.balance(GLOBAL_42)).isEqualTo(500);
        assertThat(ledger.hasEntry("refund:ch1")).isFalse();
    }

    @Test
    void guardDoesNotRequireBalanceForAnUncreditedOrder() {
        TopUpCreditor c = creditor(BalanceScope.GLOBAL, RefundPolicy.REQUIRE_BALANCE);

        assertThat(c.check(paid(null, StarOrderKind.TOP_UP, "top-up", 100, "ch1"))).isEmpty();
    }
}
