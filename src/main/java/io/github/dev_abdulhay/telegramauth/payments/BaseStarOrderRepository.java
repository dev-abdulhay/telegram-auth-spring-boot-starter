package io.github.dev_abdulhay.telegramauth.payments;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Base repository for any {@link BaseStarOrder} subtype. The {@code @Modifying} queries
 * are single-statement compare-and-set transitions: the returned count is 1 only for the
 * caller whose update matched. They carry their own {@code @Transactional} so they also work
 * when the store is built with {@code new} instead of as a Spring bean.
 */
@NoRepositoryBean
public interface BaseStarOrderRepository<O extends BaseStarOrder> extends JpaRepository<O, String> {

    Optional<O> findByChargeId(String chargeId);

    boolean existsByChargeId(String chargeId);

    List<O> findByStatusAndFulfilledAtIsNullOrderByPaidAtAsc(StarOrderStatus status, Pageable page);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update #{#entityName} o set o.status = :to, o.chargeId = :chargeId, o.paidAt = :at "
            + "where o.id = :id and o.status in :from")
    int markPaid(@Param("id") String id, @Param("chargeId") String chargeId, @Param("at") OffsetDateTime at,
                 @Param("from") Collection<StarOrderStatus> from, @Param("to") StarOrderStatus to);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update #{#entityName} o set o.fulfilledAt = :at "
            + "where o.id = :id and o.status in :from and o.fulfilledAt is null")
    int markFulfilled(@Param("id") String id, @Param("at") OffsetDateTime at,
                      @Param("from") Collection<StarOrderStatus> from);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update #{#entityName} o set o.status = :to, o.refundedAt = :at "
            + "where o.id = :id and o.status in :from")
    int markRefunded(@Param("id") String id, @Param("at") OffsetDateTime at,
                     @Param("from") Collection<StarOrderStatus> from, @Param("to") StarOrderStatus to);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update #{#entityName} o set o.status = :to "
            + "where o.status in :from and o.expiresAt < :now")
    int expireBefore(@Param("now") OffsetDateTime now, @Param("from") Collection<StarOrderStatus> from,
                     @Param("to") StarOrderStatus to);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("delete from #{#entityName} o where o.status = :status and o.expiresAt < :cutoff")
    int deleteByStatusAndExpiresAtBefore(@Param("status") StarOrderStatus status,
                                         @Param("cutoff") OffsetDateTime cutoff);
}
