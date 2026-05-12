# telegram-auth-spring-boot-starter — Tech Doc

**Status:** Draft v0.2
**Created:** 2026-04-10 14:30
**Last updated:** 2026-04-10 14:30 (resolved open questions §18)
**Owner:** a.uralov
**Target artifact:** `uz.aloqabank:telegram-auth-spring-boot-starter:0.1.0`

---

## 1. Overview

Reusable Spring Boot starter that adds Telegram-bot based authentication to any backend service. A web/mobile client initiates a login session, the user confirms in a Telegram bot, and the backend releases a project-defined login response (JWT, session cookie, whatever the host app uses).

### 1.1 Goals

- Drop-in dependency: add the starter, set a few properties, expose one callback bean — done.
- Support **three** client-side wait transports: long polling, SSE, WebSocket.
- Self-contained persistence: the starter creates and owns its own tables; no assumptions about the host app's user schema.
- Work behind corporate HTTP/HTTPS/SOCKS5 proxies.
- Horizontally scalable (Redis-backed event bus).
- Multi-language bot UX (uz/ru/en) with override capability.
- Runtime reconfiguration of bot token and proxy (no restart).

### 1.2 Non-goals

- 2FA / OTP combined flows.
- Acting as an OAuth2 provider.
- Built-in admin UI (backend endpoints only).
- Push-notification (FCM/APNs) integration — may come later.
- Telegram Login Widget (the web one). Only in-bot flow.
- MTProto proxy support.
- Running multiple bots in one instance. Exactly one bot per instance for MVP.

---

## 2. High-level flow

```
┌─────────┐         ┌─────────┐         ┌──────────────┐         ┌──────────┐
│  Client │         │ Backend │         │ Telegram Bot │         │ Telegram │
│(web/mob)│         │ + Lib   │         │   (inside    │         │   API    │
│         │         │         │         │   backend)   │         │          │
└────┬────┘         └────┬────┘         └──────┬───────┘         └────┬─────┘
     │                   │                     │                      │
     │ 1. POST /session  │                     │                      │
     ├──────────────────>│                     │                      │
     │ {token, botUrl}   │                     │                      │
     │<──────────────────┤                     │                      │
     │                   │                     │                      │
     │ 2. open botUrl    │                     │                      │
     │    (t.me/bot?start=token)                │                      │
     ├─────────────────────────────────────────────────────────────────│
     │                   │                     │                      │
     │ 3. wait/poll/sse/ws (holds connection)  │                      │
     ├──────────────────>│                     │                      │
     │                   │                     │                      │
     │                   │                     │ 4. /start <token>    │
     │                   │                     │<─────────────────────┤
     │                   │                     │                      │
     │                   │                     │ 5. check DB, ask     │
     │                   │                     │    phone/name OR     │
     │                   │                     │    show Approve btn  │
     │                   │                     ├─────────────────────>│
     │                   │                     │                      │
     │                   │                     │ 6. user taps Approve │
     │                   │                     │<─────────────────────┤
     │                   │                     │                      │
     │                   │ 7. publish(token,   │                      │
     │                   │    APPROVED, user)  │                      │
     │                   │<────────────────────┤                      │
     │                   │                     │                      │
     │                   │ 8. host app callback│                      │
     │                   │    → loginResponse  │                      │
     │ 9. release wait   │                     │                      │
     │<──────────────────┤                     │                      │
     │ {loginResponse}   │                     │                      │
     │                   │                     │                      │
```

---

## 3. Architecture

### 3.1 Component layout

```
telegram-auth-spring-boot-starter/
├── config/
│   ├── TelegramAuthAutoConfiguration      — @AutoConfiguration entry point
│   ├── TelegramAuthProperties             — @ConfigurationProperties
│   ├── BotClientConfig                    — bot HTTP client + proxy
│   └── RedisEventBusConfig                — optional, when Redis present
├── web/
│   ├── TelegramAuthController             — REST API (session CRUD, status)
│   ├── TelegramAuthPollController         — long polling endpoint
│   ├── TelegramAuthSseController          — SSE endpoint
│   └── TelegramAuthWsHandler              — WebSocket handler
├── service/
│   ├── SessionService                     — create, validate, expire
│   ├── TelegramUserService                — lookup, register, update
│   ├── AuthEventBus (interface)
│   │   ├── InMemoryAuthEventBus
│   │   └── RedisAuthEventBus
│   └── RateLimiter (interface)
│       ├── CaffeineRateLimiter
│       └── RedisRateLimiter
├── bot/
│   ├── TelegramBotRunner                  — starts long-polling loop
│   ├── BotUpdateDispatcher                — routes updates to handlers
│   ├── handler/
│   │   ├── StartCommandHandler
│   │   ├── ContactHandler
│   │   ├── NameConfirmHandler
│   │   ├── ApproveCallbackHandler
│   │   └── RejectCallbackHandler
│   └── MessageProvider                    — i18n
├── entity/
│   ├── MTelegramUser
│   ├── MTelegramAuthSession
│   └── MTelegramBotConfig                 — runtime-mutable bot config
├── repository/
│   ├── TelegramUserRepository
│   ├── TelegramAuthSessionRepository
│   └── TelegramBotConfigRepository
├── api/                                   — public extension points
│   ├── TelegramAuthApproveHandler         — MUST be implemented by host
│   ├── TelegramAuthRegisterHandler        — optional hook
│   └── dto/
│       ├── TelegramUserInfo
│       ├── CreateSessionResponse
│       ├── SessionStatusResponse
│       └── AuthApproveResult
├── security/
│   └── TokenGenerator                     — 32-byte SecureRandom, Base64URL
└── db/changelog/
    └── telegram-auth-changelog.xml        — Liquibase master
```

