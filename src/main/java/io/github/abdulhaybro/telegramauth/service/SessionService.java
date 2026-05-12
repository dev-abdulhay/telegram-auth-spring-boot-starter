package io.github.abdulhaybro.telegramauth.service;

import io.github.abdulhaybro.telegramauth.api.TelegramAuthApproveHandler;
import io.github.abdulhaybro.telegramauth.api.dto.AuthApproveResult;
import io.github.abdulhaybro.telegramauth.api.dto.AuthContext;
import io.github.abdulhaybro.telegramauth.api.dto.TelegramUserInfo;
import io.github.abdulhaybro.telegramauth.config.TelegramAuthProperties;
import io.github.abdulhaybro.telegramauth.entity.MTelegramAuthSession;
import io.github.abdulhaybro.telegramauth.entity.MTelegramAuthSession.Status;
import io.github.abdulhaybro.telegramauth.entity.MTelegramUser;
import io.github.abdulhaybro.telegramauth.repository.TelegramAuthSessionRepository;
import io.github.abdulhaybro.telegramauth.repository.TelegramUserRepository;
import io.github.abdulhaybro.telegramauth.security.TokenGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Core session lifecycle: create, lookup, approve, reject, sweep.
 *
 * <p>This service is transport-agnostic — the wait endpoints subscribe to
 * the {@link AuthEventBus}, and the bot side calls {@link #approve} or
 * {@link #reject}, which both publish on the bus.
 */
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final TelegramAuthSessionRepository sessionRepo;
    private final TelegramUserRepository userRepo;
    private final TokenGenerator tokenGenerator;
    private final AuthEventBus bus;
    private final TelegramAuthApproveHandler approveHandler;
    private final TelegramAuthProperties props;

    public SessionService(TelegramAuthSessionRepository sessionRepo,
                          TelegramUserRepository userRepo,
                          TokenGenerator tokenGenerator,
                          AuthEventBus bus,
                          TelegramAuthApproveHandler approveHandler,
                          TelegramAuthProperties props) {
        this.sessionRepo = sessionRepo;
        this.userRepo = userRepo;
        this.tokenGenerator = tokenGenerator;
        this.bus = bus;
        this.approveHandler = approveHandler;
        this.props = props;
    }

    public record CreatedSession(String rawToken, MTelegramAuthSession entity) {}

    @Transactional
    public CreatedSession create(String ipAddress, String userAgent) {
        String raw = tokenGenerator.newToken();
        MTelegramAuthSession s = new MTelegramAuthSession();
        s.setTokenHash(tokenGenerator.hash(raw));
        s.setIpAddress(ipAddress);
        s.setUserAgent(userAgent);
        s.setCreatedAt(OffsetDateTime.now());
        s.setExpiresAt(s.getCreatedAt().plus(props.getSession().getTtl()));
        s.setStatus(Status.PENDING);
        sessionRepo.save(s);
        return new CreatedSession(raw, s);
    }

    @Transactional(readOnly = true)
    public Optional<MTelegramAuthSession> findByRawToken(String rawToken) {
        return sessionRepo.findByTokenHash(tokenGenerator.hash(rawToken));
    }

    public String hash(String rawToken) {
        return tokenGenerator.hash(rawToken);
    }

    @Transactional
    public void approve(String tokenHash, MTelegramUser telegramUser) {
        MTelegramAuthSession s = sessionRepo.findByTokenHash(tokenHash).orElse(null);
        if (s == null || s.getStatus() != Status.PENDING) {
            log.debug("approve: session not found or not pending");
            return;
        }
        if (s.getExpiresAt().isBefore(OffsetDateTime.now())) {
            s.setStatus(Status.EXPIRED);
            sessionRepo.save(s);
            bus.publish(tokenHash, AuthEvent.expired());
            return;
        }

        AuthContext ctx = new AuthContext(s.getIpAddress(), s.getUserAgent());
        TelegramUserInfo info = new TelegramUserInfo(
                telegramUser.getTelegramId(),
                telegramUser.getPhone(),
                telegramUser.getFirstName(),
                telegramUser.getLastName(),
                telegramUser.getUsername(),
                telegramUser.getLanguageCode(),
                telegramUser.getExternalUserId()
        );

        AuthApproveResult result;
        try {
            result = approveHandler.onApprove(info, ctx);
        } catch (RuntimeException e) {
            log.error("host approve handler threw", e);
            throw e;
        }

        s.setStatus(Status.APPROVED);
        s.setApprovedAt(OffsetDateTime.now());
        s.setTelegramUserId(telegramUser.getId());
        sessionRepo.save(s);
        bus.publish(tokenHash, AuthEvent.approved(result.payload()));
    }

    @Transactional
    public void reject(String tokenHash) {
        MTelegramAuthSession s = sessionRepo.findByTokenHash(tokenHash).orElse(null);
        if (s == null || s.getStatus() != Status.PENDING) return;
        s.setStatus(Status.REJECTED);
        sessionRepo.save(s);
        bus.publish(tokenHash, AuthEvent.rejected());
    }

    /** Scheduled sweep that flips overdue PENDING rows to EXPIRED. */
    @Scheduled(cron = "${telegram.auth.session.cleanup-cron:0 */5 * * * *}")
    @Transactional
    public void sweepExpired() {
        int updated = sessionRepo.markExpired(Status.EXPIRED, Status.PENDING, OffsetDateTime.now());
        if (updated > 0) {
            log.info("expired sessions swept: {}", updated);
        }
    }
}
