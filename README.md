# telegram-auth-spring-boot-starter

Spring Boot starter that adds **Telegram-bot based authentication** to any backend
service. A web/mobile client kicks off a login session, the user confirms in a
Telegram bot, and the backend returns a project-defined login payload (JWT,
session cookie — whatever the host app needs).

[![Maven Central](https://img.shields.io/maven-central/v/io.github.abdulhaybro/telegram-auth-spring-boot-starter.svg)](https://central.sonatype.com/artifact/io.github.abdulhaybro/telegram-auth-spring-boot-starter)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

> **Status: MVP (Phase 1).** Long-polling transport, in-memory event bus, single
> instance. SSE / WebSocket / Redis-backed bus / runtime reconfiguration are on
> the roadmap (see `tasks/tech-doc/TECH_DOC.md`).

## Install

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    implementation("io.github.abdulhaybro:telegram-auth-spring-boot-starter:0.1.0")
}
```

**Maven:**

```xml
<dependency>
    <groupId>io.github.abdulhaybro</groupId>
    <artifactId>telegram-auth-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Quickstart

1. Add the dependency (above).
2. Enable the starter and set your bot token:

   ```yaml
   telegram:
     auth:
       enabled: true
       bot:
         token: ${TG_BOT_TOKEN}
         username: mybot          # without the @
   ```

3. Include the Liquibase changelog in your master file:

   ```xml
   <include file="classpath:/db/changelog/telegram-auth-changelog.xml"/>
   ```

4. Provide one `TelegramAuthApproveHandler` bean — the host decides what
   "login success" returns:

   ```java
   @Bean
   TelegramAuthApproveHandler approveHandler(JwtService jwt) {
       return (user, ctx) -> new AuthApproveResult(Map.of(
           "accessToken", jwt.issue(user.telegramId()),
           "user", Map.of("id", user.telegramId(), "phone", user.phone())
       ));
   }
   ```

That's it. The starter exposes the REST API below.

## REST API

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/tg-auth/session` | Create a login session; returns token + `t.me/<bot>?start=…` deep-link. |
| `GET`  | `/api/tg-auth/session/{token}/poll` | Long-poll for terminal status; releases on approve / reject / expire. |
| `GET`  | `/api/tg-auth/session/{token}/status` | Cheap status check (no payload, for diagnostics). |
| `DELETE` | `/api/tg-auth/session/{token}` | Client aborts a pending session. |

### Flow

```
client → POST /session                       → { token, botDeepLink }
client → GET /session/{token}/poll           (held open)
user   → opens t.me/<bot>?start=<token>
bot    → /start <token>                      (server side)
host   → onApprove(user, ctx)                → AuthApproveResult
client ← 200 { status: APPROVED, payload }   (poll released)
```

## Configuration reference

```yaml
telegram:
  auth:
    enabled: true                   # master switch
    base-path: /api/tg-auth         # REST base path
    bot:
      token: ${TG_BOT_TOKEN:}
      username: mybot
      polling-interval: 1s
      polling-timeout: 30s
    session:
      ttl: 180s
      cleanup-cron: "0 */5 * * * *"
    transport:
      polling:
        enabled: true
        max-wait: 30s
    db:
      schema: public
      table-prefix: tg_auth_
    i18n:
      default-language: uz
      supported: [uz, ru, en]
    rate-limit:
      enabled: true
      ip-per-minute: 5
      ip-per-hour: 30
```

## Extension points

| Bean | Required | Purpose |
|------|----------|---------|
| `TelegramAuthApproveHandler` | **yes** | What "login success" returns to the client. |
| `TelegramAuthRegisterHandler` | optional | Hook on first-ever registration; can populate `external_user_id`. |

## Roadmap

- [x] **Phase 1 (MVP):** Long polling + in-memory bus + Liquibase schema.
- [ ] **Phase 2:** Contact-share + name-confirm bot UX, Approve/Reject inline keyboard.
- [ ] **Phase 3:** SSE & WebSocket transports.
- [ ] **Phase 4:** Redis-backed event bus, rate limiter, multi-instance.
- [ ] **Phase 5:** Runtime bot/proxy reconfiguration, admin endpoints, GeoIP.

See [`tasks/tech-doc/TECH_DOC.md`](tasks/tech-doc/TECH_DOC.md) for the full design.

## Publishing

This artifact is published to Maven Central via the [Sonatype Central
Portal](https://central.sonatype.com). See [`PUBLISHING.md`](PUBLISHING.md) for the
manual prerequisites (Sonatype account, GPG key, GitHub secrets) and the
release procedure.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