### 3.2 Request path by transport

All three transports share the same backend flow:

```
Client → Controller → SessionService.waitForApproval(token, strategy)
                              │
                              └──> AuthEventBus.subscribe(token)
                                           │
                                           │  (blocks until event)
                                           │
Bot side: ApproveCallbackHandler → SessionService.approve(token, userInfo)
                                           │
                                           └──> TelegramAuthApproveHandler.onApprove(...)
                                                           │
                                                           └──> AuthEventBus.publish(token, result)
```

`AuthWaitStrategy` interface is the abstraction that lets `SessionService` talk to any transport the same way:

```java
public interface AuthWaitStrategy<T> {
    T wait(String sessionToken, Duration timeout);
}
```

Concrete strategies:
- `DeferredResultWaitStrategy` — used by long polling (Spring `DeferredResult`).
- `SseEmitterWaitStrategy` — used by SSE (Spring `SseEmitter`).
- `WebSocketWaitStrategy` — used by WS handler (session map + send on event).

---

## 4. Flow sequences (detailed)

### 4.1 Registered user — happy path

```
Client                    Backend                        Bot                      User
  │                          │                            │                        │
  │ POST /api/tg-auth/session│                            │                        │
  ├─────────────────────────>│                            │                        │
  │   {token, botDeepLink,   │                            │                        │
  │    expiresAt}            │                            │                        │
  │<─────────────────────────┤                            │                        │
  │                          │                            │                        │
  │ GET .../wait?token=...   │                            │                        │
  ├─────────────────────────>│  (hold, subscribe on bus)  │                        │
  │                          │                            │ /start <token>         │
  │                          │                            │<───────────────────────│
  │                          │ lookup(chat_id)            │                        │
  │                          │ → registered               │                        │
  │                          │                            │ "Login from <ip>?      │
  │                          │                            │  [Approve] [Reject]"   │
  │                          │                            ├───────────────────────>│
  │                          │                            │                        │
  │                          │                            │ tap Approve            │
  │                          │                            │<───────────────────────│
  │                          │ approve(token, user)       │                        │
  │                          │<───────────────────────────┤                        │
  │                          │ host.onApprove(user)       │                        │
  │                          │ → AuthApproveResult(JWT)   │                        │
  │                          │ bus.publish(token, result) │                        │
  │ release                  │                            │                        │
  │<─────────────────────────┤                            │                        │
  │ {status: APPROVED,       │                            │                        │
  │  payload: <host JSON>}   │                            │                        │
```

### 4.2 New user — registration inside bot

```
Bot                                                      User
 │ /start <token>                                          │
 │<────────────────────────────────────────────────────────│
 │ lookup(chat_id) → not found                             │
 │ "Xush kelibsiz! Raqamingizni ulashing:"                 │
 │ [Contact share button]                                  │
 ├────────────────────────────────────────────────────────>│
 │                                                          │
 │ Contact {phone, first_name, last_name}                  │
 │<────────────────────────────────────────────────────────│
 │ persist MTelegramUser (status=PENDING_CONFIRM)          │
 │ "Ismingiz: <first_name> <last_name>. Tasdiqlaysizmi?"   │
 │ [Tasdiqlash] [Tahrirlash]                               │
 ├────────────────────────────────────────────────────────>│
 │                                                          │
 │ tap Tasdiqlash                                          │
 │<────────────────────────────────────────────────────────│
 │ user.status = ACTIVE                                    │
 │ "Login from <ip>? [Approve] [Reject]"                   │
 ├────────────────────────────────────────────────────────>│
 │                                                          │
 │ tap Approve                                              │
 │<────────────────────────────────────────────────────────│
 │ (same as 4.1 from here)                                 │
```

