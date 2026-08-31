package io.github.dev_abdulhay.telegramauth.managedbots;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

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

    void deleteByBotUserId(Long botUserId);
}
