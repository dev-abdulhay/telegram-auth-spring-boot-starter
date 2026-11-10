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
}
