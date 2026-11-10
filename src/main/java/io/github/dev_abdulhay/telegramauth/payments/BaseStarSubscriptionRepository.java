package io.github.dev_abdulhay.telegramauth.payments;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Base repository for any {@link BaseStarSubscription} subtype. The {@code @Modifying} queries
 * are single-statement conditional updates so concurrent renewal, expiry and cancel paths
 * cannot overwrite each other.
 */
@NoRepositoryBean
public interface BaseStarSubscriptionRepository<S extends BaseStarSubscription> extends JpaRepository<S, String> {

    List<S> findByUserId(long userId);

    List<S> findByStatusInAndExpiresAtBefore(Collection<StarSubscriptionStatus> statuses, OffsetDateTime cutoff);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update #{#entityName} s set s.lastChargeId = :chargeId, s.expiresAt = :expiry, "
            + "s.status = case when s.status = :expired then :active else s.status end "
            + "where s.id = :id and s.expiresAt < :expiry")
    int extend(@Param("id") String id, @Param("chargeId") String chargeId, @Param("expiry") OffsetDateTime expiry,
               @Param("expired") StarSubscriptionStatus expired, @Param("active") StarSubscriptionStatus active);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update #{#entityName} s set s.status = :to "
            + "where s.id = :id and s.status in :live and s.expiresAt < :cutoff")
    int expireIfBefore(@Param("id") String id, @Param("cutoff") OffsetDateTime cutoff,
                       @Param("live") Collection<StarSubscriptionStatus> live, @Param("to") StarSubscriptionStatus to);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update #{#entityName} s set s.status = :to where s.id = :id and s.status in :live")
    int updateLiveStatus(@Param("id") String id, @Param("live") Collection<StarSubscriptionStatus> live,
                         @Param("to") StarSubscriptionStatus to);
}
