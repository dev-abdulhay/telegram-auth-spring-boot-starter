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
    <version>0.4.0</version>
</dependency>
```

**Gradle (Kotlin DSL):**

```kotlin
implementation("io.github.dev-abdulhay:telegram-auth-spring-boot-starter:0.4.0")
```

Then enable the starter:

```yaml
telegram:
  auth:
    enabled: true                  # master switch; auto-config is inert when false
    cleanup-cron: "0 */5 * * * *"  # optional — expired-session sweep schedule
```

> **Upgrading from 0.3.x?** `codeConfirmation` now defaults to `BUTTON`, so every
> login gains a number-matching step. See [Upgrading to 0.4.0](#upgrading-to-040).

## Build a module

Below is one complete `admin` module. (A runnable reference module lives under
`src/test/java/com/example/demo`.)

**1. Entities** — you own the `@Table`:

```java
@Entity @Table(name = "admin_tg_user")
public class AdminUser extends BaseTelegramUser {}

@Entity @Table(name = "admin_tg_session",
        indexes = @Index(name = "ix_admin_session_ip_status", columnList = "ip_address,status"))
public class AdminSession extends BaseAuthSession {}
```

> Index `ip_address` on the session table. Every `POST /session` runs a per-IP
> pending count for `maxPendingPerIp`, so without it the hottest endpoint in the
> library does a full table scan. (`token_hash` is already indexed by its unique
> constraint.)

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
            .sessionTtl(Duration.ofMinutes(5))
            .pollingTimeout(Duration.ofSeconds(30))
            .build();
    }

    @Bean
    AdminSessionService adminSessionService(AdminSessionRepository repo, TokenGenerator tg, TelegramBotModule module) {
        return new AdminSessionService(repo, tg, module);
    }

    @Bean
    DefaultAuthFlow<AdminUser, AdminSession> adminFlow(AdminUserService us, AdminSessionService ss, TelegramBotModule module) {
        return new DefaultAuthFlow<>(us, ss, module,    // registers /start into the module
            DefaultAuthFlow.Options.builder()
                .requireApproval(true)                       // inline ✅/❌ confirm (recommended)
                .requireContact(true)                        // phone via contact-share (soft, /skip allowed)
                .codeConfirmation(CodeConfirmation.BUTTON)   // the default — number matching
                .codeButtons(3)
                .build());
    }
}
```

Or let the operator tune it — the starter auto-configures a
`DefaultAuthFlow.Options` bean from `telegram.auth.flow.*`:

```java
@Bean
DefaultAuthFlow<AdminUser, AdminSession> adminFlow(AdminUserService us, AdminSessionService ss,
                                                   TelegramBotModule module,
                                                   DefaultAuthFlow.Options options) {
    return new DefaultAuthFlow<>(us, ss, module, options);
}
```

`requireContact` and `requireApproval` default to `false`; `codeConfirmation`
defaults to `BUTTON`. **Enable `requireApproval` in production** — without it,
anyone tricked into tapping a login link silently reaches the confirmation step
of the sender's browser session.

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
    approved_at       TIMESTAMPTZ,
    approve_payload   VARCHAR(4000)
);
```

> **No migration for 0.4.0.** The new `AWAITING_CODE` state is another value in
> the existing `status VARCHAR(20)` column, and the confirmation code is derived
> from `token_hash` rather than stored. If your DDL constrains `status` with a
> `CHECK` or an enum type, add `AWAITING_CODE` to it.

That's the whole module. The auto-config discovers every `TelegramBotModule`
bean and starts one independent long-poll loop per module.

## REST API (per module)

Paths are relative to the subclass's `@RequestMapping` prefix (e.g. `/api/admin-auth`):

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/session` | Create a login session → `{ token, botDeepLink, expiresAt, transports }`; `429` when the IP exceeds `maxPendingPerIp`. |
| `GET`  | `/session/{token}/poll[?since=]` | Long-poll for the next state the client has not seen; releases on approve / reject / expire (and on the code step, with `since=PENDING`). |
| `GET`  | `/session/{token}/status` | Cheap status check → `{ status, expiresAt }`. Never returns the confirmation code. |
| `DELETE` | `/session/{token}` | Client aborts a session that is still `PENDING` or `AWAITING_CODE`. |

