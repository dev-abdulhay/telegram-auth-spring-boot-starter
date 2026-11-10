package io.github.dev_abdulhay.telegramauth.payments;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract every {@link StarOrderStore} must satisfy. Subclass per implementation. */
public abstract class StarOrderStoreContract {

    protected abstract StarOrderStore store();

    protected static StarOrder created(String id, OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        return new StarOrder(id, 1L, 7L, "pack", StarOrderKind.PURCHASE, 50, 100L,
                StarOrderStatus.CREATED, null, null, createdAt, expiresAt, null, null, null);
    }

    protected static StarOrder created(String id) {
        OffsetDateTime now = OffsetDateTime.now();
        return created(id, now, now.plusMinutes(30));
    }

    protected static StarOrder paidRenewal(String id, String chargeId) {
        OffsetDateTime now = OffsetDateTime.now();
        return new StarOrder(id, 1L, 7L, "vip", StarOrderKind.RENEWAL, 100, 0L,
                StarOrderStatus.PAID, chargeId, "sub1", now, now.plusMinutes(30), now, null, null);
    }

    @Test
    void savesAndFindsById() {
        store().save(created("o1"));
        assertThat(store().findById("o1")).get()
                .extracting(StarOrder::productCode).isEqualTo("pack");
        assertThat(store().findById("nope")).isEmpty();
    }

    @Test
    void savingTheSameIdOverwrites() {
        store().save(created("o1"));
        store().save(created("o1").withSubscriptionId("s9"));
        assertThat(store().findById("o1")).get()
                .extracting(StarOrder::subscriptionId).isEqualTo("s9");
    }

    @Test
    void markPaidTransitionsOnce() {
        store().save(created("o1"));
        OffsetDateTime at = OffsetDateTime.now();

        assertThat(store().markPaid("o1", "ch1", at)).isTrue();
        assertThat(store().markPaid("o1", "ch2", at)).isFalse();

        StarOrder o = store().findById("o1").orElseThrow();
        assertThat(o.status()).isEqualTo(StarOrderStatus.PAID);
        assertThat(o.chargeId()).isEqualTo("ch1");
        assertThat(o.paidAt()).isNotNull();
        assertThat(store().findByChargeId("ch1")).isPresent();
    }

    @Test
    void markPaidAcceptsExpired() {
        OffsetDateTime now = OffsetDateTime.now();
        store().save(created("o1", now.minusHours(2), now.minusHours(1)));
        store().expireCreatedBefore(now);

        assertThat(store().findById("o1").orElseThrow().status()).isEqualTo(StarOrderStatus.EXPIRED);
        assertThat(store().markPaid("o1", "ch1", now)).isTrue();
    }

    @Test
    void markPaidRejectsRefunded() {
        store().save(created("o1"));
        store().markPaid("o1", "ch1", OffsetDateTime.now());
        store().markRefunded("o1", OffsetDateTime.now());

        assertThat(store().markPaid("o1", "ch2", OffsetDateTime.now())).isFalse();
        assertThat(store().findById("o1").orElseThrow().status()).isEqualTo(StarOrderStatus.REFUNDED);
    }

    @Test
    void insertPaidIfChargeAbsentIsIdempotent() {
        assertThat(store().insertPaidIfChargeAbsent(paidRenewal("r1", "chR"))).isTrue();
        assertThat(store().insertPaidIfChargeAbsent(paidRenewal("r2", "chR"))).isFalse();

        assertThat(store().findById("r2")).isEmpty();
        assertThat(store().findByChargeId("chR")).get().extracting(StarOrder::id).isEqualTo("r1");
    }

    @Test
    void markFulfilledOnce() {
        store().save(created("o1"));
        assertThat(store().markFulfilled("o1", OffsetDateTime.now())).isFalse();   // not paid yet

        store().markPaid("o1", "ch1", OffsetDateTime.now());
        assertThat(store().markFulfilled("o1", OffsetDateTime.now())).isTrue();
        assertThat(store().markFulfilled("o1", OffsetDateTime.now())).isFalse();
        assertThat(store().findById("o1").orElseThrow().fulfilledAt()).isNotNull();
    }

    @Test
    void markRefundedOnlyFromPaid() {
        store().save(created("o1"));
        assertThat(store().markRefunded("o1", OffsetDateTime.now())).isFalse();

        store().markPaid("o1", "ch1", OffsetDateTime.now());
        assertThat(store().markRefunded("o1", OffsetDateTime.now())).isTrue();
        assertThat(store().markRefunded("o1", OffsetDateTime.now())).isFalse();
        assertThat(store().findById("o1").orElseThrow().refundedAt()).isNotNull();
    }

    @Test
    void findPaidUnfulfilledSkipsFulfilled() {
        for (String id : new String[]{"o1", "o2", "o3"}) {
            store().save(created(id));
            store().markPaid(id, "ch-" + id, OffsetDateTime.now());
        }
        store().save(created("o4"));   // unpaid
        store().markFulfilled("o2", OffsetDateTime.now());

        assertThat(store().findPaidUnfulfilled(10)).extracting(StarOrder::id)
                .containsExactlyInAnyOrder("o1", "o3");
        assertThat(store().findPaidUnfulfilled(1)).hasSize(1);
    }

    @Test
    void expireAndPurge() {
        OffsetDateTime now = OffsetDateTime.now();
        store().save(created("old", now.minusHours(3), now.minusHours(2)));
        store().save(created("fresh", now, now.plusMinutes(30)));

        assertThat(store().expireCreatedBefore(now)).isEqualTo(1);
        assertThat(store().findById("old").orElseThrow().status()).isEqualTo(StarOrderStatus.EXPIRED);
        assertThat(store().findById("fresh").orElseThrow().status()).isEqualTo(StarOrderStatus.CREATED);

        assertThat(store().deleteExpiredBefore(now.minusHours(1))).isEqualTo(1);
        assertThat(store().findById("old")).isEmpty();
        assertThat(store().findById("fresh")).isPresent();
    }

    @Test
    void parsePayloadRejectsForeign() {
        String id = StarOrderIds.newId();
        assertThat(id).matches("[0-9a-f]{32}");
        assertThat(StarOrderIds.parsePayload("so:" + id)).contains(id);
        assertThat(StarOrderIds.parsePayload(created(id).payload())).contains(id);

        assertThat(StarOrderIds.parsePayload("x:abc")).isEmpty();
        assertThat(StarOrderIds.parsePayload("so:" + "a".repeat(31))).isEmpty();
        assertThat(StarOrderIds.parsePayload("so:" + "A".repeat(32))).isEmpty();
        assertThat(StarOrderIds.parsePayload("so:../")).isEmpty();
        assertThat(StarOrderIds.parsePayload(null)).isEmpty();
    }
}