If user taps **Tahrirlash**, bot asks for first_name, then last_name (text input), then re-shows confirmation.

### 4.3 Reject flow

```
Bot                                                      User
 │ "Login from <ip>? [Approve] [Reject]"                   │
 ├────────────────────────────────────────────────────────>│
 │ tap Reject                                               │
 │<────────────────────────────────────────────────────────│
 │ session.status = REJECTED                                │
 │ bus.publish(token, REJECTED)                             │
 │ "Bekor qilindi."                                         │
 ├────────────────────────────────────────────────────────>│

Client side:
  wait endpoint returns 403 {status: REJECTED}
```

### 4.4 Expiration

- Session TTL = 180 s (configurable).
- Scheduled job runs every 30 s, marks `status = EXPIRED` for sessions past `expires_at`.
- Client-side wait endpoint: on timeout returns 204 (No Content) — client may reopen the wait.
- Server-side wait loops have their own hard ceiling (= `session.expires_at - now`), so a client reconnecting after expiration gets 410 Gone.

---

## 5. Database schema

All tables live under the configured schema (default: `public`) with a configurable prefix (default: `tg_auth_`).

### 5.1 `tg_auth_telegram_user`

| Column           | Type            | Notes                                           |
|------------------|-----------------|-------------------------------------------------|
| id               | BIGSERIAL PK    |                                                 |
| telegram_id      | BIGINT UNIQUE   | Telegram chat/user id                           |
| phone            | VARCHAR(20)     | E.164 format, no `+`                            |
| first_name       | VARCHAR(100)    |                                                 |
| last_name        | VARCHAR(100)    | NULL allowed                                    |
| username         | VARCHAR(50)     | NULL allowed (Telegram @username)               |
| language_code    | VARCHAR(5)      | uz/ru/en, detected from Telegram                |
| status           | VARCHAR(30)     | PENDING_CONFIRM / ACTIVE / BLOCKED              |
| external_user_id | VARCHAR(100)    | NULLABLE. Host app's own user id if mapped      |
| created_at       | TIMESTAMPTZ     | default now()                                   |
| updated_at       | TIMESTAMPTZ     | default now()                                   |

Indexes: `telegram_id` (unique), `phone`, `external_user_id` (non-unique, partial `WHERE external_user_id IS NOT NULL`).

**`external_user_id` is optional.** Host app can:
- **Ignore it** (decoupled mode): maintain its own mapping table between the host's user and telegram_user_id. Starter does not require this field.
- **Populate it** via `TelegramAuthRegisterHandler` (see §10.2): pass the host's user id back into the starter, which stores it here. Simplifies lookup — starter can answer "who is this telegram user in your system?" directly.

Both approaches are supported. Developer chooses per host app.

### 5.2 `tg_auth_session`

| Column           | Type          | Notes                                   |
|------------------|---------------|-----------------------------------------|
| id               | BIGSERIAL PK  |                                         |
| token_hash       | VARCHAR(64)   | SHA-256 of raw token, indexed           |
| telegram_user_id | BIGINT        | FK → tg_auth_telegram_user.id, NULL     |
| status           | VARCHAR(20)   | PENDING / APPROVED / REJECTED / EXPIRED |
| ip_address       | VARCHAR(45)   | IPv4/IPv6                               |
| user_agent       | VARCHAR(500)  |                                         |
| created_at       | TIMESTAMPTZ   |                                         |
| expires_at       | TIMESTAMPTZ   | created_at + TTL                        |
| approved_at      | TIMESTAMPTZ   | NULL until approval                     |
| metadata         | JSONB         | free-form, host app can read/write      |

Indexes: `token_hash` (unique), `(status, expires_at)` for cleanup job.

**Never store the raw token in the DB.** The client holds the raw token; the backend looks up by `sha256(rawToken)`. This limits damage if the DB leaks.

### 5.3 `tg_auth_bot_config`

Runtime-mutable bot configuration. Single row (id=1) — see §12.

| Column          | Type          | Notes                             |
|-----------------|---------------|-----------------------------------|
| id              | BIGSERIAL PK  |                                   |
| bot_token       | TEXT          | Plaintext bot token               |
| proxy_type      | VARCHAR(10)   | NONE / HTTP / SOCKS5              |
| proxy_host      | VARCHAR(255)  |                                   |
| proxy_port      | INTEGER       |                                   |
| proxy_username  | VARCHAR(100)  |                                   |
| proxy_password  | VARCHAR(255)  | Plaintext                         |
| updated_at      | TIMESTAMPTZ   |                                   |
| updated_by      | VARCHAR(100)  |                                   |

On startup the starter loads this row (if present) and merges over `application.yml` properties. Runtime reconfiguration endpoint (§7.3) updates this row and triggers a bot client rebuild.

