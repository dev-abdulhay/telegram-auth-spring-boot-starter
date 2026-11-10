package com.example.demo;

import io.github.dev_abdulhay.telegramauth.payments.JpaStarSubscriptionStore;
import io.github.dev_abdulhay.telegramauth.payments.StarSubscriptionStore;
import io.github.dev_abdulhay.telegramauth.payments.StarSubscriptionStoreContract;
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
        "spring.datasource.url=jdbc:h2:mem:starsubscriptions;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
class JpaStarSubscriptionStoreTest extends StarSubscriptionStoreContract {

    @Autowired
    private DemoStarSubscriptionRepository repo;

    private StarSubscriptionStore store;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        store = new JpaStarSubscriptionStore<>(repo, DemoStarSubscription::new);
    }

    @Override
    protected StarSubscriptionStore store() {
        return store;
    }

    @Test
    void firstChargeIdIsUnique() {
        OffsetDateTime exp = OffsetDateTime.now().plusDays(30);
        store.save(active("s1", 7L, exp));

        assertThatThrownBy(() -> store.save(new io.github.dev_abdulhay.telegramauth.payments.StarSubscription(
                "s2", 1L, 7L, "vip", "first-s1", "first-s1",
                io.github.dev_abdulhay.telegramauth.payments.StarSubscriptionStatus.ACTIVE, exp, exp)))
                .isInstanceOf(RuntimeException.class);
        assertThat(repo.count()).isEqualTo(1);
    }
}
