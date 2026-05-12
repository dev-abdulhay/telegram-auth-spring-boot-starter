package io.github.dev_abdulhay.telegramauth.repository;

import io.github.dev_abdulhay.telegramauth.entity.MTelegramAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.MTelegramAuthSession.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface TelegramAuthSessionRepository extends JpaRepository<MTelegramAuthSession, Long> {

    Optional<MTelegramAuthSession> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update MTelegramAuthSession s set s.status = :expired " +
           "where s.status = :pending and s.expiresAt < :now")
    int markExpired(@Param("expired") Status expired,
                    @Param("pending") Status pending,
                    @Param("now") OffsetDateTime now);
}