**Storage policy:** bot token and proxy password are stored **plaintext**. Rationale:
- Bot token risk is limited (compromise = bot abuse, reissue takes minutes; not a data breach).
- The same secret already lives plaintext in `application.yml` / environment variables — encrypting only the DB copy would be theater security, since the decryption key would live in the same app.
- The config table is accessible only from the application; no end-user role can `SELECT` from it.
- **Compensating controls** (see §11.5): secrets are never logged, never returned by any GET endpoint, and the admin reconfigure endpoint is write-only.

### 5.4 Rate limiting

Redis-backed primarily (keys like `tg_auth:rl:ip:<ip>:<window>`). No DB table. Caffeine fallback is in-memory.

### 5.5 Liquibase

Master changelog: `db/changelog/telegram-auth-changelog.xml`, included by host application's master (`include file="..."` line added manually).

---

## 6. Configuration (`application.yml`)

```yaml
telegram:
  auth:
    enabled: true                        # master switch

    bot:
      token: "${TG_BOT_TOKEN:}"          # fallback if DB config absent
      polling-interval-ms: 1000
      polling-timeout-s: 30              # getUpdates long-poll timeout
      username: "mybot"                  # used to build t.me/<username> deep-links

    proxy:
      type: NONE                         # NONE | HTTP | SOCKS5
      host: ""
      port: 0
      username: ""
      password: ""

    session:
      ttl-seconds: 180
      cleanup-cron: "0 */5 * * * *"      # every 5 min

    transport:
      polling:
        enabled: true
        max-wait-seconds: 30             # DeferredResult timeout per call
      sse:
        enabled: true
        heartbeat-seconds: 15
      websocket:
        enabled: true
        path: "/ws/tg-auth"

    event-bus:
      type: AUTO                         # AUTO | IN_MEMORY | REDIS
      redis-channel-prefix: "tg_auth:events:"

    rate-limit:
      enabled: true
      ip-per-minute: 5
      ip-per-hour: 30
      telegram-user-per-hour: 10
      backend: AUTO                      # AUTO | CAFFEINE | REDIS

    db:
      schema: "public"
      table-prefix: "tg_auth_"

    i18n:
      default-language: uz
      supported: [uz, ru, en]

    geoip:
      enabled: false                     # optional enrichment for approve prompt
      provider: NONE                     # NONE | IP_API | IPINFO
      timeout-ms: 500                    # hard cap; lookup is non-blocking
      cache-ttl-minutes: 60

    admin:
      enabled: true
      role: "ROLE_ADMIN"                 # required role for reconfigure endpoint
```

`event-bus.type=AUTO` → Redis if a `RedisConnectionFactory` bean exists, else in-memory. Same for rate limit backend.

---

## 7. Public REST API

Base path: `/api/tg-auth` (configurable).

All endpoints follow the project convention of requiring `X-Front-Request-Id` header.

### 7.1 Create session

```
POST /api/tg-auth/session
Body: { "clientInfo": "optional string" }

Response 200:
{
  "token": "aB3xYz...",                    // raw token, client holds this
  "botDeepLink": "https://t.me/mybot?start=aB3xYz...",
  "expiresAt": "2026-04-10T14:33:00Z",
  "transports": ["POLL", "SSE", "WS"]      // enabled transports
}
```

Rate-limited by IP.

### 7.2 Wait endpoints

**Long polling:**
```
GET /api/tg-auth/session/{token}/poll
→ 200 {status: APPROVED, payload: {...host response...}}
→ 204 No Content (timeout — client may reopen)
→ 403 {status: REJECTED}
→ 410 Gone (session expired or not found)
```

Uses Spring `DeferredResult` with timeout = `min(max-wait-seconds, session.remainingTtl)`.

**SSE:**
```
GET /api/tg-auth/session/{token}/sse
Content-Type: text/event-stream

event: heartbeat
data: {}

event: approved
data: {"status":"APPROVED","payload":{...}}

event: rejected
data: {"status":"REJECTED"}

event: expired
data: {"status":"EXPIRED"}
```

Heartbeat every `sse.heartbeat-seconds`. On terminal event, server closes the stream.

**WebSocket:**
```
WS /ws/tg-auth?token=aB3xYz...

Server → Client messages:
{"type":"connected"}
{"type":"heartbeat"}
{"type":"approved","payload":{...}}
{"type":"rejected"}
{"type":"expired"}
```

Client sends nothing. Server closes connection after terminal event.

### 7.3 Session status (for diagnostics / fallback polling)

```
GET /api/tg-auth/session/{token}/status
→ { "status": "PENDING|APPROVED|REJECTED|EXPIRED", "expiresAt": "..." }
```

