package com.example.demo;

import io.github.dev_abdulhay.telegramauth.payments.JpaStarSubscriptionStore;
import io.github.dev_abdulhay.telegramauth.payments.StarSubscription;
import io.github.dev_abdulhay.telegramauth.payments.StarSubscriptionStatus;
import io.github.dev_abdulhay.telegramauth.payments.StarSubscriptionStore;
import io.github.dev_abdulhay.telegramauth.payments.StarSubscriptionStoreContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

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

    @Autowired
    private PlatformTransactionManager txManager;

    private StarSubscriptionStore store;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        store = new JpaStarSubscriptionStore<>(repo, DemoStarSubscription::new, txManager);
    }

    @Override
    protected StarSubscriptionStore store() {
        return store;
    }

    @Test
    void firstChargeIdIsUnique() {
        OffsetDateTime exp = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(30);
        store.save(active("s1", 7L, exp));

        assertThatThrownBy(() -> store.save(new io.github.dev_abdulhay.telegramauth.payments.StarSubscription(
                "s2", 1L, 7L, "vip", "first-s1", "first-s1",
                io.github.dev_abdulhay.telegramauth.payments.StarSubscriptionStatus.ACTIVE, exp, exp)))
                .isInstanceOf(RuntimeException.class);
        assertThat(repo.count()).isEqualTo(1);
    }

    /**
     * The race "row committed by another transaction between our existence check and our insert",
     * made deterministic: the repository's existence check lies once. Only the primary key can
     * stop the duplicate, and the existing row must not be merged over.
     */
    @Test
    void rowCommittedAfterTheExistenceCheckIsNotOverwritten() {
        OffsetDateTime exp = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS).plusDays(30);
        store.insertIfAbsent(active("s1", 7L, exp));
        store.updateLiveStatus("s1", StarSubscriptionStatus.CANCELED);
        AtomicBoolean stale = new AtomicBoolean(true);
        @SuppressWarnings("unchecked")
        DemoStarSubscriptionRepository blind = (DemoStarSubscriptionRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{DemoStarSubscriptionRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("existsById") && stale.getAndSet(false)) {
                        return false;
                    }
                    try {
                        return method.invoke(repo, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        StarSubscriptionStore racing = new JpaStarSubscriptionStore<>(blind, DemoStarSubscription::new, txManager);

        boolean inserted = racing.insertIfAbsent(new StarSubscription("s1", 1L, 7L, "vip", "first-s1", "first-s1",
                StarSubscriptionStatus.ACTIVE, exp.plusDays(9), exp));

        assertThat(inserted).isFalse();
        StarSubscription s = store.findById("s1").orElseThrow();
        assertThat(s.status()).isEqualTo(StarSubscriptionStatus.CANCELED);
        assertThat(s.expiresAt().toInstant()).isEqualTo(exp.toInstant());
    }
}
