package io.github.dev_abdulhay.telegramauth.payments.balance;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contract every {@link BalanceLedger} must satisfy. Subclass per implementation. */
public abstract class BalanceLedgerContract {

    protected static final BalanceAccountKey ACC = new BalanceAccountKey(null, 1L);

    protected abstract BalanceLedger ledger();

    @Test
    void acceptsRefKeysBuiltFromRealisticTelegramChargeIds() {
        // real charge ids are ~200 chars; TopUpCreditor prefixes them with "pay:" / "refund:"
        String chargeId = "stxCh_" + "Ab9-".repeat(50).substring(0, 194);
        String pay = "pay:" + chargeId;
        String refund = "refund:" + chargeId;

        assertThat(ledger().credit(ACC, 10, pay, "x").applied()).isTrue();
        assertThat(ledger().debit(ACC, 4, refund, "x").applied()).isTrue();

        assertThat(ledger().hasEntry(pay)).isTrue();
        assertThat(ledger().hasEntry(refund)).isTrue();
        assertThat(ledger().balance(ACC)).isEqualTo(6);
    }

    @Test
    void hasEntryReportsOnlyAppliedRefKeys() {
        assertThat(ledger().hasEntry("pay:c1")).isFalse();
        ledger().credit(ACC, 100, "pay:c1", "TOP_UP:pack");
        assertThatThrownBy(() -> ledger().debit(ACC, 500, "spend:big", "spend"))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(ledger().hasEntry("pay:c1")).isTrue();
        assertThat(ledger().hasEntry("spend:big")).isFalse();
    }

    @Test
    void creditThenBalance() {
        assertThat(ledger().balance(ACC)).isZero();

        LedgerResult r = ledger().credit(ACC, 100, "pay:c1", "TOP_UP:pack");

        assertThat(r).isEqualTo(new LedgerResult(true, 100));
        assertThat(ledger().balance(ACC)).isEqualTo(100);
    }

    @Test
    void replaySameRefKeyIsNoop() {
        ledger().credit(ACC, 100, "pay:c1", "TOP_UP:pack");

        LedgerResult again = ledger().credit(ACC, 999, "pay:c1", "TOP_UP:pack");
        LedgerResult asDebit = ledger().debit(ACC, 50, "pay:c1", "spend");

        assertThat(again).isEqualTo(new LedgerResult(false, 100));
        assertThat(asDebit).isEqualTo(new LedgerResult(false, 100));
        assertThat(ledger().balance(ACC)).isEqualTo(100);
        assertThat(ledger().history(ACC, 0, 10)).hasSize(1);
    }

    @Test
    void debitInsufficientThrowsAndLeavesNoEntry() {
        ledger().credit(ACC, 30, "pay:c1", "TOP_UP:pack");

        assertThatThrownBy(() -> ledger().debit(ACC, 50, "spend:1", "spend"))
                .isInstanceOfSatisfying(InsufficientBalanceException.class, e -> {
                    assertThat(e.required()).isEqualTo(50);
                    assertThat(e.available()).isEqualTo(30);
                });

        assertThat(ledger().balance(ACC)).isEqualTo(30);
        assertThat(ledger().history(ACC, 0, 10)).extracting(LedgerEntry::refKey).containsExactly("pay:c1");
        // the refKey was not consumed, so a later debit with it still applies
        ledger().credit(ACC, 20, "pay:c2", "TOP_UP:pack");
        assertThat(ledger().debit(ACC, 50, "spend:1", "spend")).isEqualTo(new LedgerResult(true, 0));
    }

    @Test
    void debitAllowNegativeGoesBelowZero() {
        ledger().credit(ACC, 30, "pay:c1", "TOP_UP:pack");

        LedgerResult r = ledger().debitAllowNegative(ACC, 100, "refund:c1", "refund");

        assertThat(r).isEqualTo(new LedgerResult(true, -70));
        assertThat(ledger().balance(ACC)).isEqualTo(-70);
        assertThat(ledger().history(ACC, 0, 1)).first()
                .satisfies(e -> {
                    assertThat(e.delta()).isEqualTo(-100);
                    assertThat(e.account()).isEqualTo(ACC);
                    assertThat(e.reason()).isEqualTo("refund");
                    assertThat(e.createdAt()).isNotNull();
                });
    }

    @Test
    void historyNewestFirstPaged() {
        ledger().credit(ACC, 10, "r1", "a");
        ledger().credit(ACC, 20, "r2", "b");
        ledger().debit(ACC, 5, "r3", "c");
        ledger().credit(new BalanceAccountKey(5L, 1L), 1, "other", "x");

        assertThat(ledger().history(ACC, 0, 2)).extracting(LedgerEntry::refKey).containsExactly("r3", "r2");
        assertThat(ledger().history(ACC, 1, 2)).extracting(LedgerEntry::refKey).containsExactly("r1");
        assertThat(ledger().history(ACC, 2, 2)).isEmpty();
        assertThat(ledger().history(ACC, 0, 10)).extracting(LedgerEntry::delta).containsExactly(-5L, 20L, 10L);
    }

    @Test
    void scopesAreIsolated() {
        BalanceAccountKey global = new BalanceAccountKey(null, 1L);
        BalanceAccountKey perBot = new BalanceAccountKey(5L, 1L);

        ledger().credit(global, 10, "g1", "a");
        ledger().credit(perBot, 70, "b1", "a");

        assertThat(ledger().balance(global)).isEqualTo(10);
        assertThat(ledger().balance(perBot)).isEqualTo(70);
        assertThat(global.asString()).isEqualTo("g:1");
        assertThat(perBot.asString()).isEqualTo("5:1");
    }

    @Test
    void concurrentDebitsNeverOverdraw() throws Exception {
        ledger().credit(ACC, 100, "seed", "seed");
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String ref = "spend:" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        ledger().debit(ACC, 10, ref, "spend");
                        ok.incrementAndGet();
                    } catch (InsufficientBalanceException e) {
                        insufficient.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(ok.get()).isEqualTo(10);
        assertThat(insufficient.get()).isEqualTo(40);
        assertThat(ledger().balance(ACC)).isZero();
        assertThat(ledger().history(ACC, 0, 100)).hasSize(11);
    }

    @Test
    void rejectsNonPositiveAmount() {
        assertThatThrownBy(() -> ledger().credit(ACC, 0, "r", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger().debit(ACC, -1, "r", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger().debitAllowNegative(ACC, 0, "r", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger().credit(ACC, 1, " ", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger().credit(ACC, 1, "r".repeat(601), "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger().credit(ACC, 1, "r", "x".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ledger().balance(ACC)).isZero();
    }
}