Does **not** return the approve payload (to prevent payload leaking via unintended GETs); for that, use a wait endpoint.

### 7.4 Cancel session

```
DELETE /api/tg-auth/session/{token}
→ 204
```

Client can abort a pending session.

### 7.5 Admin: reconfigure bot

```
POST /api/tg-auth/admin/reconfigure
Authorization: Bearer <admin JWT>
Body: {
  "botToken": "123:abc...",       // optional
  "proxy": {                       // optional
    "type": "SOCKS5",
    "host": "proxy.corp.local",
    "port": 1080,
    "username": "...",
    "password": "..."
  }
}
→ 200 { "status": "reconfigured" }
```

Persists to `tg_auth_bot_config`, rebuilds the bot HTTP client, restarts the polling loop. Old in-flight `getUpdates` call is cancelled.

Protected by Spring Security role specified in `telegram.auth.admin.role`.

---

## 8. Transport layer — design notes

### 8.1 Shared event bus

```java
public interface AuthEventBus {
    void subscribe(String tokenHash, Consumer<AuthEvent> listener);
    void unsubscribe(String tokenHash, Consumer<AuthEvent> listener);
    void publish(String tokenHash, AuthEvent event);
}
```

- **InMemoryAuthEventBus**: `ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer>>`. Single-instance only.
- **RedisAuthEventBus**: `ReactiveRedisTemplate` pub/sub on channel `tg_auth:events:{tokenHash}`. Each instance subscribes to the channel when a local wait arrives, unsubscribes when it completes.

### 8.2 Why separate endpoints, not content negotiation

Content negotiation (same URL, different `Accept` headers) is fragile across HTTP clients. Android's OkHttp and iOS's URLSession handle SSE differently from browsers. Separate paths keep each transport independent and let clients choose explicitly.

### 8.3 Fallback chain (client-side recommendation)

For maximum reach, clients should attempt transports in order: WS → SSE → Polling. If a corporate proxy strips WS and buffers SSE, polling always wins. Starter ships a client SDK reference but **does not enforce** the order — that's a client-side concern.

---

## 9. Bot UX — states and messages

### 9.1 State machine (per chat)

```
IDLE
  │
  │ /start <token>
  ▼
┌──────────────────┐
│ TOKEN_VALIDATED  │
└────────┬─────────┘
         │
   ┌─────┴──────┐
   │            │
registered    new
   │            │
   │            ▼
   │     AWAITING_CONTACT
   │            │  (Contact share)
   │            ▼
   │     AWAITING_NAME_CONFIRM
   │            │  (tap Confirm or edit)
   │            ▼
   │     (user persisted)
   │            │
   └────────┬───┘
            ▼
   AWAITING_APPROVE
            │
    ┌───────┴────────┐
    │                │
  Approve         Reject
    │                │
    ▼                ▼
  APPROVED       REJECTED
```

### 9.2 Messages (default uz)

| Key                          | Text                                                        |
|------------------------------|-------------------------------------------------------------|
| `start.invalid_token`        | "Havola yaroqsiz yoki muddati tugagan."                     |
| `start.welcome_new`          | "Xush kelibsiz! Iltimos, raqamingizni ulashing."            |
| `start.welcome_registered`   | "Salom, {firstName}! Tizimga kirishni tasdiqlaysizmi?"      |
| `contact.share_button`       | "📱 Raqamni ulashish"                                        |
| `name.confirm_prompt`        | "Ismingiz: {firstName} {lastName}. To'g'rimi?"              |
| `name.confirm_button`        | "✅ Ha, to'g'ri"                                             |
| `name.edit_button`           | "✏️ Tahrirlash"                                              |
| `name.ask_first_name`        | "Ismingizni kiriting:"                                      |
| `name.ask_last_name`         | "Familiyangizni kiriting:"                                  |
| `approve.prompt`             | "Kirish so'rovi: {ip}{location} dan. Tasdiqlaysizmi?"       |
| `approve.button`             | "✅ Tasdiqlash"                                              |
| `reject.button`              | "❌ Rad etish"                                               |
| `approve.done`               | "Tasdiqlandi. Web saytga qayting."                          |
| `reject.done`                | "Bekor qilindi."                                            |
| `error.expired`              | "So'rov muddati tugagan."                                   |
| `error.rate_limited`         | "Juda ko'p urinish. Keyinroq qayta urinib ko'ring."         |

`ru` and `en` variants live in `messages_tgauth_ru.properties` / `_en.properties`. Host app can override by providing same-named files in its own classpath (standard Spring `MessageSource` resolution).

