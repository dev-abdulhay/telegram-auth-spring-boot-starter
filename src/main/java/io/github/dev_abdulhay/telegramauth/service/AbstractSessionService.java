package io.github.dev_abdulhay.telegramauth.service;

import io.github.dev_abdulhay.telegramauth.api.dto.AuthApproveResult;
import io.github.dev_abdulhay.telegramauth.api.dto.AuthContext;
import io.github.dev_abdulhay.telegramauth.api.dto.TelegramUserInfo;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession.Status;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.repository.BaseAuthSessionRepository;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Default session lifecycle, generic over the host's user/session subtypes.
 * Transport-agnostic: terminal transitions are published on the module's
 * {@link AuthEventBus}. Override any method to change behaviour.
 */
public abstract class AbstractSessionService<U extends BaseTelegramUser, S extends BaseAuthSession> {

    private static final Logger log = LoggerFactory.getLogger(AbstractSessionService.class);

    protected final BaseAuthSessionRepository<S> sessionRepo;
    private final Supplier<S> factory;
    private final TokenGenerator tokenGenerator;
    protected final TelegramBotModule module;

    protected AbstractSessionService(BaseAuthSessionRepository<S> sessionRepo,
                                     Supplier<S> factory,
                                     TokenGenerator tokenGenerator,
                                     TelegramBotModule module) {
        this.sessionRepo = sessionRepo;
        this.factory = factory;
        this.tokenGenerator = tokenGenerator;
        this.module = module;
    }

    public record CreatedSession(String rawToken, BaseAuthSession entity) {}

    @Transactional
    public CreatedSession create(String ipAddress, String userAgent) {
        String raw = tokenGenerator.newToken();
        S s = factory.get();
        s.setTokenHash(tokenGenerator.hash(raw));
        s.setIpAddress(ipAddress);
        s.setUserAgent(userAgent);
        s.setCreatedAt(OffsetDateTime.now());
        s.setExpiresAt(s.getCreatedAt().plus(module.getSessionTtl()));
        s.setStatus(Status.PENDING);
        sessionRepo.save(s);
        return new CreatedSession(raw, s);
    }

    @Transactional(readOnly = true)
    public Optional<S> findByRawToken(String rawToken) {
        return sessionRepo.findByTokenHash(tokenGenerator.hash(rawToken));
    }

    public String hash(String rawToken) {
        return tokenGenerator.hash(rawToken);
    }

    @Transactional
    public void approve(String tokenHash, U user) {
        S s = sessionRepo.findByTokenHash(tokenHash).orElse(null);
        if (s == null || s.getStatus() != Status.PENDING) {
            log.debug("approve: session not found or not pending");
            return;
        }
        if (s.getExpiresAt().isBefore(OffsetDateTime.now())) {
            s.setStatus(Status.EXPIRED);
            sessionRepo.save(s);
            module.getBus().publish(tokenHash, AuthEvent.expired());
            return;
        }

        AuthContext ctx = new AuthContext(s.getIpAddress(), s.getUserAgent());
        TelegramUserInfo info = new TelegramUserInfo(
                user.getTelegramId(), user.getPhone(), user.getFirstName(),
                user.getLastName(), user.getUsername(), user.getLanguageCode(),
                user.getExternalUserId());

        AuthApproveResult result;
        try {
            result = module.getApproveHandler().onApprove(info, ctx);
        } catch (RuntimeException e) {
            log.error("host approve handler threw", e);
            throw e;
        }

        s.setStatus(Status.APPROVED);
        s.setApprovedAt(OffsetDateTime.now());
        s.setTelegramUserId(user.getId());
        sessionRepo.save(s);
        module.getBus().publish(tokenHash, AuthEvent.approved(result.payload()));
    }

    @Transactional
    public void reject(String tokenHash) {
        S s = sessionRepo.findByTokenHash(tokenHash).orElse(null);
        if (s == null || s.getStatus() != Status.PENDING) return;
        s.setStatus(Status.REJECTED);
        sessionRepo.save(s);
        module.getBus().publish(tokenHash, AuthEvent.rejected());
    }

    @Scheduled(cron = "${telegram.auth.cleanup-cron:0 */5 * * * *}")
    @Transactional
    public void sweepExpired() {
        List<S> overdue = sessionRepo.findByStatusAndExpiresAtBefore(Status.PENDING, OffsetDateTime.now());
        if (overdue.isEmpty()) return;
        overdue.forEach(s -> s.setStatus(Status.EXPIRED));
        sessionRepo.saveAll(overdue);
        log.info("expired sessions swept: {}", overdue.size());
    }
}
