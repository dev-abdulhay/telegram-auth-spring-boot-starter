package io.github.dev_abdulhay.telegramauth.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession.Status;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.service.AbstractSessionService;
import io.github.dev_abdulhay.telegramauth.service.AuthEvent;
import io.github.dev_abdulhay.telegramauth.service.SessionRateLimitException;
import io.github.dev_abdulhay.telegramauth.web.dto.CreateSessionRequest;
import io.github.dev_abdulhay.telegramauth.web.dto.CreateSessionResponse;
import io.github.dev_abdulhay.telegramauth.web.dto.SessionStatusResponse;
import io.github.dev_abdulhay.telegramauth.web.dto.WaitResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Default REST surface, generic over the host's user/session subtypes. NOT a
 * bean itself — the host subclass adds {@code @RestController} and the routing
 * {@code @RequestMapping(prefix)}. Spring picks up these inherited handler
 * annotations. Override any method to change behaviour.
 */
public abstract class AbstractTelegramAuthController<U extends BaseTelegramUser, S extends BaseAuthSession> {

    private static final Logger log = LoggerFactory.getLogger(AbstractTelegramAuthController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    protected final AbstractSessionService<U, S> sessionService;
    protected final TelegramBotModule module;

    protected AbstractTelegramAuthController(AbstractSessionService<U, S> sessionService, TelegramBotModule module) {
        this.sessionService = sessionService;
        this.module = module;
    }

    @PostMapping("/session")
    public CreateSessionResponse create(@RequestBody(required = false) CreateSessionRequest body,
                                        HttpServletRequest req) {
        String ip = clientIp(req);
        String ua = req.getHeader("User-Agent");
        AbstractSessionService.CreatedSession created = sessionService.create(ip, ua);
        String deepLink = "https://t.me/" + module.getUsername() + "?start=" + created.rawToken();
        return new CreateSessionResponse(
                created.rawToken(), deepLink, created.entity().getExpiresAt(), List.of("POLL"));
    }

    /**
     * Long-polls for the next session state the caller has not seen yet. The
     * approval payload is delivered on the live subscription when possible, and
     * is also persisted on the session row, so a poll of an already-APPROVED
     * session returns the stored payload. The bus subscription is registered
     * <em>before</em> the final status check, so a transition landing in between
     * is never missed.
     *
     * <p>{@code since} names the state the client already knows:
     * <ul>
     *   <li>omitted — the pre-0.4.0 contract: only terminal states are returned,
     *       and an {@code AWAITING_CODE} transition arriving mid-poll is answered
     *       with {@code 204} so the client simply polls again;</li>
     *   <li>{@code PENDING} — opts into the confirmation-code step and yields
     *       {@code 202} plus the browser-visible {@code confirmCode};</li>
     *   <li>{@code AWAITING_CODE} — waits for a terminal state. This is what stops
     *       a client from busy-looping on the state it is already in.</li>
     * </ul>
     */
    @GetMapping("/session/{token}/poll")
    public DeferredResult<ResponseEntity<WaitResponse>> poll(@PathVariable String token,
                                                             @RequestParam(required = false) String since) {
        String hash = sessionService.hash(token);
        boolean wantsCode = "PENDING".equalsIgnoreCase(since);
        S s = sessionService.findByRawToken(token).orElse(null);
        if (s == null) {
            return immediate(ResponseEntity.status(HttpStatus.GONE).build());
        }
        ResponseEntity<WaitResponse> ready = immediateResponse(s, hash, wantsCode);
        if (ready != null) {
            return immediate(ready);
        }

        long remainingMs = Duration.between(OffsetDateTime.now(), s.getExpiresAt()).toMillis();
        long timeoutMs = Math.min(module.getPollingTimeout().toMillis(), Math.max(remainingMs, 0));

        DeferredResult<ResponseEntity<WaitResponse>> result = new DeferredResult<>(timeoutMs);
        result.onTimeout(() -> result.setResult(ResponseEntity.noContent().build()));

        Consumer<AuthEvent> listener = ev -> {
            ResponseEntity<WaitResponse> resp = switch (ev.type()) {
                case APPROVED -> ResponseEntity.ok(new WaitResponse("APPROVED", ev.payload()));
                case REJECTED -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(new WaitResponse("REJECTED", Map.of()));
                case EXPIRED -> ResponseEntity.status(HttpStatus.GONE).build();
                // a client that did not ask for the code step gets the same answer
                // as a timeout, so it re-polls instead of hanging for nothing
                case AWAITING_CODE -> wantsCode ? awaitingCodeResponse(hash) : ResponseEntity.noContent().build();
            };
            result.setResult(resp);
        };
        module.getBus().subscribe(hash, listener);
        result.onCompletion(() -> module.getBus().unsubscribe(hash, listener));

        // Re-check AFTER subscribing: a transition that landed between the first
        // read and the subscription is caught here from the DB.
        S fresh = sessionService.findByRawToken(token).orElse(null);
        ResponseEntity<WaitResponse> late;
        if (fresh == null) {
            late = ResponseEntity.status(HttpStatus.GONE).build();
        } else {
            late = immediateResponse(fresh, hash, wantsCode);
        }
        if (late != null) {
            result.setResult(late);
        }
        return result;
    }

    @GetMapping("/session/{token}/status")
    public ResponseEntity<SessionStatusResponse> status(@PathVariable String token) {
        return sessionService.findByRawToken(token)
                .map(s -> ResponseEntity.ok(new SessionStatusResponse(s.getStatus().name(), s.getExpiresAt())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.GONE).build());
    }

    @DeleteMapping("/session/{token}")
    public ResponseEntity<Void> cancel(@PathVariable String token) {
        sessionService.findByRawToken(token).ifPresent(s -> {
            if (s.getStatus() == Status.PENDING || s.getStatus() == Status.AWAITING_CODE) {
                sessionService.reject(s.getTokenHash());
            }
        });
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(SessionRateLimitException.class)
    public ResponseEntity<Void> onRateLimited(SessionRateLimitException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
    }

    /** Response for a state the caller has not seen yet, or {@code null} to keep waiting. */
    private ResponseEntity<WaitResponse> immediateResponse(S s, String hash, boolean wantsCode) {
        if (s.getStatus() == Status.APPROVED) {
            return ResponseEntity.ok(new WaitResponse("APPROVED", readPayload(s)));
        }
        if (s.getStatus() == Status.REJECTED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new WaitResponse("REJECTED", Map.of()));
        }
        // checked before AWAITING_CODE so an overdue half-finished login reads as gone
        if (s.getStatus() == Status.EXPIRED || s.getExpiresAt().isBefore(OffsetDateTime.now())) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }
        if (s.getStatus() == Status.AWAITING_CODE && wantsCode) {
            return awaitingCodeResponse(hash);
        }
        return null;
    }