**`approve.prompt` — `{location}` placeholder:**
- The `{location}` placeholder is optional enrichment for the approve prompt.
- **Default: empty string.** Only the raw IP is shown: `"Kirish so'rovi: 185.x.x.x dan. Tasdiqlaysizmi?"`.
- **Optional GeoIP enrichment:** if a free GeoIP service is available (e.g. ip-api.com free tier, ipinfo.io free tier), the starter calls it asynchronously at session create time and caches the result (Caffeine, 1h TTL). The resolved location (e.g. `", Toshkent, O'zbekiston"`) is prepended with a comma and space.
- **Graceful fallback:** if the GeoIP call fails (timeout, rate limit, service down), `{location}` resolves to empty string and the flow continues with just the IP. **The GeoIP lookup is never on the critical path.** Session creation does not wait for it; if the lookup has not returned by the time the bot sends the approve prompt, the prompt is sent without location.
- **Configuration:** `telegram.auth.geoip.enabled` (default `false`), `telegram.auth.geoip.provider` (`IP_API` / `IPINFO` / `NONE`), `telegram.auth.geoip.timeout-ms` (default `500`).
- **No host-provided service required for MVP.** If the host has its own GeoIP infrastructure later, a `GeoIpResolver` interface extension point can be added.

---

## 10. Extension points

### 10.1 `TelegramAuthApproveHandler` (required)

```java
public interface TelegramAuthApproveHandler {
    AuthApproveResult onApprove(TelegramUserInfo user, AuthContext ctx);
}
```

The host app **must** provide a bean of this type. This is where the host decides what "login success" means: issue a JWT, set a session cookie, whatever. The returned `AuthApproveResult.payload` is delivered to the waiting client verbatim.

```java
public record AuthApproveResult(Map<String, Object> payload) {}
```

### 10.2 `TelegramAuthRegisterHandler` (optional)

```java
public interface TelegramAuthRegisterHandler {
    void onFirstRegister(TelegramUserInfo user, RegisterContext ctx);
}

public class RegisterContext {
    // Host app may set this to link the telegram user to its own user table.
    // Starter will persist it into tg_auth_telegram_user.external_user_id.
    // Leave null to keep the mapping entirely outside the starter.
    public void setExternalUserId(String externalUserId) { ... }
}
```

Fired once, right after a brand-new user finishes the in-bot registration. Host app can create its own domain user here, send welcome email, etc. Runs in the same transaction as the user insert (host side should be fast or async-publish).

The `RegisterContext.setExternalUserId(...)` call is the only way to populate `external_user_id`. Host apps that prefer full decoupling simply don't call it.

### 10.3 `MessageProvider` (optional)

```java
public interface MessageProvider {
    String get(String key, Locale locale, Object... args);
}
```

Default implementation delegates to Spring `MessageSource`. Override to pull messages from a DB, CMS, etc.

### 10.4 `AuthContextEnricher` (optional)

```java
public interface AuthContextEnricher {
    void enrich(AuthContext ctx, HttpServletRequest req);
}
```

Lets the host add fields (geo-IP, device fingerprint, etc.) to the `AuthContext` passed into `onApprove`.

---

## 11. Security

### 11.1 Token generation

- 32 bytes from `SecureRandom`, Base64URL-encoded (43 chars, no padding).
- Raw token lives only in: HTTP response body, client storage, URL fragment of the Telegram deep-link.
- DB stores only `SHA-256(rawToken)` as `token_hash`.

### 11.2 Logging

- Full token: **never** logged.
- For tracing, log `SHA-256(rawToken)[0..16]` (first 8 hex chars) with MDC key `tg_auth_session_hash`.
- MDC also carries `X-Front-Request-Id` per project convention.

### 11.3 Session protection

- One-time use: after `APPROVED` or `REJECTED`, status is terminal. Wait endpoints return the terminal response once, then 410 on subsequent calls.
- TTL enforced server-side on every lookup, regardless of DB status.
- `ip_address` and `user_agent` captured at create time and **shown to the user in the Telegram approve prompt**, so they can reject unexpected login attempts.

### 11.4 Rate limiting

- IP: 5 `POST /session` per minute, 30 per hour.
- Telegram user: 10 failed approve attempts per hour → temporary block (1 hour).
- Backend: Redis primary (`INCR` with `EXPIRE`), Caffeine fallback.

### 11.5 Bot token & proxy credentials

- Stored **plaintext** in `tg_auth_bot_config` (see §5.3 for rationale).
- Access control: the config table is readable only by the application's DB user; no end-user roles have direct access.
- **Never logged.** Bot token is masked in all log output (first 6 chars + `...`, last 4 chars). Proxy password is never logged at any level.
- **Never returned** by any GET endpoint. The admin reconfigure endpoint is strictly write-only — there is no "read current config" endpoint.
- The health endpoint (`/api/tg-auth/admin/health`) returns only non-sensitive fields (last successful `getMe` timestamp, proxy type, proxy host, but not credentials).

