package com.example.demo;

import io.github.dev_abdulhay.telegramauth.managedbots.JpaManagedBotTokenStore;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBot;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotStoreContract;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A bare {@code @DataJpaTest} does not work in this repo: its restricted
 * auto-configuration import list excludes this library's own
 * {@code TelegramAuthAutoConfiguration} (the source of {@code TokenGenerator}),
 * yet {@link DemoTgConfig} and {@link DemoAuthController} — sitting in the same
 * {@code com.example.demo} package — still get component-scanned into the slice
 * and fail to wire. {@link JpaLayerTest} already works around this the same way:
 * a full {@code @SpringBootTest} with the auto-configuration explicitly enabled
 * and Liquibase/datasource pinned to an in-memory H2 instance.
 *
 * <p>{@code store} is wired as a real Spring bean rather than {@code new}'d
 * directly, so {@link JpaManagedBotTokenStore}'s own {@code @Transactional}
 * annotations are actually proxied — required for {@code deleteByBotUserId},
 * whose derived delete query needs an active transaction to call
 * {@code EntityManager.remove}. (A class-level {@code @Transactional} on this
 * test does not help here: Spring's transactional test support does not pick
 * it up for methods inherited from {@link ManagedBotStoreContract}, only for
 * methods declared directly on this class.) Each test instead cleans the table
 * itself in {@link #cleanUp()} for isolation.
 */
@SpringBootTest(classes = DemoApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "telegram.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:managedbots;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.liquibase.enabled=false"
})
class JpaManagedBotStoreTest extends ManagedBotStoreContract {

    @TestConfiguration
    static class StoreConfig {
        @Bean
        JpaManagedBotTokenStore<DemoManagedBot> managedBotTokenStore(DemoManagedBotRepository repo) {
            return new JpaManagedBotTokenStore<>(repo, DemoManagedBot::new);
        }
    }

    @Autowired
    private DemoManagedBotRepository repo;

    @Autowired
    private ManagedBotTokenStore store;

    @BeforeEach
    void cleanUp() {
        repo.deleteAll();
    }

    @Override
    protected ManagedBotTokenStore store() {
        return store;
    }

    @Test
    void savesAndReadsBackThroughTheDatabase() {
        store.save(bot(101L, 7L));

        assertThat(store.findByBotUserId(101L)).get()
                .extracting(ManagedBot::encryptedToken).isEqualTo("enc-101");
    }

    @Test
    void savingTheSameBotAgainUpdatesTheExistingRow() {
        store.save(bot(101L, 7L));
        OffsetDateTime now = OffsetDateTime.now();
        store.save(new ManagedBot(101L, "renamed_bot", "Renamed", 7L, "enc-new", now, now));

        assertThat(repo.count()).isEqualTo(1);
        assertThat(store.findByBotUserId(101L)).get()
                .extracting(ManagedBot::encryptedToken).isEqualTo("enc-new");
    }

    @Test
    void findsByOwnerAndDeletesByBotUserId() {
        store.save(bot(101L, 7L));
        store.save(bot(102L, 7L));
        store.save(bot(103L, 8L));

        assertThat(store.findByOwnerUserId(7L)).hasSize(2);

        store.deleteByBotUserId(101L);
        assertThat(store.findByBotUserId(101L)).isEmpty();
        assertThat(store.findAll()).hasSize(2);
    }

    /**
     * The shared contract only checks that {@code encryptedToken} survives a save
     * (see {@link ManagedBotStoreContract#savesAndFindsByBotUserId()}); it never
     * checks the other six fields. That's harmless for the in-memory store but
     * risky here: a mis-mapped {@code @Column} would silently drop a field and
     * every other test would still pass. Compares timestamps with
     * {@code isEqualToIgnoringNanos} since H2's {@code TIMESTAMP WITH TIME ZONE}
     * column round-trips {@link OffsetDateTime} at microsecond precision, not the
     * full nanosecond precision {@link OffsetDateTime#now()} produces in the JVM.
     */
    @Test
    void everyFieldSurvivesTheRoundTrip() {
        OffsetDateTime createdAt = OffsetDateTime.now().minusDays(1);
        OffsetDateTime updatedAt = OffsetDateTime.now();
        ManagedBot original = new ManagedBot(101L, "tenant_101_bot", "Tenant 101", 7L,
                "enc-101", createdAt, updatedAt);

        store.save(original);

        ManagedBot found = store.findByBotUserId(101L).orElseThrow();
        assertThat(found.botUserId()).isEqualTo(original.botUserId());
        assertThat(found.username()).isEqualTo(original.username());
        assertThat(found.firstName()).isEqualTo(original.firstName());
        assertThat(found.ownerUserId()).isEqualTo(original.ownerUserId());
        assertThat(found.encryptedToken()).isEqualTo(original.encryptedToken());
        assertThat(found.createdAt()).isEqualToIgnoringNanos(original.createdAt());
        assertThat(found.updatedAt()).isEqualToIgnoringNanos(original.updatedAt());
    }
}