`poll` responses: `200 { status:"APPROVED", payload:{…} }` on approval,
`202 { status:"AWAITING_CODE", confirmCode: 42 }` when the browser must show its
number, `204 No Content` on timeout (poll again), `403` on reject, `410 Gone` if
the token is unknown/expired.

> **The `since` parameter** names the state the client already knows, and it is
> what keeps a long-poll from returning instantly forever:
>
> | `since` | Behaviour |
> |---|---|
> | *(omitted)* | Pre-0.4.0 contract — terminal states only. A code transition arriving mid-poll answers `204`, so an old client simply polls again. |
> | `PENDING` | Opts into the code step: `202` plus `confirmCode`, either immediately or when the user taps ✅. |
> | `AWAITING_CODE` | Waits for a terminal state. Without this a client polling an already-`AWAITING_CODE` session would get `202` back instantly, forever. |
>
> A browser therefore polls with `since=PENDING`, displays the returned
> `confirmCode`, then polls again with `since=AWAITING_CODE`. Clients written
> against 0.3.x keep working unchanged.

> **Payload delivery contract.** The approval `payload` (whatever your
> `approveHandler` returns) is pushed to the live long-poll subscription **and**
> persisted on the session row (`approve_payload`, JSON), so a poll that arrives
> after approval still returns the payload. Events are published only after the
> DB transaction commits, and the poll endpoint subscribes before its final
> status check, so an approval can no longer fall between the cracks.

### Flow

```
client → POST {prefix}/session                        → { token, botDeepLink, expiresAt }
client → GET  {prefix}/session/{token}/poll?since=PENDING   (held open)
user   → opens t.me/<bot>?start=<token>
bot    → /start <token>            → DefaultAuthFlow.onStart
           ├─ requireContact    → contact-share keyboard (or /skip), then continue
           ├─ requireApproval   → inline ✅/❌  ......................... touch 1
           └─ codeConfirmation  → session becomes AWAITING_CODE
client ← 202 { status:"AWAITING_CODE", confirmCode: 42 }    (browser shows 42)
client → GET  {prefix}/session/{token}/poll?since=AWAITING_CODE  (held open)
user   → taps or types 42 in the bot  ........................... touch 2
bot    → register user + approve
host   → approveHandler(info, ctx)                    → AuthApproveResult(payload)
client ← 200 { status:"APPROVED", payload }           (poll released)
```

**What each combination does** — nothing is left implicit:

| `requireApproval` | `codeConfirmation` | Behaviour |
|---|---|---|
| `false` | `OFF` | `/start` registers and approves immediately (pre-0.4.0 default). |
| `true` | `OFF` | ✅ registers and approves (pre-0.4.0 `requireApproval`). |
| `false` | `BUTTON` / `TYPED` | **One touch:** `/start` moves the session to `AWAITING_CODE` and asks for the number, showing the session IP, device and warning. |
| `true` | `BUTTON` / `TYPED` | **Two touches:** ✅ (with IP, device and warning) unlocks the number question; the number finishes the login. |

`requireContact`, when on, always runs before the code step.

Bot texts are localized (`uz` default, `ru`, `en`) from the user's Telegram
`language_code`. `BLOCKED` users are always denied — re-login never lifts a
block. A shared contact is accepted only if it belongs to the sender
(`contact.user_id == from.id`), and the leading `+` is stripped from the phone.

> **Private chats only.** Every `DefaultAuthFlow` handler ignores updates whose
> `chat.id` differs from `from.id` — a deep link pasted into a group would
> otherwise register the *group id* as a user, and an inline ✅ posted there
> could be tapped by a bystander, approving someone else's browser session under
> their account. A `tgauth:` callback arriving from any other chat is refused
> with "access denied".

> **Registration happens at the last confirmation.** The user row is created (or
> refreshed) only when the final step succeeds — the correct number with
> `codeConfirmation` on, ✅ without it — so a login rejected or abandoned at any
> earlier point leaves no `ACTIVE` account behind. The inline `callback_data` is
> `tgauth:<action>:<rawToken>` and must fit Telegram's 64-byte limit
> (`tgauth:approve:` + 43 chars = 58 bytes; a number button is `tgauth:c42:` +
> 43 = 54). A custom `TokenGenerator` that overruns it fails fast with an
> `IllegalStateException` instead of silently producing a dead keyboard.