### 11.6 CSRF / Replay

- Token-based API, no cookies for the wait flow → CSRF not applicable.
- Session is single-use → replay not possible.
- Admin reconfigure endpoint follows the host's normal Spring Security chain (including CSRF if host enables it).

---

## 12. Proxy configuration

### 12.1 Supported

- **HTTP / HTTPS proxy** (with optional basic auth)
- **SOCKS5** (with optional username/password auth)
- **No proxy** (direct)

### 12.2 Implementation

Bot HTTP client is a Java 17 `HttpClient` built with:

```java
HttpClient.newBuilder()
    .proxy(proxySelector)   // ProxySelector.of(InetSocketAddress) for HTTP
                            //   or custom for SOCKS5
    .authenticator(...)
    .build();
```

For SOCKS5 at socket level, Java `HttpClient` does not support SOCKS directly → fall back to wiring via `java.net.Socket` factory when `proxy.type=SOCKS5`. The starter hides this detail.

Proxy password is stored plaintext in `tg_auth_bot_config.proxy_password` (see §5.3 and §11.5).

### 12.3 Runtime reconfiguration

1. Admin POST to `/api/tg-auth/admin/reconfigure` with new proxy settings.
2. `BotConfigService.update()` persists the new row in `tg_auth_bot_config` (encrypted).
3. `BotClientHolder.rebuild()` is called:
   - Cancels the current `getUpdates` long poll (closes the old `HttpClient`).
   - Builds a fresh `HttpClient` from new config.
   - Starts a new polling loop.
4. Any in-flight auth sessions survive: they hold their own DB records, and the new bot client continues serving them.

### 12.4 Health

- `HEALTH GET /api/tg-auth/admin/health` returns bot connectivity status (last successful `getMe` timestamp, last error if any).
- Contributes to Spring Boot actuator `/health` when `management.health.telegram-auth.enabled=true`.

---

## 13. Testing strategy

### 13.1 Unit tests

- `SessionService` — TTL, state transitions, token hashing.
- `TokenGenerator` — entropy and uniqueness over 10k samples.
- `RateLimiter` implementations — window math.
- `BotUpdateDispatcher` — routing logic.

### 13.2 Integration tests (Testcontainers)

- Postgres container: full DB schema loaded via Liquibase.
- Redis container: event bus + rate limit.
- **Mock Telegram API**: WireMock stubbing `api.telegram.org` responses. `BotClientHolder` injected with a `baseUrl` override pointing at WireMock.
- **Mock host callback**: test `@Configuration` provides a fake `TelegramAuthApproveHandler` that returns `{"jwt":"test-jwt"}`.

### 13.3 End-to-end scenarios

**Automated (CI):**
1. Create session → simulate `/start` update → simulate Approve callback → assert client wait returns approved payload.
2. Create session → do nothing → assert timeout / expired.
3. Create session → simulate Reject → assert client gets 403.
4. New user: no contact → assert bot asks for contact → simulate Contact update → assert user persisted.
5. Runtime reconfigure: change proxy mid-flight → assert bot continues working.

**Manual staging only (not CI):**
6. **Horizontal scaling:** two instances sharing Redis → session created on instance A, approve arrives on instance B → instance A's waiting client gets approved. Executed by QA before each release, tracked in a release checklist. Rationale: running two full app instances + Postgres + Redis in CI adds significant pipeline time for a single integration point that rarely regresses; a manual smoke test in staging gives the same confidence at a fraction of the cost.

### 13.4 Load test (non-blocking)

- 1k concurrent wait sessions on long polling.
- 1k concurrent on SSE.
- 1k concurrent on WS.
- Measure p50/p95/p99 for wait release latency.

---

## 14. Packaging & publishing

### 14.1 Gradle setup

```
telegram-auth-spring-boot-starter/
├── build.gradle
├── settings.gradle
├── src/main/java/...
├── src/main/resources/
│   ├── META-INF/
│   │   └── spring/
│   │       └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
│   ├── db/changelog/telegram-auth-changelog.xml
│   └── messages_tgauth*.properties
└── src/test/...
```

