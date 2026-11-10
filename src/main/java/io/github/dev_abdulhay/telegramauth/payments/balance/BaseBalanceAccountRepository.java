package io.github.dev_abdulhay.telegramauth.payments.balance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Base repository for any {@link BaseBalanceAccount} subtype. Balance changes are single
 * {@code UPDATE} statements evaluated by the database, so concurrent writers never lose an
 * update; {@link #debit} only matches while the balance covers the amount.
 */
@NoRepositoryBean
public interface BaseBalanceAccountRepository<A extends BaseBalanceAccount> extends JpaRepository<A, String> {

    @Query("select a.balance from #{#entityName} a where a.accountKey = :k")
    Optional<Long> findBalance(@Param("k") String accountKey);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update #{#entityName} a set a.balance = a.balance + :n, a.updatedAt = :at where a.accountKey = :k")
    int credit(@Param("k") String accountKey, @Param("n") long amount, @Param("at") OffsetDateTime at);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update #{#entityName} a set a.balance = a.balance - :n, a.updatedAt = :at "
            + "where a.accountKey = :k and a.balance >= :n")
    int debit(@Param("k") String accountKey, @Param("n") long amount, @Param("at") OffsetDateTime at);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update #{#entityName} a set a.balance = a.balance - :n, a.updatedAt = :at where a.accountKey = :k")
    int debitAllowNegative(@Param("k") String accountKey, @Param("n") long amount, @Param("at") OffsetDateTime at);
}