## Number matching (`codeConfirmation`)

An approval tap proves only that someone followed a link. It does **not** stop
device-code phishing: an attacker opens a session in their own browser, sends
you the link, you tap ✅, and they are in. Showing the session's IP and device
helps only if you read it.

The number does more. The browser displays a 2-digit code and the bot asks for
it, so finishing a login requires *looking at the screen that started it*.

```
codeConfirmation: BUTTON   → inline keyboard of `codeButtons` numbers (3–10, default 3)
codeConfirmation: TYPED    → user sends the number as text
codeConfirmation: OFF      → no code step
```

**A wrong answer is not a retry.** Telling the user to "try again" would let an
attacker tap every button in turn and reduce the whole scheme to nothing.
`BUTTON` therefore ends the login on the first miss (`maxCodeAttempts` 1);
`TYPED` allows three of a hundred candidates, because a single misread should
not cost a genuine user their session. Every wrong answer is logged at `WARN` —
it is a strong phishing signal.

**Rejecting the session is not enough on its own.** The attacker just opens
another one and sends a fresh link, so per-session odds compound over rounds.
Each dead login therefore also counts a *strike* against the Telegram user and
cools them down: `codeCooldown` (default 5 min) doubling on every further
failure up to `codeCooldownMax` (default 1 h), armed once
`codeCooldownThreshold` (default 1) failed logins have accumulated. A successful
login clears the ladder. All three attempts of one `TYPED` login count as **one**
strike, not three. ❌ is never blocked by a cooldown — saying "this was not me"
must always work.

**Where the code comes from — nowhere.** It is derived from the session's token
hash (`ConfirmCode`: first two bytes, modulo 100), so there is no column, no
migration, and both the bot and the REST layer recompute the same value. The
code is *not a secret*: anyone holding the deep link holds the raw token and can
compute it. Its value is that a victim who is not at the browser cannot know it
without being told, which turns a one-tap attack into a live, interactive one.

**Be honest about what this buys.** With the default profile (`BUTTON`,
`codeButtons` 3, cooldown 5 min doubling) an attacker who can talk a victim
through repeated rounds faces:

| Round | Wait | Cumulative success |
|---|---|---|
| 1 | — | 33% |
| 2 | 5 min | 56% |
| 3 | +10 min | 70% |
| 4 | +20 min | 80% |

The cooldown converts "70% in thirty seconds" into "70% across fifteen minutes,
three separate social-engineering rounds, and three `WARN` lines" — real, but it
does not make three buttons mathematically strong. `codeButtons(10)` gives
10 / 19 / 27 / 34%; `TYPED` gives 3 / 6 / 9%. Choose accordingly.

> **`TYPED` claims the text handler.** It registers `module.onText(…)`, which is
> single-slot. If your host already handles free text, either use `BUTTON` or
> register your handler through `fallback(…)` — the flow forwards every text it
> does not own (no login in progress, an unregistered `/command`, anything
> non-numeric while no code is pending) straight to your fallback.

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

`DefaultAuthFlow` registers `/start` (plus `/skip`, contact and callback
handlers when the corresponding `Options` flags are on). To change it:

- **Flip the flags** — `DefaultAuthFlow.Options.builder().requireContact(…).requireApproval(…).codeConfirmation(…)`, or bind them from `telegram.auth.flow.*`.
- **Change the wording** — subclass and `@Override protected String msg(FlowMessages.Key key, String lang)`.
- **Override a step** — the `onStart` / `onContact` / `onSkip` / `onCallback` / `onText` methods are public and overridable.
- **Change the code scheme** — `TelegramBotModule.builder(token, name).confirmCodeGenerator(hash -> …)`. It must be a *pure function of the token hash*: the bot and the controller derive the code independently and never exchange it, so anything random or stateful makes them disagree and every login fails.
- **Change the candidate numbers** — subclass and `@Override protected List<Integer> codeChoices(int realCode, int count)` (must contain `realCode` exactly once, no duplicates, size `count`) or `@Override protected String formatCode(int code)` (default `%02d`).
- **Override an endpoint** — `@Override` any handler on your `AbstractTelegramAuthController` subclass.
- **Replace the `/start` handler** — register your own `Consumer<JsonNode>` on the module: `module.command("/start", myHandler)`.
- **Change the confirmation text** — subclass and `@Override protected String confirmPrompt(S session, String lang)` (the default appends the session IP + device).
- **Handle other updates** — `module.onCallbackQuery(handler)` for inline buttons, `module.onContact(handler)` for shared contacts, `module.onText(handler)` for plain text, `module.fallback(handler)` for everything else. (`DefaultAuthFlow` claims each only when the corresponding option is on, and it **forwards what it does not own to your `fallback`** — `callback_data` outside the `tgauth:` namespace, contacts arriving with no login in progress, and any text that is not a pending confirmation code — so your own inline keyboards and text handling keep working.)

