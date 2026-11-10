package com.example.demo;

import io.github.dev_abdulhay.telegramauth.payments.JpaStarOrderStore;
import io.github.dev_abdulhay.telegramauth.payments.StarOrderStore;
import io.github.dev_abdulhay.telegramauth.payments.StarOrderStoreContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Same wiring as {@link JpaManagedBotIntentStoreTest}. */
@SpringBootTest(classes = DemoApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "telegram.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:starorders;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
class JpaStarOrderStoreTest extends StarOrderStoreContract {

    @Autowired
    private DemoStarOrderRepository repo;

    @Autowired
    private PlatformTransactionManager txManager;

    private StarOrderStore store;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        store = new JpaStarOrderStore<>(repo, DemoStarOrder::new);
    }

    @Override
    protected StarOrderStore store() {
        return store;
    }

    @Test
    void duplicateChargeInsideOuterTransactionYieldsFalseAndCommits() {
        store.save(created("o1"));
        store.markPaid("o1", "ch1", OffsetDateTime.now());

        TransactionTemplate tx = new TransactionTemplate(txManager);
        Boolean second = tx.execute(status -> {
            boolean first = store.insertPaidIfChargeAbsent(paidRenewal("r1", "chR"));
            boolean dup = store.insertPaidIfChargeAbsent(paidRenewal("r2", "chR"));
            return first && !dup;
        });

        assertThat(second).isTrue();
        assertThat(store.findByChargeId("chR")).get().extracting(o -> o.id()).isEqualTo("r1");
    }
}