    private ResponseEntity<WaitResponse> awaitingCodeResponse(String hash) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new WaitResponse(
                "AWAITING_CODE", Map.of(), module.getConfirmCodeGenerator().codeFor(hash)));
    }

    private Map<String, Object> readPayload(S s) {
        String json = s.getApprovePayload();
        if (json == null || json.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("stored approve payload unreadable", e);
            return Map.of();
        }
    }

    private static <T> DeferredResult<T> immediate(T value) {
        DeferredResult<T> dr = new DeferredResult<>();
        dr.setResult(value);
        return dr;
    }

    /**
     * Client IP for auditing/rate-limiting. {@code X-Forwarded-For} is honoured
     * only when the module opted in via {@code trustProxyHeaders(true)} — the
     * header is trivially spoofable when the app is reached directly.
     *
     * <p>Entries are counted from the <em>right</em>, skipping one per trusted
     * hop ({@code trustedProxyHops}): each trusted proxy appends the peer it
     * received from, so with N hops the client sits N entries from the end and
     * everything further left came from the client itself and can be forged.
     * Reading the last entry unconditionally would be wrong behind a CDN — every
     * request would report the CDN's address and share one rate-limit bucket.
     *
     * <p>A header with fewer entries than the configured hop count did not
     * traverse the expected chain, so the socket address is used instead of
     * trusting a value we cannot place.
     */
    private String clientIp(HttpServletRequest req) {
        if (module.isTrustProxyHeaders()) {
            String ip = forwardedFor(req.getHeader("X-Forwarded-For"), module.getTrustedProxyHops());
            if (ip != null) return ip;
        }
        return req.getRemoteAddr();
    }

    /**
     * Picks the client entry out of an {@code X-Forwarded-For} value, or
     * {@code null} when the header cannot be trusted to hold one.
     *
     * @param trustedHops number of trusted proxies between the client and here
     */
    static String forwardedFor(String header, int trustedHops) {
        if (header == null || header.isBlank()) return null;
        String[] hops = header.split(",");
        int idx = hops.length - trustedHops;
        if (idx < 0) {
            log.debug("X-Forwarded-For has {} entries, fewer than the {} trusted hops — ignoring it",
                    hops.length, trustedHops);
            return null;
        }
        String ip = hops[idx].trim();
        return ip.isEmpty() ? null : ip;
    }
}
