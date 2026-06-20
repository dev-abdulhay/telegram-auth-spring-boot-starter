package io.github.dev_abdulhay.telegramauth.web;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession.Status;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.service.AbstractSessionService;
import io.github.dev_abdulhay.telegramauth.service.AuthEvent;
import io.github.dev_abdulhay.telegramauth.web.dto.CreateSessionRequest;
import io.github.dev_abdulhay.telegramauth.web.dto.CreateSessionResponse;
import io.github.dev_abdulhay.telegramauth.web.dto.SessionStatusResponse;
import io.github.dev_abdulhay.telegramauth.web.dto.WaitResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @GetMapping("/session/{token}/poll")
    public DeferredResult<ResponseEntity<WaitResponse>> poll(@PathVariable String token) {
        String hash = sessionService.hash(token);
        S s = sessionService.findByRawToken(token).orElse(null);
        if (s == null) {
            return immediate(ResponseEntity.status(HttpStatus.GONE).build());
        }
        if (s.getStatus() == Status.APPROVED) {
            return immediate(ResponseEntity.ok(new WaitResponse("APPROVED", Map.of())));
        }
        if (s.getStatus() == Status.REJECTED) {
            return immediate(ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new WaitResponse("REJECTED", Map.of())));
        }
        if (s.getStatus() == Status.EXPIRED || s.getExpiresAt().isBefore(OffsetDateTime.now())) {
            return immediate(ResponseEntity.status(HttpStatus.GONE).build());
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
            };
            result.setResult(resp);
        };
        module.getBus().subscribe(hash, listener);
        result.onCompletion(() -> module.getBus().unsubscribe(hash, listener));
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
            if (s.getStatus() == Status.PENDING) {
                sessionService.reject(s.getTokenHash());
            }
        });
        return ResponseEntity.noContent().build();
    }

    private static <T> DeferredResult<T> immediate(T value) {
        DeferredResult<T> dr = new DeferredResult<>();
        dr.setResult(value);
        return dr;
    }

    private static String clientIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            return (comma >= 0 ? fwd.substring(0, comma) : fwd).trim();
        }
        return req.getRemoteAddr();
    }
}
