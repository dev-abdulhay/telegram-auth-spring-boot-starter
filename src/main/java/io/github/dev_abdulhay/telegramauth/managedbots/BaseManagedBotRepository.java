package io.github.dev_abdulhay.telegramauth.managedbots;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Base repository for any {@link BaseManagedBot} subtype. Host repositories
 * extend this with their concrete entity: {@code interface TenantBotRepository
 * extends BaseManagedBotRepository<TenantBot> {}}.
 */
@NoRepositoryBean
public interface BaseManagedBotRepository<M extends BaseManagedBot> extends JpaRepository<M, Long> {

    Optional<M> findByBotUserId(Long botUserId);

    List<M> findByOwnerUserId(Long ownerUserId);

    /**
     * Spring Data implements a derived delete like this as find-then-{@code remove()},
     * which requires an active transaction. {@code @Transactional} here makes the
     * repository self-sufficient: {@link JpaManagedBotTokenStore} works correctly even
     * when constructed with plain {@code new} (no Spring proxy around it), because
     * Spring Data's own repository proxy — always container-managed — honours this
     * annotation regardless of how its caller is wired.
     */
    @Transactional
    void deleteByBotUserId(Long botUserId);
}
