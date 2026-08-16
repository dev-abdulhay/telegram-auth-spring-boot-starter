package io.github.dev_abdulhay.telegramauth.repository;

import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Base repository for any {@link BaseAuthSession} subtype. Derived queries are
 * used (not JPQL) so the methods resolve against the concrete entity name; the
 * one JPQL statement here uses {@code #{#entityName}} for the same reason.
 */
@NoRepositoryBean
public interface BaseAuthSessionRepository<S extends BaseAuthSession>
        extends JpaRepository<S, Long> {

    Optional<S> findByTokenHash(String tokenHash);

    /** Row-locked lookup used by approve/reject so concurrent terminal transitions serialize. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<S> findWithLockByTokenHash(String tokenHash);

    List<S> findByStatusAndExpiresAtBefore(BaseAuthSession.Status status, OffsetDateTime time);

    /** Live pending sessions for an IP; overdue rows are excluded so a stale batch cannot lock the IP out. */
    long countByIpAddressAndStatusAndExpiresAtAfter(String ipAddress, BaseAuthSession.Status status, OffsetDateTime time);

    /**
     * Bulk-deletes old terminal sessions in one statement. A derived
     * {@code deleteBy...} would load every matching row into the persistence
     * context and delete them one by one, which on a large session table is both
     * slow and an OOM risk during the scheduled sweep.
     *
     * @return number of rows deleted
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from #{#entityName} s where s.status in :statuses and s.expiresAt < :time")
    int deleteByStatusInAndExpiresAtBefore(@Param("statuses") Collection<BaseAuthSession.Status> statuses,
                                           @Param("time") OffsetDateTime time);
}
