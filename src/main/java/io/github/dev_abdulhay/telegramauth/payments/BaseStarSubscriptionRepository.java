package io.github.dev_abdulhay.telegramauth.payments;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/** Base repository for any {@link BaseStarSubscription} subtype. */
@NoRepositoryBean
public interface BaseStarSubscriptionRepository<S extends BaseStarSubscription> extends JpaRepository<S, String> {

    List<S> findByUserId(long userId);

    List<S> findByStatusInAndExpiresAtBefore(Collection<StarSubscriptionStatus> statuses, OffsetDateTime cutoff);
}
