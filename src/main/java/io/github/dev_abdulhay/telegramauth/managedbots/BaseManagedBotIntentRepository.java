package io.github.dev_abdulhay.telegramauth.managedbots;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Base repository for any {@link BaseManagedBotIntent} subtype. Host repositories
 * extend this with their concrete entity: {@code interface TenantIntentRepository
 * extends BaseManagedBotIntentRepository<TenantIntent> {}}.
 */
@NoRepositoryBean
public interface BaseManagedBotIntentRepository<I extends BaseManagedBotIntent>
        extends JpaRepository<I, String> {

    Optional<I> findByBotUserId(Long botUserId);

    List<I> findByOwnerUserIdAndStatus(Long ownerUserId, ManagedBotIntentStatus status);

    List<I> findByStatusNotInAndExpiresAtBefore(Collection<ManagedBotIntentStatus> statuses,
                                                OffsetDateTime cutoff);
}
