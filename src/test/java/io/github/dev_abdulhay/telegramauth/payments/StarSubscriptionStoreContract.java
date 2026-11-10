package io.github.dev_abdulhay.telegramauth.payments;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract every {@link StarSubscriptionStore} must satisfy. Subclass per implementation. */
public abstract class StarSubscriptionStoreContract {

    protected abstract StarSubscriptionStore store();

    protected static StarSubscription active(String id, long userId, OffsetDateTime expiresAt) {
        return new StarSubscription(id, 1L, userId, "vip", "first-" + id, "first-" + id,
                StarSubscriptionStatus.ACTIVE, expiresAt, OffsetDateTime.now());
    }

    @Test
    void savesFindsAndOverwrites() {
        OffsetDateTime exp = OffsetDateTime.now().plusDays(30);
        store().save(active("s1", 7L, exp));
        assertThat(store().findById("s1")).isPresent();
        assertThat(store().findById("nope")).isEmpty();

        store().save(active("s1", 7L, exp).renewed("ch2", exp.plusDays(30)));
        StarSubscription s = store().findById("s1").orElseThrow();
        assertThat(s.lastChargeId()).isEqualTo("ch2");
        assertThat(s.firstChargeId()).isEqualTo("first-s1");
        assertThat(s.expiresAt().toInstant()).isEqualTo(exp.plusDays(30).toInstant());
    }

    @Test
    void findByUser() {
        OffsetDateTime exp = OffsetDateTime.now().plusDays(30);
        store().save(active("s1", 7L, exp));
        store().save(active("s2", 7L, exp));
        store().save(active("s3", 8L, exp));

        assertThat(store().findByUser(7L)).extracting(StarSubscription::id)
                .containsExactlyInAnyOrder("s1", "s2");
        assertThat(store().findByUser(99L)).isEmpty();
    }

    @Test
    void findLiveExpiringBeforeIncludesCanceledExcludesExpired() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime past = now.minusHours(1);
        store().save(active("act", 7L, past));
        store().save(active("can", 7L, past).withStatus(StarSubscriptionStatus.CANCELED));
        store().save(active("exp", 7L, past).withStatus(StarSubscriptionStatus.EXPIRED));
        store().save(active("future", 7L, now.plusDays(1)));

        assertThat(store().findLiveExpiringBefore(now)).extracting(StarSubscription::id)
                .containsExactlyInAnyOrder("act", "can");
    }

    @Test
    void extendOnlyMovesExpiryForward() {
        OffsetDateTime now = OffsetDateTime.now();
        store().save(active("s1", 7L, now).withStatus(StarSubscriptionStatus.EXPIRED));

        assertThat(store().extend("s1", "ch2", now.plusDays(30))).isTrue();
        StarSubscription s = store().findById("s1").orElseThrow();
        assertThat(s.status()).isEqualTo(StarSubscriptionStatus.ACTIVE);
        assertThat(s.lastChargeId()).isEqualTo("ch2");
        assertThat(s.firstChargeId()).isEqualTo("first-s1");

        assertThat(store().extend("s1", "ch-old", now.plusDays(10))).isFalse();
        assertThat(store().findById("s1").orElseThrow().lastChargeId()).isEqualTo("ch2");
        assertThat(store().extend("missing", "ch", now)).isFalse();
    }

    @Test
    void expireIfBeforeOnlyExpiresLiveAndDue() {
        OffsetDateTime now = OffsetDateTime.now();
        store().save(active("due", 7L, now.minusHours(2)).withStatus(StarSubscriptionStatus.CANCELED));
        store().save(active("future", 7L, now.plusDays(1)));

        assertThat(store().expireIfBefore("due", now)).isTrue();
        assertThat(store().expireIfBefore("due", now)).isFalse();
        assertThat(store().expireIfBefore("future", now)).isFalse();
        assertThat(store().findById("due").orElseThrow().status()).isEqualTo(StarSubscriptionStatus.EXPIRED);
        assertThat(store().findById("future").orElseThrow().status()).isEqualTo(StarSubscriptionStatus.ACTIVE);
    }

    @Test
    void updateLiveStatusLeavesExpiredAndExpiryAlone() {
        OffsetDateTime now = OffsetDateTime.now();
        store().save(active("s1", 7L, now.plusDays(1)));
        store().save(active("s2", 7L, now.minusDays(1)).withStatus(StarSubscriptionStatus.EXPIRED));

        assertThat(store().updateLiveStatus("s1", StarSubscriptionStatus.CANCELED)).isTrue();
        assertThat(store().updateLiveStatus("s2", StarSubscriptionStatus.ACTIVE)).isFalse();
        assertThat(store().findById("s1").orElseThrow().status()).isEqualTo(StarSubscriptionStatus.CANCELED);
        assertThat(store().findById("s2").orElseThrow().status()).isEqualTo(StarSubscriptionStatus.EXPIRED);
    }
}
