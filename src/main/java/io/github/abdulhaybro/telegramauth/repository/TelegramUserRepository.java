package io.github.abdulhaybro.telegramauth.repository;

import io.github.abdulhaybro.telegramauth.entity.MTelegramUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TelegramUserRepository extends JpaRepository<MTelegramUser, Long> {

    Optional<MTelegramUser> findByTelegramId(Long telegramId);
}
