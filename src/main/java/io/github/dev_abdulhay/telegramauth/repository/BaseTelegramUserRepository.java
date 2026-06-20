package io.github.dev_abdulhay.telegramauth.repository;

import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Base repository for any {@link BaseTelegramUser} subtype. Host repositories
 * extend this with their concrete entity: {@code interface AdminUserRepository
 * extends BaseTelegramUserRepository<AdminUser> {}}.
 */
@NoRepositoryBean
public interface BaseTelegramUserRepository<U extends BaseTelegramUser>
        extends JpaRepository<U, Long> {

    Optional<U> findByTelegramId(Long telegramId);
}
