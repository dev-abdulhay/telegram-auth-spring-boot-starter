package com.example.demo;

import io.github.dev_abdulhay.telegramauth.payments.balance.BalanceAccountKey;
import io.github.dev_abdulhay.telegramauth.payments.balance.BalanceLedger;
import io.github.dev_abdulhay.telegramauth.payments.balance.BalanceLedgerContract;
import io.github.dev_abdulhay.telegramauth.payments.balance.InsufficientBalanceException;
import io.github.dev_abdulhay.telegramauth.payments.balance.JpaBalanceLedger;
import io.github.dev_abdulhay.telegramauth.payments.balance.LedgerResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

/** Same wiring as {@link JpaStarOrderStoreTest}; a longer H2 lock timeout for the contention tests. */
@SpringBootTest(classes = DemoApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "telegram.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:balanceledger;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
class JpaBalanceLedgerTest extends BalanceLedgerContract {

    @Autowired
    private DemoBalanceAccountRepository accounts;

    @Autowired
    private DemoLedgerEntryRepository entries;

    @Autowired
    private PlatformTransactionManager txManager;

    private BalanceLedger ledger;

    @BeforeEach
    void setUp() {
        entries.deleteAll();
        accounts.deleteAll();
        ledger = new JpaBalanceLedger<>(accounts, entries, DemoBalanceAccount::new, DemoLedgerEntry::new, txManager);
    }

    @Override
    protected BalanceLedger ledger() {
        return ledger;
    }

    @Test
    void storesAccountColumns() {
        ledger.credit(new BalanceAccountKey(5L, 9L), 3, "r", "x");

        DemoBalanceAccount a = accounts.findById("5:9").orElseThrow();
        assertThat(a.getBotId()).isEqualTo(5L);
        assertThat(a.getUserId()).isEqualTo(9L);
        assertThat(a.getBalance()).isEqualTo(3);
        assertThat(a.getUpdatedAt()).isNotNull();
    }

    @Test
    void outerTransactionRollbackDoesNotUndoLedgerAndFailureDoesNotPoisonIt() {
        ledger.credit(ACC, 5, "seed", "seed");

        Boolean ok = new TransactionTemplate(txManager).execute(status -> {
            LedgerResult dup = ledger.credit(ACC, 5, "seed", "seed");
            assertThatThrownBy(() -> ledger.debit(ACC, 50, "big", "x"))
                    .isInstanceOf(InsufficientBalanceException.class);
            return !dup.applied();
        });

        assertThat(ok).isTrue();
        assertThat(ledger.balance(ACC)).isEqualTo(5);
    }

    @Test
    void concurrentFirstCreditsOfNewAccountAndSameRefKeyApplyOnce() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger applied = new AtomicInteger();
        BalanceAccountKey fresh = new BalanceAccountKey(7L, 42L);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String ref = i % 2 == 0 ? "pay:same" : "pay:" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    if (ledger.credit(fresh, 10, ref, "TOP_UP:pack").applied()) {
                        applied.incrementAndGet();
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

        // 10 distinct odd refKeys + the shared one exactly once
        assertThat(applied.get()).isEqualTo(11);
        assertThat(ledger.balance(fresh)).isEqualTo(110);
        assertThat(entries.count()).isEqualTo(11);
    }
}
