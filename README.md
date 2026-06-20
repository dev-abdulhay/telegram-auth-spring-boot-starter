# telegram-auth-spring-boot-starter

Spring Boot starter that adds **Telegram-bot based authentication** to any backend
service. A web/mobile client kicks off a login session, the user confirms in a
Telegram bot, and the backend returns a project-defined login payload (JWT,
session cookie — whatever the host app needs).

[![Maven Central](https://img.shields.io/maven-central/v/io.github.dev-abdulhay/telegram-auth-spring-boot-starter.svg)](https://central.sonatype.com/artifact/io.github.dev-abdulhay/telegram-auth-spring-boot-starter)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

> **Status: v0.2.0 — abstract multi-instance toolkit.** The starter ships only
> generic base classes. You write the entities, repositories, services, and
> controllers; the starter wires the bot lifecycle and event bus.

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
dependencies {
    implementation("io.github.dev-abdulhay:telegram-auth-spring-boot-starter:0.2.0")
}
```

## Quickstart (v0.2.0)

The starter ships only abstract base classes. For each user type you write 6
subclasses + 1 `@Configuration`. The starter creates **no** tables and registers
**no** controllers — you own those.

1. Enable the starter:

   ```yaml
   telegram:
     auth:
       enabled: true
   admin:
     bot:
       token: ${ADMIN_BOT_TOKEN}
   ```

2. Create the tables yourself (Liquibase/Flyway/DDL). Example for the admin type:

   ```sql
   create table admin_tg_user (
     id bigserial primary key,
     telegram_id bigint not null unique,
     phone varchar(20), first_name varchar(100), last_name varchar(100),
     username varchar(50), language_code varchar(5),
     status varchar(30) not null, external_user_id varchar(100),
     created_at timestamptz not null, updated_at timestamptz not null
   );
   create table admin_tg_session (
     id bigserial primary key,
     token_hash varchar(64) not null unique, telegram_user_id bigint,
     status varchar(20) not null, ip_address varchar(45), user_agent varchar(500),
     created_at timestamptz not null, expires_at timestamptz not null, approved_at timestamptz
   );
   ```

3. Write the module:

   ```java
   @Entity @Table(name = "admin_tg_user")
   public class AdminUser extends BaseTelegramUser {}

   @Entity @Table(name = "admin_tg_session")
   public class AdminSession extends BaseAuthSession {}

   public interface AdminUserRepository extends BaseTelegramUserRepository<AdminUser> {}
   public interface AdminSessionRepository extends BaseAuthSessionRepository<AdminSession> {}

   @Service
   public class AdminUserService extends AbstractTelegramUserService<AdminUser> {
       public AdminUserService(AdminUserRepository repo) { super(repo, AdminUser::new); }
   }

   @Service
   public class AdminSessionService extends AbstractSessionService<AdminUser, AdminSession> {
       public AdminSessionService(AdminSessionRepository repo, TokenGenerator tg, TelegramBotModule m) {
           super(repo, AdminSession::new, tg, m);
       }
   }

   @RestController
   @RequestMapping("/api/admin-auth")
   public class AdminAuthController extends AbstractTelegramAuthController<AdminUser, AdminSession> {
       public AdminAuthController(AdminSessionService s, TelegramBotModule m) { super(s, m); }
   }

   @Configuration
   public class AdminTgConfig {
       @Bean
       TelegramBotModule adminModule(@Value("${admin.bot.token}") String token, JwtService jwt) {
           return TelegramBotModule.builder(token, "admin_bot")
               .approveHandler((info, ctx) -> new AuthApproveResult(Map.of(
                   "accessToken", jwt.issue(info.telegramId()))))
               .build();
       }
       @Bean AdminUserService adminUserService(AdminUserRepository r) { return new AdminUserService(r); }
       @Bean AdminSessionService adminSessionService(AdminSessionRepository r, TokenGenerator tg, TelegramBotModule m) {
           return new AdminSessionService(r, tg, m);
       }
       // Declaring the default flow bean wires /start automatically.
       @Bean DefaultAuthFlow<AdminUser, AdminSession> adminFlow(
               AdminUserService us, AdminSessionService ss, TelegramBotModule m) {
           return new DefaultAuthFlow<>(us, ss, m);
       }
   }
   ```

For a second user type (e.g. `customer`), repeat with its own `@RequestMapping`
prefix, `@Table` names, bot token, and `TelegramBotModule` bean.

## REST API (per module, relative to the subclass `@RequestMapping`)

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/session` | Create a login session; returns token + `t.me/<bot>?start=…`. |
| `GET`  | `/session/{token}/poll` | Long-poll for terminal status. |
| `GET`  | `/session/{token}/status` | Cheap status check. |
| `DELETE` | `/session/{token}` | Abort a pending session. |

> **Payload delivery.** The approval payload (whatever your `TelegramAuthApproveHandler` returns) is delivered **once**, on the long-poll connection that is open when the approval happens. It is not persisted server-side. If a client's `/poll` connection drops and it re-polls an already-`APPROVED` session, the response is `{ "status": "APPROVED" }` with an empty payload. Clients should treat an interrupted poll as needing a fresh login session, or keep the long-poll open until it returns.

## Configuration reference

```yaml
telegram:
  auth:
    enabled: true                   # master switch
    session:
      ttl: 180s
      cleanup-cron: "0 */5 * * * *"
```

## Roadmap

- [x] **Phase 1 (MVP):** Long polling + in-memory bus + single bot.
- [x] **Phase 2 (v0.2.0):** Abstract multi-instance toolkit — N independent bots,
      host-owned entities/controllers.
- [ ] **Phase 3:** SSE & WebSocket transports.
- [ ] **Phase 4:** Redis-backed event bus, rate limiter.
- [ ] **Phase 5:** Runtime bot/proxy reconfiguration, admin endpoints, GeoIP.

## Publishing

This artifact is published to Maven Central via the [Sonatype Central
Portal](https://central.sonatype.com). See [`PUBLISHING.md`](PUBLISHING.md) for the
manual prerequisites (Sonatype account, GPG key, GitHub secrets) and the
release procedure.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
