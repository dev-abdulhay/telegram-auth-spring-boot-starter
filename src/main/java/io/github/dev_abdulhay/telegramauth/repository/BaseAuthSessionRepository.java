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

    /**
     * The same lookup <em>within one tenant bot</em>. A token minted by one tenant
     * must not resolve through another tenant's service: the row would be moved to
     * a terminal state and its event published on the wrong bot's
     * {@code AuthEventBus}, leaving the browser that actually started the login
     * waiting forever on a session that already reads APPROVED.
     */
    Optional<S> findByTokenHashAndBotUserId(String tokenHash, Long botUserId);

    /** Row-locked lookup used by approve/reject so concurrent terminal transitions serialize. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<S> findWithLockByTokenHash(String tokenHash);

    /** Tenant-scoped {@link #findWithLockByTokenHash}; see {@link #findByTokenHashAndBotUserId}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<S> findWithLockByTokenHashAndBotUserId(String tokenHash, Long botUserId);

    /** Sessions in any of the given statuses whose deadline has passed. */
    List<S> findByStatusInAndExpiresAtBefore(Collection<BaseAuthSession.Status> statuses, OffsetDateTime time);

    /**
     * Live sessions for an IP in any of the given statuses; overdue rows are excluded so a
     * stale batch cannot lock the IP out. Both {@code PENDING} and {@code AWAITING_CODE}
     * count — a half-finished login still occupies a slot, and counting only {@code PENDING}
     * would let an attacker park sessions at the code step to bypass the limit.
     */
    long countByIpAddressAndStatusInAndExpiresAtAfter(String ipAddress,
                                                      Collection<BaseAuthSession.Status> statuses,
                                                      OffsetDateTime time);

    /**
     * Live sessions for an IP <em>within one tenant bot</em>. A flood against one
     * tenant must not consume another tenant's quota, even though both share the
     * table.
     */
    long countByIpAddressAndBotUserIdAndStatusInAndExpiresAtAfter(String ipAddress,
                                                                  Long botUserId,
                                                                  Collection<BaseAuthSession.Status> statuses,
                                                                  OffsetDateTime time);

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
