package com.example.demo;

import io.github.dev_abdulhay.telegramauth.payments.JpaStarOrderStore;
import io.github.dev_abdulhay.telegramauth.payments.StarOrderStore;
import io.github.dev_abdulhay.telegramauth.payments.StarOrderStoreContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void chargeIdIsUnique() {
        store.save(created("o1"));
        store.save(created("o2"));
        store.markPaid("o1", "ch1", OffsetDateTime.now());

        assertThatThrownBy(() -> store.markPaid("o2", "ch1", OffsetDateTime.now()))
                .isInstanceOf(RuntimeException.class);
        assertThat(store.findByChargeId("ch1")).get().extracting(o -> o.id()).isEqualTo("o1");
    }
}
