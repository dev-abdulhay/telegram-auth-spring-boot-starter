# telegram-auth-spring-boot-starter

A Spring Boot starter for **Telegram-bot based registration & authentication** —
designed so a single dependency can power **N independent user types** (admins,
customers, drivers …), each with its own bot, its own tables, and its own REST
prefix.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.dev-abdulhay/telegram-auth-spring-boot-starter.svg)](https://central.sonatype.com/artifact/io.github.dev-abdulhay/telegram-auth-spring-boot-starter)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

The flow: a web/mobile client opens a login session, the user confirms in your
Telegram bot, and the backend returns a project-defined payload (JWT, session
cookie — whatever you decide).

## Mental model

The starter ships **only generic, abstract building blocks** — it creates **no
tables, no entities, no controllers, no bots** on its own. You define one
*module* per user type by subclassing six classes and wiring one
`@Configuration`. Subclass them correctly and a working register/auth flow comes
for free; override a method to change the behaviour.

| You write (per user type) | extends / role |
|---|---|
| `XUser` `@Entity @Table` | `BaseTelegramUser` |
| `XSession` `@Entity @Table` | `BaseAuthSession` |
| `XUserRepository` | `BaseTelegramUserRepository<XUser>` |
| `XSessionRepository` | `BaseAuthSessionRepository<XSession>` |
| `XUserService` | `AbstractTelegramUserService<XUser>` |
| `XSessionService` | `AbstractSessionService<XUser, XSession>` |
| `XAuthController` `@RestController @RequestMapping("/api/x-auth")` | `AbstractTelegramAuthController<XUser, XSession>` |
| `XTgConfig` `@Configuration` | provides the `TelegramBotModule` + `DefaultAuthFlow` beans |

> **Where the prefixes live.** The REST routing prefix is the subclass's
> `@RequestMapping`. The table name is the subclass's `@Table(name = …)`. Neither
> lives in config — so every module is fully isolated.

## Install

**Maven:**

```xml
<dependency>
    <groupId>io.github.dev-abdulhay</groupId>
    <artifactId>telegram-auth-spring-boot-starter</artifactId>
    <version>0.2.0</version>
</dependency>
```

**Gradle (Kotlin DSL):**

```kotlin
implementation("io.github.dev-abdulhay:telegram-auth-spring-boot-starter:0.2.0")
```

Then enable the starter:

```yaml
telegram:
  auth:
    enabled: true                  # master switch; auto-config is inert when false
    cleanup-cron: "0 */5 * * * *"  # optional — expired-session sweep schedule
```

## Build a module

Below is one complete `admin` module. (A runnable reference module lives under
`src/test/java/com/example/demo`.)

**1. Entities** — you own the `@Table`:

```java
@Entity @Table(name = "admin_tg_user")
public class AdminUser extends BaseTelegramUser {}

@Entity @Table(name = "admin_tg_session")
public class AdminSession extends BaseAuthSession {}
```

**2. Repositories** — Spring Data generates the implementations:

```java
public interface AdminUserRepository extends BaseTelegramUserRepository<AdminUser> {}
public interface AdminSessionRepository extends BaseAuthSessionRepository<AdminSession> {}
```

**3. Services** — generics can't `new U()`, so you pass a `Supplier` factory via
`super(...)`:

```java
@Service
public class AdminUserService extends AbstractTelegramUserService<AdminUser> {
    public AdminUserService(AdminUserRepository repo) {
        super(repo, AdminUser::new);
    }
}

public class AdminSessionService extends AbstractSessionService<AdminUser, AdminSession> {
    public AdminSessionService(AdminSessionRepository repo, TokenGenerator tg, TelegramBotModule module) {
        super(repo, AdminSession::new, tg, module);
    }
}
```

**4. Controller** — the subclass supplies `@RestController` + the prefix; Spring
picks up the inherited endpoint mappings:

```java
@RestController
@RequestMapping("/api/admin-auth")
public class AdminAuthController extends AbstractTelegramAuthController<AdminUser, AdminSession> {
    public AdminAuthController(AdminSessionService service, TelegramBotModule module) {
        super(service, module);
    }
    // four endpoints work out of the box; @Override any to change behaviour
}
```

**5. Configuration** — build the `TelegramBotModule` (bot token, username,
approve handler) and the `DefaultAuthFlow` (which self-registers a working
`/start` handler into the module):

```java
@Configuration
public class AdminTgConfig {

    @Bean
    TelegramBotModule adminModule(@Value("${admin.bot.token}") String token, JwtService jwt) {
        return TelegramBotModule.builder(token, "admin_bot")   // (token, username without @)
            .approveHandler((info, ctx) -> new AuthApproveResult(Map.of(
                "accessToken", jwt.issue(info.telegramId()),
                "user", Map.of("id", info.telegramId(), "phone", info.phone()))))
            .sessionTtl(Duration.ofMinutes(3))
            .pollingTimeout(Duration.ofSeconds(30))
            .build();
    }

    @Bean
    AdminSessionService adminSessionService(AdminSessionRepository repo, TokenGenerator tg, TelegramBotModule module) {
        return new AdminSessionService(repo, tg, module);
    }

    @Bean
    DefaultAuthFlow<AdminUser, AdminSession> adminFlow(AdminUserService us, AdminSessionService ss, TelegramBotModule module) {
        return new DefaultAuthFlow<>(us, ss, module);   // registers /start into the module
    }
}
```

**6. Schema** — you own the DDL (the starter ships no changelog). Minimum
columns the base entities expect:

```sql
CREATE TABLE admin_tg_user (
    id               BIGSERIAL PRIMARY KEY,
    telegram_id      BIGINT       NOT NULL UNIQUE,
    phone            VARCHAR(20),
    first_name       VARCHAR(100),
    last_name        VARCHAR(100),
    username         VARCHAR(50),
    language_code    VARCHAR(5),
    status           VARCHAR(30)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);

CREATE TABLE admin_tg_session (
    id                BIGSERIAL PRIMARY KEY,
    token_hash        VARCHAR(64)  NOT NULL UNIQUE,
    telegram_user_id  BIGINT,
    status            VARCHAR(20)  NOT NULL,
    ip_address        VARCHAR(45),
    user_agent        VARCHAR(500),
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    expires_at        TIMESTAMPTZ  NOT NULL,
    approved_at       TIMESTAMPTZ
);
```

That's the whole module. The auto-config discovers every `TelegramBotModule`
bean and starts one independent long-poll loop per module.

## REST API (per module)

Paths are relative to the subclass's `@RequestMapping` prefix (e.g. `/api/admin-auth`):

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/session` | Create a login session → `{ token, botDeepLink, expiresAt, transports }`. |
| `GET`  | `/session/{token}/poll` | Long-poll for the terminal result; releases on approve / reject / expire. |
| `GET`  | `/session/{token}/status` | Cheap status check → `{ status, expiresAt }`. |
| `DELETE` | `/session/{token}` | Client aborts a pending session. |

`poll` responses: `200 { status:"APPROVED", payload:{…} }` on approval,
`204 No Content` on timeout (poll again), `403` on reject, `410 Gone` if the
token is unknown/expired.

> **Payload delivery contract.** The approval `payload` (whatever your
> `approveHandler` returns) is pushed to the long-poll connection that is **open
> at the moment of approval**; it is not persisted. Polling an already-`APPROVED`
> session returns `APPROVED` with an empty payload. Keep a poll open until it
> returns, or treat an interrupted poll as needing a fresh login session.

### Flow

```
client → POST {prefix}/session                  → { token, botDeepLink, expiresAt }
client → GET  {prefix}/session/{token}/poll      (held open)
user   → opens t.me/<bot>?start=<token>
bot    → /start <token>          → DefaultAuthFlow.onStart → register user + approve
host   → approveHandler(info, ctx)               → AuthApproveResult(payload)
client ← 200 { status:"APPROVED", payload }      (poll released)
```

## Multiple user types

Repeat the module for each type — different bot token, different tables,
different prefix:

```
/api/admin-auth     → admin_bot     → admin_tg_user / admin_tg_session
/api/customer-auth  → customer_bot  → customer_tg_user / customer_tg_session
/api/driver-auth    → driver_bot    → driver_tg_user / driver_tg_session
```

Each `@Configuration` wires **its own** `TelegramBotModule` explicitly into its
session service, controller, and flow, so there is no bean ambiguity across
types. All modules share the single auto-configured `TokenGenerator`.

## Customising the flow

`DefaultAuthFlow` is the MVP behaviour: `/start <token>` auto-registers the user
from the Telegram message metadata and approves the session. To change it:

- **Override an endpoint** — `@Override` any handler on your `AbstractTelegramAuthController` subclass.
- **Replace the `/start` handler** — register your own `Consumer<JsonNode>` on the module: `module.command("/start", myHandler)`.
- **Handle other updates** — register a `module.fallback(handler)` for anything that isn't a known `/command` (contact share, callback queries, free text).
- **Decide the login result** — that's the module's `approveHandler` returning an `AuthApproveResult`.

Command handlers receive the raw Telegram `update` as a Jackson `JsonNode` (the
signature may evolve in a later release).

## Sending bot messages

Each module owns a `TelegramBot` instance (`module.getBot()`), so any service
can push messages:

```java
module.getBot().sendMessage(chatId, "Welcome!");
```

## Configuration reference

Global properties (everything else is code, on `TelegramBotModule`):

| Property | Default | Purpose |
|----------|---------|---------|
| `telegram.auth.enabled` | `false` | Master switch; auto-config stays inert when false. |
| `telegram.auth.cleanup-cron` | `0 */5 * * * *` | Spring cron for the expired-session sweep (each module sweeps its own table). |

Per-module settings live on the builder, not in YAML: `sessionTtl` (default
3 min), `pollingTimeout` (30 s), `pollingInterval` (1 s), `approveHandler`, plus
optional `bot(…)` / `eventBus(…)` overrides.

## Status & roadmap

> **MVP.** Long-polling transport, in-memory per-module event bus, single
> instance.

- [x] Multi-instance abstract toolkit (N user types, N bots).
- [x] `DefaultAuthFlow` with self-registering `/start`.
- [ ] Contact-share + name-confirm bot UX, Approve/Reject inline keyboard.
- [ ] SSE & WebSocket transports.
- [ ] Redis-backed event bus + multi-instance horizontal scaling.

## Publishing

Published to Maven Central via the [Sonatype Central
Portal](https://central.sonatype.com). See [`PUBLISHING.md`](PUBLISHING.md) for
prerequisites and the release procedure.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
