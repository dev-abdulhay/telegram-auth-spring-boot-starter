package io.github.dev_abdulhay.telegramauth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Default session lifecycle, generic over the host's user/session subtypes.
 * Transport-agnostic: terminal transitions are published on the module's
 * {@link AuthEventBus} after the surrounding transaction commits, and the
 * approve payload is also persisted on the session row so late pollers can
 * still fetch it. Override any method to change behaviour.
 */
public abstract class AbstractSessionService<U extends BaseTelegramUser, S extends BaseAuthSession> {

    private static final Logger log = LoggerFactory.getLogger(AbstractSessionService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<Status> TERMINAL_STATUSES = List.of(Status.APPROVED, Status.REJECTED, Status.EXPIRED);
    /** Matches the {@code approve_payload} column length on {@code BaseAuthSession}. */
    private static final int MAX_PAYLOAD_CHARS = 4000;

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

    /**
     * <p><b>Best-effort limit.</b> The count and the insert are two statements,
     * not one atomic operation, so a burst of genuinely simultaneous requests
     * from one IP can land a few rows over {@code maxPendingPerIp}. It is a
     * flood brake, not a hard quota — put a real rate limiter (gateway, WAF,
     * bucket filter) in front if you need an exact ceiling.
     *
     * @throws SessionRateLimitException when the IP already holds
     *         {@code maxPendingPerIp} live (PENDING, not yet expired) sessions
     *         (0 disables the check). Overdue sessions are ignored so a caller
     *         is never blocked while waiting for the sweeper to run.
     */
    @Transactional
    public CreatedSession create(String ipAddress, String userAgent) {
        int limit = module.getMaxPendingPerIp();
        if (limit > 0 && ipAddress != null && !ipAddress.isBlank()
                && sessionRepo.countByIpAddressAndStatusAndExpiresAtAfter(
                        ipAddress, Status.PENDING, OffsetDateTime.now()) >= limit) {
            throw new SessionRateLimitException(ipAddress);
        }
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

    /**
     * Approves a PENDING, non-expired session. The row is locked for the
     * transition so concurrent approve/reject calls serialize instead of
     * double-firing the host handler.
     *
     * @return {@code true} if the session was approved; {@code false} if it was
     *         missing, already terminal, or expired
     */
    @Transactional
    public boolean approve(String tokenHash, U user) {
        S s = sessionRepo.findWithLockByTokenHash(tokenHash).orElse(null);
        if (s == null || s.getStatus() != Status.PENDING) {
            log.debug("approve: session not found or not pending");
            return false;
        }
        if (s.getExpiresAt().isBefore(OffsetDateTime.now())) {
            s.setStatus(Status.EXPIRED);
            sessionRepo.save(s);
            publishAfterCommit(tokenHash, AuthEvent.expired());
            return false;
        }

        AuthContext ctx = new AuthContext(s.getIpAddress(), s.getUserAgent());
        TelegramUserInfo info = new TelegramUserInfo(
                user.getTelegramId(), user.getPhone(), user.getFirstName(),
                user.getLastName(), user.getUsername(), user.getLanguageCode());

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
        s.setApprovePayload(serializePayload(result.payload()));
        sessionRepo.save(s);
        publishAfterCommit(tokenHash, AuthEvent.approved(result.payload()));
        return true;
    }

    /** @return {@code true} if a PENDING session was rejected. */
    @Transactional
    public boolean reject(String tokenHash) {
        S s = sessionRepo.findWithLockByTokenHash(tokenHash).orElse(null);
        if (s == null || s.getStatus() != Status.PENDING) return false;
        s.setStatus(Status.REJECTED);
        sessionRepo.save(s);
        publishAfterCommit(tokenHash, AuthEvent.rejected());
        return true;
    }

    /**
     * Marks overdue PENDING sessions as EXPIRED and deletes terminal sessions
     * older than the module's {@code sessionRetention} (ZERO disables deletion).
     * This method is {@code @Scheduled}, so every concrete subclass bean becomes
     * its own scheduled sweeper, all sharing the global
     * {@code telegram.auth.cleanup-cron} expression; override this method to opt
     * out or change cadence.
     */
    @Scheduled(cron = "${telegram.auth.cleanup-cron:0 */5 * * * *}")
    @Transactional
    public void sweepExpired() {
        List<S> overdue = sessionRepo.findByStatusAndExpiresAtBefore(Status.PENDING, OffsetDateTime.now());
        if (!overdue.isEmpty()) {
            overdue.forEach(s -> s.setStatus(Status.EXPIRED));
            sessionRepo.saveAll(overdue);
            log.info("expired sessions swept: {}", overdue.size());
        }

        Duration retention = module.getSessionRetention();
        if (retention != null && !retention.isZero() && !retention.isNegative()) {
            long removed = sessionRepo.deleteByStatusInAndExpiresAtBefore(
                    TERMINAL_STATUSES, OffsetDateTime.now().minus(retention));
            if (removed > 0) {
                log.info("terminal sessions purged: {}", removed);
            }
        }
    }

    /**
     * Publishes after the surrounding transaction commits, so a subscriber can
     * never observe an event whose DB state was rolled back. Publishes
     * immediately when no transaction synchronization is active (plain unit
     * tests, non-transactional callers).
     */
    protected void publishAfterCommit(String tokenHash, AuthEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    module.getBus().publish(tokenHash, event);
                }
            });
        } else {
            module.getBus().publish(tokenHash, event);
        }
    }

    private String serializePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return null;
        try {
            String json = MAPPER.writeValueAsString(payload);
            if (json.length() > MAX_PAYLOAD_CHARS) {
                log.warn("approve payload is {} chars, exceeds the {}-char column; "
                        + "payload delivered via live event only", json.length(), MAX_PAYLOAD_CHARS);
                return null;
            }
            return json;
        } catch (Exception e) {
            log.error("approve payload serialization failed; payload delivered via live event only", e);
            return null;
        }
    }
}
