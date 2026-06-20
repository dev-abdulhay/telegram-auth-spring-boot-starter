package io.github.dev_abdulhay.telegramauth.repository;

import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Base repository for any {@link BaseAuthSession} subtype. Derived queries are
 * used (not JPQL) so the methods resolve against the concrete entity name.
 */
@NoRepositoryBean
public interface BaseAuthSessionRepository<S extends BaseAuthSession>
        extends JpaRepository<S, Long> {

    Optional<S> findByTokenHash(String tokenHash);

    List<S> findByStatusAndExpiresAtBefore(BaseAuthSession.Status status, OffsetDateTime time);
}