Update routing order is: `callback_query` → command registry → `contact` →
`text` → `fallback`. An **unregistered `/command` reaches the text handler**,
not the fallback — once the registry misses there is nothing left to
distinguish it from ordinary text. `DefaultAuthFlow` forwards those to your
fallback and never counts them as a code attempt.

> `onCallbackQuery`, `onContact` and `onText` are single-slot and **throw
> `IllegalStateException` if a second handler is registered**, rather than
> silently replacing the first. Otherwise a host registering its own callback
> handler alongside `requireApproval(true)` would disable login approval with no
> error anywhere. Route your own updates through `fallback(…)` — the flow already
> forwards everything it does not own there.
- **Decide the login result** — that's the module's `approveHandler` returning an `AuthApproveResult`.

Command handlers receive the raw Telegram `update` as a Jackson `JsonNode` (the
signature may evolve in a later release).

## Sending bot messages

Each module owns a `TelegramBot` instance (`module.getBot()`), so any service
can push messages:

```java
module.getBot().sendMessage(chatId, "Welcome!");
module.getBot().sendMessage(chatId, "Pick one:", replyMarkupJson); // inline/reply keyboard
module.getBot().answerCallbackQuery(callbackQueryId, "Done");      // button-press toast
module.getBot().editMessageText(chatId, messageId, "Updated");     // also drops the inline keyboard
```

## Configuration reference

Global properties (everything else is code, on `TelegramBotModule`):

| Property | Default | Purpose |
|----------|---------|---------|
| `telegram.auth.enabled` | `false` | Master switch; auto-config stays inert when false. |
| `telegram.auth.cleanup-cron` | `0 */5 * * * *` | Spring cron for the expired-session sweep (each module sweeps its own table). |

Flow behaviour is bindable too, because it is what operators re-tune per
environment. The starter auto-configures a `DefaultAuthFlow.Options` bean from
`telegram.auth.flow`; declaring your own `Options` bean replaces it.

```yaml
telegram:
  auth:
    flow:                          # defaults for every DefaultAuthFlow
      require-contact: false
      require-approval: true
      code-confirmation: BUTTON    # BUTTON | TYPED | OFF
      code-buttons: 3              # 3..10, BUTTON only
      max-code-attempts: 0         # 0 = per-mode default (BUTTON 1, TYPED 3)
      code-cooldown: 5m            # ZERO disables cooldowns
      code-cooldown-max: 1h
      code-cooldown-threshold: 1
    flows:                         # optional per-user-type overrides
      admin:
        code-confirmation: TYPED
```

A field left unset under `flows.<name>` falls back to `flow`, and then to the
built-in default — a host with several user types states only what differs:

```java
DefaultAuthFlow.Options adminOptions =
        properties.getFlows().get("admin").toOptions(properties.getFlow());
```

Values are validated by the same `Options.Builder` a hand-written config uses,
so `code-buttons: 11` fails the application context at startup with an
explanatory message rather than misbehaving later. Code always wins: a builder
passed explicitly to `new DefaultAuthFlow<>(…)` ignores these properties.

Per-module settings live on the builder, not in YAML: `sessionTtl` (default
5 min), `pollingTimeout` (30 s), `pollingInterval` (1 s), `approveHandler`,
`confirmCodeGenerator` (default `ConfirmCode`),
`sessionRetention` (1 day — terminal sessions older than this are deleted by the
sweeper; `Duration.ZERO` disables), `maxPendingPerIp` (50 — `POST /session`
returns `429` beyond it; `0` disables), `trustProxyHeaders` (`false` — enable
only behind a trusted proxy so `X-Forwarded-For` is honoured for the client IP),
`trustedProxyHops` (1 — how many trusted proxies sit in front),
plus optional `bot(…)` / `eventBus(…)` overrides.

