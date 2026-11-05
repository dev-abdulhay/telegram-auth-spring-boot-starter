package com.example.demo;

import io.github.dev_abdulhay.telegramauth.managedbots.JpaManagedBotIntentStore;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotIntent;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotIntentStore;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotIntentStoreContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Same wiring as {@link JpaManagedBotStoreTest} and for the same reason: a bare
 * {@code @DataJpaTest} cannot see this library's own auto-configuration while the
 * {@code com.example.demo} package is component-scanned.
 */
@SpringBootTest(classes = DemoApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "telegram.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:managedbotintents;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
class JpaManagedBotIntentStoreTest extends ManagedBotIntentStoreContract {

    @Autowired
    private DemoManagedBotIntentRepository repo;

    private ManagedBotIntentStore store;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        store = new JpaManagedBotIntentStore<>(repo, DemoManagedBotIntent::new);
    }

    @Override
    protected ManagedBotIntentStore store() {
        return store;
    }

    @Test
    void savingTheSameIntentAgainUpdatesTheExistingRow() {
        OffsetDateTime now = OffsetDateTime.now();
        store.save(open("i1", now));
        store.save(open("i1", now).claimedBy(7L, now));

        assertThat(repo.count()).isEqualTo(1);
        assertThat(store.findById("i1")).get()
                .extracting(ManagedBotIntent::ownerUserId).isEqualTo(7L);
    }

    @Test
    void twoIntentsCannotClaimTheSameBot() {
        OffsetDateTime now = OffsetDateTime.now();
        store.save(open("i1", now).claimedBy(7L, now).completedWith(555L, now));

        assertThatThrownBy(() -> store.save(open("i2", now).claimedBy(7L, now).completedWith(555L, now)))
                .isInstanceOf(RuntimeException.class);
    }
}