Dependencies (`compileOnly` for host provided):
- `spring-boot-starter-web`
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-websocket`
- `spring-boot-starter-data-redis` (optional)
- `org.liquibase:liquibase-core`
- `com.github.ben-manes.caffeine:caffeine`
- `org.postgresql:postgresql` (runtime, host-provided)

### 14.2 Auto-configuration

`META-INF/spring/.../AutoConfiguration.imports`:
```
uz.aloqabank.telegramauth.config.TelegramAuthAutoConfiguration
```

Activation:
```java
@AutoConfiguration
@ConditionalOnProperty(prefix = "telegram.auth", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TelegramAuthProperties.class)
public class TelegramAuthAutoConfiguration { ... }
```

### 14.3 Host integration steps

1. Add dependency: `implementation 'uz.aloqabank:telegram-auth-spring-boot-starter:0.1.0'`.
2. Set `telegram.auth.enabled=true` and `telegram.auth.bot.token=...`.
3. Include the Liquibase changelog:
   ```xml
   <include file="classpath:/db/changelog/telegram-auth-changelog.xml" />
   ```
4. Provide a `@Bean TelegramAuthApproveHandler` that returns the host's login payload.
5. Done.

### 14.4 Publishing

- Internal Nexus: `maven.aloqabank.local/repository/maven-releases/`.
- Version scheme: semver. `0.1.0` initial, `0.1.x` for bugfixes, `0.2.0` for new features, `1.0.0` after first production rollout.
- CI: Gradle `publish` task triggered on git tag `vX.Y.Z`.

---

## 15. Observability

### 15.1 Metrics (Micrometer)

- `tg_auth.session.created` (counter, tags: `result=ok|rate_limited`)
- `tg_auth.session.approved` (counter)
- `tg_auth.session.rejected` (counter)
- `tg_auth.session.expired` (counter)
- `tg_auth.wait.latency` (timer, tags: `transport=poll|sse|ws`)
- `tg_auth.bot.getupdates.latency` (timer)
- `tg_auth.bot.getupdates.errors` (counter, tags: `type=proxy|network|telegram|other`)

### 15.2 Logging levels

- `INFO`: session create, approve, reject, expired (aggregated).
- `DEBUG`: each bot update received, each state transition.
- `WARN`: rate limit hits, proxy failures, malformed bot updates.
- `ERROR`: bot client crash, Redis down, host callback exceptions.

---

## 16. Migration / rollout plan

1. **Phase 1 (MVP)**: long polling only, in-memory event bus, single instance, YML-only config. Integrate into one pilot host app.
2. **Phase 2**: add SSE, Redis event bus, rate limiting.
3. **Phase 3**: add WebSocket, runtime reconfiguration, admin endpoints.
4. **Phase 4**: publish to Nexus, document, onboard second host app.

---

## 17. Future work

- **MTProto proxy** support (requires a different bot library, significant scope).
- **Admin UI** for user management and session monitoring.
- **Push notification** integration (FCM/APNs) so mobile clients can drop the wait connection once backgrounded and still get the approved event.
- **Multi-bot** in one instance (routing by `@botname` in the deep-link).
- **Webhook mode** as an opt-in alternative to long polling (for public-facing deployments).
- **Two-factor composition**: combine with SMS/OTP as a second factor.
- **Telegram Login Widget** as an alternative entry point for desktop web.

---

## 18. Resolved decisions

All four open questions from the initial draft have been resolved. Decisions below, with links to where they are codified in the doc.

| # | Question | Decision | Where |
|---|---|---|---|
| 1 | Encrypt bot token and proxy password in DB? | **No.** Store plaintext. Bot token risk is limited, encryption with a same-app key is theater security, and the secrets already live plaintext in `application.yml`. Compensating controls: never logged, never returned by GET, access limited to app DB user. | §5.3, §11.5 |
| 2 | `external_user_id` column in `tg_auth_telegram_user`? | **Yes, but nullable and optional.** Host app may populate it via `RegisterContext.setExternalUserId(...)` in `TelegramAuthRegisterHandler`, or ignore it entirely and keep mapping outside the starter. Both modes supported. | §5.1, §10.2 |
| 3 | Horizontal-scaling test in CI or manual staging? | **Manual staging only.** Running two full instances + Postgres + Redis in CI is expensive for a rarely-regressing integration; a manual QA smoke test per release gives the same confidence. | §13.3 |
| 4 | GeoIP enrichment for the approve prompt? | **Raw IP only by default.** Optional free GeoIP (ip-api.com / ipinfo.io) behind `telegram.auth.geoip.enabled=false`. Lookup is non-blocking, 500ms hard cap, cached, **and failures never break the flow** — on any error the prompt falls back to IP-only. | §6, §9.2 |

---

## 19. Glossary

- **Session**: a single login attempt tied to a token, with a short TTL.
- **Token**: opaque 43-char Base64URL string, one per session.
- **Approve handler**: host-provided bean that decides what a successful login yields.
- **Event bus**: internal pub/sub that notifies a waiting client when its session's status changes.
- **Transport**: one of POLL / SSE / WS — the mechanism a client uses to wait.
- **Host app**: the Spring Boot application that depends on this starter.