> **Running behind a reverse proxy?** Enable `trustProxyHeaders(true)` and set
> `trustedProxyHops` to the number of proxies between your users and the app —
> `1` for a single nginx, `2` for CDN + nginx. Without `trustProxyHeaders` every
> request carries the proxy's own address, so all of your users share a single
> `maxPendingPerIp` bucket and concurrent logins across the site start getting
> `429`s.
>
> The client IP is read that many entries from the **right** of
> `X-Forwarded-For`, because each trusted hop appends the peer it received from
> and everything further left came from the client and can be forged. Getting the
> count wrong is not harmless in either direction: too low reads a forged entry,
> too high reads your own proxy's address and collapses every user into one
> rate-limit bucket again. A header with fewer entries than the configured hop
> count is ignored in favour of the socket address. Never enable any of this when
> the app is reachable directly.

> **`maxPendingPerIp` is best-effort.** The pending count and the insert are two
> statements, not one atomic operation, so a burst of genuinely simultaneous
> requests from one IP can land a few rows over the limit. It brakes floods; put
> a real rate limiter (gateway, WAF, bucket filter) in front if you need an exact
> ceiling.

> **In-flight login state is JVM-local.** The phone collected at the contact
> step and the confirmation-code attempt counter live in an in-memory map, as do
> the per-user cooldown strikes. A restart — or a second instance taking over
> polling — forgets them. This is degraded, not broken: ✅, ❌ and the number
> buttons still work, because the token travels in the `callback_data` and the
> session lives in the DB. What is lost is a phone shared moments earlier (the
> previously stored one is kept), a pending `/skip` or contact-share, a typed
> code with nothing to correlate against, and any outstanding cooldown. The
> confirmation step **widens this window**: an entry must now survive from the
> contact step all the way to the final number, rather than to ✅. Override the
> `on*` methods with a shared store if a mid-flow handover has to survive intact.

## Upgrading to 0.4.0

Breaking, in rough order of how likely it is to affect you:

1. **`codeConfirmation` defaults to `BUTTON`.** `Options.defaults()` and the
   3-argument `DefaultAuthFlow` constructor now add a number-matching step to
   every login. To keep 0.3.x behaviour exactly:
   ```java
   DefaultAuthFlow.Options.builder().codeConfirmation(CodeConfirmation.OFF).build()
   ```
   or `telegram.auth.flow.code-confirmation: OFF`.
2. **`WaitResponse` gained a third component**, `Integer confirmCode`. The
   2-argument constructor still exists, and the field is omitted from the JSON
   when null, so existing clients and `approveHandler`s are unaffected.
3. **Two repository methods were widened to status sets** —
   `findByStatusAndExpiresAtBefore` → `findByStatusInAndExpiresAtBefore`, and
   `countByIpAddressAndStatusAndExpiresAtAfter` →
   `countByIpAddressAndStatusInAndExpiresAtAfter`. Only affects code that called
   them directly or hand-implemented `BaseAuthSessionRepository`.
4. **`AuthEvent.Type` gained `AWAITING_CODE`.** An exhaustive `switch` over it
   needs a new branch. It is the first non-terminal event: after it fires the
   subscription is closed as usual and the client re-subscribes on its next poll.
5. **`sessionTtl` defaults to 5 minutes** (was 3) — the extra confirmation step
   needs the room.
6. **`Status` gained `AWAITING_CODE`.** No DDL change unless your schema
   constrains the column with a `CHECK` or an enum type.

## Status & roadmap

> **MVP.** Long-polling transport, in-memory per-module event bus, single
> instance.

- [x] Multi-instance abstract toolkit (N user types, N bots).
- [x] `DefaultAuthFlow` with self-registering `/start`.
- [x] Contact-share + Approve/Reject inline keyboard (opt-in `Options`), 3-language bot texts.
- [x] Number matching (`codeConfirmation`) with per-user cooldown, and flow options bindable from YAML.
- [ ] SSE & WebSocket transports.
- [ ] Redis-backed event bus + multi-instance horizontal scaling (would also make in-flight login state survive failover).

## Publishing

Published to Maven Central via the [Sonatype Central
Portal](https://central.sonatype.com). See [`PUBLISHING.md`](PUBLISHING.md) for
prerequisites and the release procedure.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
