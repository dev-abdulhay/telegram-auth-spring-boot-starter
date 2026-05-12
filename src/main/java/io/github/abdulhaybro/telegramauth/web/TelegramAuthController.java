package io.github.abdulhaybro.telegramauth.web;

import io.github.abdulhaybro.telegramauth.config.TelegramAuthProperties;
import io.github.abdulhaybro.telegramauth.entity.MTelegramAuthSession;
import io.github.abdulhaybro.telegramauth.entity.MTelegramAuthSession.Status;
import io.github.abdulhaybro.telegramauth.service.AuthEvent;
import io.github.abdulhaybro.telegramauth.service.AuthEventBus;
import io.github.abdulhaybro.telegramauth.service.SessionService;
import io.github.abdulhaybro.telegramauth.web.dto.CreateSessionRequest;
import io.github.abdulhaybro.telegramauth.web.dto.CreateSessionResponse;
import io.github.abdulhaybro.telegramauth.web.dto.SessionStatusResponse;
import io.github.abdulhaybro.telegramauth.web.dto.WaitResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Consumer;

@RestController
@RequestMapping("${telegram.auth.base-path:/api/tg-auth}")
public class TelegramAuthController {

    private final SessionService sessionService;
    private final AuthEventBus bus;
    private final TelegramAuthProperties props;

    public TelegramAuthController(SessionService sessionService,
                                  AuthEventBus bus,
                                  TelegramAuthProperties props) {
        this.sessionService = sessionService;
        this.bus = bus;
        this.props = props;
    }

    @PostMapping("/session")
    public CreateSessionResponse create(@RequestBody(required = false) CreateSessionRequest body,
                                        HttpServletRequest req) {
        String ip = clientIp(req);
        String ua = req.getHeader("User-Agent");
        SessionService.CreatedSession created = sessionService.create(ip, ua);
        String deepLink = "https://t.me/" + props.getBot().getUsername()
                + "?start=" + created.rawToken();
        return new CreateSessionResponse(
                created.rawToken(),
                deepLink,
                created.entity().getExpiresAt(),
                List.of("POLL")
        );
    }

    @GetMapping("/session/{token}/poll")
    public DeferredResult<ResponseEntity<WaitResponse>> poll(@PathVariable String token) {
        String hash = sessionService.hash(token);
        MTelegramAuthSession s = sessionService.findByRawToken(token).orElse(null);

        if (s == null) {
            DeferredResult<ResponseEntity<WaitResponse>> r = new DeferredResult<>();
            r.setResult(ResponseEntity.status(HttpStatus.GONE).build());
            return r;
        }

        // Terminal — answer immediately and never subscribe.
        if (s.getStatus() == Status.APPROVED) {
            return immediate(ResponseEntity.ok(new WaitResponse("APPROVED", java.util.Map.of())));
        }
        if (s.getStatus() == Status.REJECTED) {
            return immediate(ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new WaitResponse("REJECTED", java.util.Map.of())));
        }
        if (s.getStatus() == Status.EXPIRED
                || s.getExpiresAt().isBefore(OffsetDateTime.now())) {
            return immediate(ResponseEntity.status(HttpStatus.GONE).build());
        }

        long remainingMs = Duration.between(OffsetDateTime.now(), s.getExpiresAt()).toMillis();
        long timeoutMs = Math.min(props.getTransport().getPolling().getMaxWait().toMillis(),
                                  Math.max(remainingMs, 0));

        DeferredResult<ResponseEntity<WaitResponse>> result = new DeferredResult<>(timeoutMs);
        // No body on timeout — 204 lets the client reopen.
        result.onTimeout(() -> result.setResult(ResponseEntity.noContent().build()));

        Consumer<AuthEvent> listener = ev -> {
            ResponseEntity<WaitResponse> resp = switch (ev.type()) {
                case APPROVED -> ResponseEntity.ok(new WaitResponse("APPROVED", ev.payload()));
                case REJECTED -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(new WaitResponse("REJECTED", java.util.Map.of()));
                case EXPIRED -> ResponseEntity.status(HttpStatus.GONE).build();
            };
            result.setResult(resp);
        };
        bus.subscribe(hash, listener);
        result.onCompletion(() -> bus.unsubscribe(hash, listener));
        return result;
    }

    @GetMapping("/session/{token}/status")
    public ResponseEntity<SessionStatusResponse> status(@PathVariable String token) {
        return sessionService.findByRawToken(token)
                .map(s -> ResponseEntity.ok(
                        new SessionStatusResponse(s.getStatus().name(), s.getExpiresAt())))
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
