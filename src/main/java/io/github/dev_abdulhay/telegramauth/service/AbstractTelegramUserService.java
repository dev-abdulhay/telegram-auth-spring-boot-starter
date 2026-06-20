package io.github.dev_abdulhay.telegramauth.service;

import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.repository.BaseTelegramUserRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Default user lifecycle, generic over the host's {@link BaseTelegramUser}
 * subtype. Generics cannot {@code new U()}, so a {@link Supplier} factory is
 * injected (host passes {@code MyUser::new}). Override any method to change
 * behaviour.
 */
public abstract class AbstractTelegramUserService<U extends BaseTelegramUser> {

    protected final BaseTelegramUserRepository<U> repo;
    private final Supplier<U> factory;

    protected AbstractTelegramUserService(BaseTelegramUserRepository<U> repo, Supplier<U> factory) {
        this.repo = repo;
        this.factory = factory;
    }

    /** Upsert by telegramId and mark the user ACTIVE. */
    @Transactional
    public U register(Long telegramId, String phone, String firstName,
                      String lastName, String username, String languageCode) {
        U user = repo.findByTelegramId(telegramId).orElseGet(factory);
        OffsetDateTime now = OffsetDateTime.now();
        if (user.getCreatedAt() == null) {
            user.setCreatedAt(now);
        }
        user.setTelegramId(telegramId);
        user.setPhone(phone);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setUsername(username);
        user.setLanguageCode(languageCode);
        user.setStatus(BaseTelegramUser.Status.ACTIVE);
        user.setUpdatedAt(now);
        return repo.save(user);
    }

    @Transactional(readOnly = true)
    public Optional<U> findByTelegramId(Long telegramId) {
        return repo.findByTelegramId(telegramId);
    }
}
