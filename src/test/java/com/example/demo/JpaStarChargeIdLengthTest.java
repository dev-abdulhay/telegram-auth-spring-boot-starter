package com.example.demo;

import io.github.dev_abdulhay.telegramauth.payments.JpaStarOrderStore;
import io.github.dev_abdulhay.telegramauth.payments.JpaStarSubscriptionStore;
import io.github.dev_abdulhay.telegramauth.payments.StarOrder;
import io.github.dev_abdulhay.telegramauth.payments.StarOrderKind;
import io.github.dev_abdulhay.telegramauth.payments.StarOrderStatus;
import io.github.dev_abdulhay.telegramauth.payments.StarOrderStore;
import io.github.dev_abdulhay.telegramauth.payments.StarSubscription;
import io.github.dev_abdulhay.telegramauth.payments.StarSubscriptionStatus;
import io.github.dev_abdulhay.telegramauth.payments.StarSubscriptionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real Telegram charge ids are ~200 chars. 0.6.0 mapped them to varchar(128), and the failed
 * UPDATE was swallowed as a plain {@code false}: the payment was taken but never recorded.
 * H2 runs in PostgreSQL mode, so the failure is the same value-too-long error a Postgres host sees.
 */
@SpringBootTest(classes = DemoApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "telegram.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:starchargeids;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@ExtendWith(OutputCaptureExtension.class)
class JpaStarChargeIdLengthTest {

    /** As long as the one seen in production. */
    static final String CHARGE_ID = "stxCh_" + "Ab9-".repeat(50).substring(0, 194);

    @Autowired
    private DemoStarOrderRepository orderRepo;

    @Autowired
    private DemoStarSubscriptionRepository subRepo;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    private StarOrderStore orders;
    private StarSubscriptionStore subs;

    @BeforeEach
    void setUp() {
        orderRepo.deleteAll();
        subRepo.deleteAll();
        orders = new JpaStarOrderStore<>(orderRepo, DemoStarOrder::new, txManager);
        subs = new JpaStarSubscriptionStore<>(subRepo, DemoStarSubscription::new, txManager);
    }

    @Test
    void markPaidStoresARealisticChargeId() {
        assertThat(CHARGE_ID).hasSize(200);
        orders.save(created("o1"));

        assertThat(orders.markPaid("o1", CHARGE_ID, OffsetDateTime.now())).isTrue();

        assertThat(orders.findById("o1").orElseThrow().status()).isEqualTo(StarOrderStatus.PAID);
        assertThat(orders.findByChargeId(CHARGE_ID)).get().extracting(StarOrder::id).isEqualTo("o1");
    }

    @Test
    void renewalAndSubscriptionStoreARealisticChargeId() {
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        StarOrder renewal = new StarOrder("r1", 1L, 7L, "vip", StarOrderKind.RENEWAL, 100, 0L,
                StarOrderStatus.PAID, CHARGE_ID, "sub1", now, now.plusMinutes(30), now, null, null);
        assertThat(orders.insertPaidIfChargeAbsent(renewal)).isTrue();
        assertThat(orders.insertPaidIfChargeAbsent(renewal)).isFalse();

        String next = CHARGE_ID.substring(0, 199) + "Z";
        assertThat(subs.insertIfAbsent(new StarSubscription("s1", 1L, 7L, "vip", CHARGE_ID, CHARGE_ID,
                StarSubscriptionStatus.ACTIVE, now.plusDays(30), now))).isTrue();
        assertThat(subs.extend("s1", next, now.plusDays(60))).isTrue();

        StarSubscription s = subs.findById("s1").orElseThrow();
        assertThat(s.firstChargeId()).isEqualTo(CHARGE_ID);
        assertThat(s.lastChargeId()).isEqualTo(next);
    }

    @Test
    void aNonDuplicateIntegrityFailureIsLoggedAndRethrownNotSwallowed(CapturedOutput output) {
        orders.save(created("o1"));
        // a host that followed the 0.6.0 README
        jdbc.execute("ALTER TABLE demo_star_order ALTER COLUMN charge_id SET DATA TYPE VARCHAR(128)");
        try {
            assertThatThrownBy(() -> orders.markPaid("o1", CHARGE_ID, OffsetDateTime.now()))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> orders.insertPaidIfChargeAbsent(new StarOrder("r1", 1L, 7L, "vip",
                    StarOrderKind.RENEWAL, 100, 0L, StarOrderStatus.PAID, CHARGE_ID, "sub1", OffsetDateTime.now(),
                    OffsetDateTime.now(), OffsetDateTime.now(), null, null)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("ALTER TABLE demo_star_order ALTER COLUMN charge_id SET DATA TYPE VARCHAR(512)");
        }

        assertThat(orders.findById("o1").orElseThrow().status()).isEqualTo(StarOrderStatus.CREATED);
        assertThat(output.getOut()).contains("ERROR")
                .contains("Recording Stars charge " + CHARGE_ID + " (length 200) on order o1 failed")
                .contains("Recording Stars charge " + CHARGE_ID + " (length 200) on order r1 failed")
                .contains("charge_id must be at least varchar(512)");
    }

    @Test
    void aDuplicateChargeIdStillReturnsFalseQuietly(CapturedOutput output) {
        orders.save(created("o1"));
        orders.save(created("o2"));
        assertThat(orders.markPaid("o1", CHARGE_ID, OffsetDateTime.now())).isTrue();

        assertThat(orders.markPaid("o2", CHARGE_ID, OffsetDateTime.now())).isFalse();

        assertThat(orders.findById("o2").orElseThrow().status()).isEqualTo(StarOrderStatus.CREATED);
        assertThat(output.getOut()).doesNotContain("Recording Stars charge");
    }

    private static StarOrder created(String id) {
        OffsetDateTime now = OffsetDateTime.now();
        return new StarOrder(id, 1L, 7L, "pack", StarOrderKind.PURCHASE, 50, 100L,
                StarOrderStatus.CREATED, null, null, now, now.plusMinutes(30), null, null, null);
    }
}
