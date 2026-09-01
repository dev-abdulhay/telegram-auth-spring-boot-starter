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
    bot_user_id       BIGINT,       -- NULL unless the module carries a bot id
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

> **`bot_user_id` is additive and nullable.** Existing tables need
> `ALTER TABLE … ADD COLUMN bot_user_id BIGINT` and nothing else — no backfill.
> It stays `NULL` for every session created by a statically configured module,
> which keeps the table-wide rate-limit behaviour unchanged. Only [white-label
> tenant bots](#white-label-tenant-bots) populate it, and those hosts should
> index `ip_address,bot_user_id,status` instead of `ip_address,status`.

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

## Managed bots

A **manager bot** can create other bots on a user's behalf and keep custody of
their tokens, using the Telegram Bot API's managed-bots methods (a `/newbot`
deep link, `getManagedBotToken`, `replaceManagedBotToken`,
`getManagedBotAccessSettings`, `setManagedBotAccessSettings`). It is a
**separate, opt-in feature** (`telegram.managed-bots.*`, its own namespace,
its own auto-configuration) that is fully independent of the auth flow
documented above — a host can enable either alone, both together, or neither.

> **This narrows the update stream once enabled.** As soon as a
> `managed_bot` handler is registered on a `TelegramBotModule`, that module's
> poller starts sending Telegram an explicit `allowed_updates` list —
> `["message", "callback_query", "managed_bot"]` — because Telegram's own
> default list excludes `managed_bot`. If your host relied on the default list
> to observe other update types through `module.fallback(...)` (for example
> `my_chat_member`, to detect that a user blocked the bot), you will stop
> receiving them on that module once managed bots are enabled on it. The
> library's own auth flow is unaffected — `DefaultAuthFlow` only ever consumes
> `message` and `callback_query`. Read this before flipping
> `telegram.managed-bots.enabled` to `true`.

### Prerequisites

The library cannot turn either of these on — an operator does it in Telegram,
before any code here runs:

- **Bot Management Mode** must be enabled for the manager bot in the
  [BotFather Mini App](https://t.me/BotFather).
- The Telegram user creating a bot through the manager needs the
  `can_manage_bots` right.

### Configuration

| Property | Default | Purpose |
|----------|---------|---------|
| `telegram.managed-bots.enabled` | `false` | Opt-in switch for the whole feature; auto-config stays inert when false. |
| `telegram.managed-bots.encryption-key` | *(required)* | Base64-encoded 32-byte AES key used to encrypt tokens at rest. Required when the feature is on, unless you supply your own `TokenEncryptor` bean. |
| `telegram.managed-bots.token-fetch-retries` | `3` | Attempts for `getManagedBotToken` before giving up on a `managed_bot` update. |
| `telegram.managed-bots.token-fetch-backoff` | `1s` | Delay before the first retry, doubling on each further attempt. |

```yaml
telegram:
  managed-bots:
    enabled: true
    encryption-key: "BASE64_ENCODED_32_BYTE_KEY"
    token-fetch-retries: 3
    token-fetch-backoff: 1s
```

### Minimal usage

The auto-configuration wires `ManagedBotService` and the update handler, but
it does **not** register a `ManagedBotTokenStore` bean — only the host knows
whether that store is JPA-backed (and with which entity) or in-memory, so you
declare exactly one yourself.

**Option A — JPA**, subclassing `BaseManagedBot` and
`BaseManagedBotRepository` the same way you subclass `BaseAuthSession`:

```java
@Entity
@Table(name = "managed_bot", indexes = @Index(columnList = "owner_user_id"))
public class TenantBot extends BaseManagedBot {}

public interface TenantBotRepository extends BaseManagedBotRepository<TenantBot> {}

@Bean
ManagedBotTokenStore managedBotTokenStore(TenantBotRepository repo) {
    return new JpaManagedBotTokenStore<>(repo, TenantBot::new);
}
```

**Option B — in-memory** (tests, or hosts that do not need durability):

```java
@Bean
ManagedBotTokenStore managedBotTokenStore() {
    return new InMemoryManagedBotStore();
}
```

Then turn the feature on and create a link:

```java
String link = managedBotService.createLink("mycompany_sales_bot", "My Company Sales");
// send `link` to the user; they tap it, confirm/edit the details in Telegram,
// and the new bot's `managed_bot` update arrives and is handled automatically
```

The suggested username is validated locally and `createLink` throws
`IllegalArgumentException` when it is shorter than 5 or longer than 32
characters, contains anything outside `A-Z a-z 0-9 _`, or does not end in
`bot` (case-insensitively) — the three rules Telegram could never accept. It
is only a *suggestion*: the user may edit it in Telegram's confirmation
dialog, and the Bot API offers no way to check whether a username is free, so
a valid suggestion can still be taken. Passing `null` or a blank username
builds a link with no suggestion at all.

React to a new bot by implementing `ManagedBotEvents#onCreated` (every method
is a no-op default, so implement only what you need):

```java
@Bean
ManagedBotEvents managedBotEvents(ManagedBotService managedBotService) {
    return new ManagedBotEvents() {
        @Override
        public void onCreated(ManagedBot bot) {
            String token = managedBotService.findToken(bot.botUserId()).orElseThrow();
            // e.g. start a runtime bot instance for bot.botUserId() with this token
        }
    };
}
```

### Deleting a managed bot

The Bot API exposes **no method to delete a managed bot** — this is a live
Telegram platform limitation, not a gap in this library. `decommission(long
botUserId)` does the next best thing: it revokes the current token (discarding
the replacement Telegram issues) and forgets the bot locally, firing
`ManagedBotEvents#onDecommissioned`. The bot itself keeps existing under the
owning user's Telegram account — the user removes it themselves through
BotFather.

Revoking is itself a token change, so Telegram sends the manager a
`managed_bot` update echoing it. `ManagedBotService` suppresses that echo for
5 minutes per bot; otherwise the update would look like a brand-new bot and
the service would fetch the fresh token and re-create the row it just deleted.
The same guard swallows exactly one echo after `rotateToken`, so a rotation
you initiate fires `onTokenRotated` once rather than twice. **The guard is
JVM-local and not replicated** (like the flow's pending-login state): on a
multi-instance deployment an echo delivered to a different instance, or after
a restart, is still processed as if the owner had done it.

`decommission` is deliberately lenient about ids it does not know — unlike
`rotateToken`, which throws `IllegalArgumentException`. That is the only way
to revoke a bot whose token fetch failed and which therefore has no row.

### Recovering a bot with no stored token

When `getManagedBotToken` fails every configured attempt, the bot exists on
Telegram with nothing stored here, `onTokenFetchFailed` fires, and no further
update is coming. The same gap opens if the process dies mid-handler — the
poller advances Telegram's offset as soon as the update is queued.

`ManagedBotService#fetchAndStore(long botUserId, long ownerUserId)` is the
recovery entry point: it does exactly what update handling does (fetch with
the configured retries, encrypt, store, then publish `onCreated` or
`onTokenRotated`) without needing an update. Unlike `handleUpdate` it
**throws** `TelegramApiException` on failure instead of publishing
`onTokenFetchFailed` again, so calling it from inside that callback cannot
loop:

```java
@Bean
ManagedBotEvents managedBotEvents(ManagedBotService service) {
    return new ManagedBotEvents() {
        @Override
        public void onTokenFetchFailed(long botUserId, long ownerUserId, Exception cause) {
            // hand it to your scheduler — the usual cause is rate limiting,
            // and this callback runs on the bot's single update worker
            scheduler.schedule(() -> service.fetchAndStore(botUserId, ownerUserId),
                    1, TimeUnit.MINUTES);
        }
    };
}
```

### Restricting who may use a managed bot

`setAccessSettings(botUserId, restricted, addedUserIds)` writes both fields in
one call. An **empty** `addedUserIds` list clears the allow-list (only the
owner keeps access); `null` omits the parameter and leaves whatever Telegram
already has. Telegram caps the list at 10 users and ignores it entirely when
`restricted` is `false`; a longer list is rejected with
`IllegalArgumentException` before any request is sent.

### Security notes

- Tokens are stored **encrypted at rest** — the default `TokenEncryptor` is
  `AesGcmTokenEncryptor` (AES-256-GCM, a fresh random IV on every write,
  stored as `Base64(IV || ciphertext || tag)`); a tampered value fails to
  decrypt rather than returning garbage.
- Tokens are **never logged** and are **masked in `toString`** — both
  `ManagedBot` and `BaseManagedBot` print `encryptedToken=***`.
- **The host owns key custody.** Declaring your own `TokenEncryptor` bean
  (e.g. delegating to a KMS or vault) replaces the built-in AES-GCM default,
  and `telegram.managed-bots.encryption-key` is then not needed at all.
- **Do not set `management.endpoint.env.show-values: ALWAYS`.** Spring Boot 3
  masks property values under the `/env` and `/configprops` Actuator endpoints
  by default; that setting unmasks them and would publish
  `telegram.managed-bots.encryption-key` — the key that decrypts every stored
  token — to anyone who can reach the endpoint. Leave it at the default
  (`NEVER`), or at most `WHEN_AUTHORIZED`.
- **A token can go dead at any time** — the owning user can revoke or rotate
  it from BotFather independently of this library, so your application must
  tolerate that. When it happens, Telegram delivers another `managed_bot`
  update and `ManagedBotService` re-fetches and re-stores the token
  automatically, then calls `onTokenRotated`.
- **A rate-limited managed-bot call fails fast rather than blocking logins.**
  The `429` wait runs on the module's single update worker — the same thread
  that serves the auth flow — so `TelegramBot` waits out a `retry_after` only
  up to `TelegramBot.DEFAULT_MAX_RATE_LIMIT_WAIT` (60s) and throws beyond it,
  leaving recovery to `token-fetch-retries`, `onTokenFetchFailed` and
  `fetchAndStore`. Lower the budget by building the bot yourself:
  `TelegramBotModule.builder(token, username).bot(new TelegramBot(httpClient,
  token, "https://api.telegram.org", Duration.ofSeconds(5)))`.

## White-label tenant bots

Managed bots *create* a tenant's bot and keep custody of its token; the
**white-label runtime** actually *runs* it — one long-poll loop, one
`TelegramBotModule` and one session service per tenant, so every tenant
authenticates its own users through its own branded bot. It is a second opt-in
layer **on top of** managed bots: `telegram.managed-bots.enabled` must be `true`
as well, because the runtime is built out of `ManagedBotService` and
`ManagedBotTokenStore`, and neither exists otherwise.

### The factory the host implements

The library cannot build a tenant's session service by itself —
`AbstractSessionService` and `DefaultAuthFlow` are generic over the host's own
user and session entities, which the library never sees. So the host declares
exactly one `TenantBotFactory` bean:

```java
@FunctionalInterface
public interface TenantBotFactory<U extends BaseTelegramUser, S extends BaseAuthSession> {
    RunningBot<U, S> create(ManagedBot bot, String decryptedToken);
}
```

`RunningBot` is the pair the registry keeps — the module to poll, and the
session service it hands back to your REST layer later:

```java
public record RunningBot<U extends BaseTelegramUser, S extends BaseAuthSession>(
        TelegramBotModule module, AbstractSessionService<U, S> sessionService) {}
```

Turning the runtime on without that bean **fails the context at startup** with
`IllegalStateException: a TenantBotFactory bean is required when
telegram.white-label.enabled=true` — deliberately, rather than starting an
application in which no tenant can ever log in.

```java
@Configuration
public class TenantRuntimeConfig {

    private final TenantSessionRepository sessions;
    private final TokenGenerator tokens;

    public TenantRuntimeConfig(TenantSessionRepository sessions, TokenGenerator tokens) {
        this.sessions = sessions;
        this.tokens = tokens;
    }

    // Container-built, prototype-scoped, and it takes the tenant's module as its
    // ONLY parameter. All three matter — see the warning below.
    @Bean
    @Scope("prototype")
    TenantSessionService tenantSessionService(TelegramBotModule module) {
        return new TenantSessionService(sessions, tokens, module);
    }

    @Bean
    TenantBotFactory<TenantUser, TenantSession> tenantBotFactory(
            ObjectProvider<TenantSessionService> sessionServices,
            TenantUserService users,
            DefaultAuthFlow.Options options,
            JwtService jwt) {

        return (bot, decryptedToken) -> {
            TelegramBotModule module = TelegramBotModule.builder(decryptedToken, bot.username())
                    .botUserId(bot.botUserId())                  // required — see below
                    .approveHandler((info, ctx) -> new AuthApproveResult(Map.of(
                            "accessToken", jwt.issue(info.telegramId()),
                            "tenant", bot.botUserId())))
                    .sessionTtl(Duration.ofMinutes(5))
                    .build();

            // getObject(module) is what carries this tenant's module into the
            // prototype — plain getObject() would autowire the manager module.
            TenantSessionService service = sessionServices.getObject(module);

            new DefaultAuthFlow<>(users, service, module, options);   // registers /start
            return new RunningBot<>(module, service);
        };
    }
}
```

`TenantSessionService` and `TenantUserService` are ordinary subclasses of
`AbstractSessionService<TenantUser, TenantSession>` and
`AbstractTelegramUserService<TenantUser>`, written exactly as in [Build a
module](#build-a-module). Building `DefaultAuthFlow` with `new` is fine — it
carries no `@Transactional` and delegates every write to the session service.

> ### ⚠️ Three separate requirements hide in that `@Bean`
>
> They are commonly stated as one rule about prototype scope. They are not one
> rule: each has a different cause and a different failure.
>
> **1. Container-managed — never a plain `new`.** A hand-built service is not a
> Spring bean, so it gets no AOP proxy. `@Transactional` then silently does
> nothing: the `PESSIMISTIC_WRITE` lock in `findWithLockByTokenHash` is released
> the moment its query returns instead of serialising concurrent
> `approve`/`reject` transitions, and `publishAfterCommit` falls through to its
> "no transaction active" branch. This has **nothing to do with scope** — the
> proxy is applied by a `BeanPostProcessor`, which runs on every
> container-managed instance whatever its scope. It compiles, it runs, it passes
> a smoke test; it only corrupts data under concurrency.
>
> **2. Prototype-scoped — never singleton.** A different failure with a
> different cause. `AbstractSessionService` holds one tenant's
> `TelegramBotModule` in a final field and reads `getBotUserId`, `getSessionTtl`,
> `getMaxPendingPerIp`, `getApproveHandler` and `getBus` from it. A singleton is
> created once and returned for every tenant after the first, freezing the
> **first** tenant's module — tenant B mints tokens against tenant A's bot,
> publishes to tenant A's event bus, and draws on tenant A's rate-limit bucket.
>
> **3. Prototype scope alone is not enough — the module has to reach the bean.**
> Declare the `@Bean` method so the module is a construction argument and pass it
> with `ObjectProvider#getObject(args)`. Spring matches explicit arguments
> against the factory method's *whole* parameter list, so the `@Bean` method must
> take the module **and nothing else**; a method like
> `tenantSessionService(repo, tokens, module)` called as `getObject(module)`
> fails with `BeanCreationException: … Illegal arguments to factory method`.
> Inject the other dependencies into the `@Configuration` class instead, as
> above.

### `.botUserId(bot.botUserId())` is not optional

The module builder's `botUserId` is what stamps the tenant onto every session
row. Leave it out and the module still works — it polls, logins succeed — but
`AbstractSessionService#create` writes a `null` `bot_user_id` and falls back to
the table-wide rate-limit count. Every tenant then shares **one**
`maxPendingPerIp` bucket, so a flood against one tenant locks logins for all of
them, and no session can afterwards be attributed to the bot that created it.

### Configuration

| Property | Default | Purpose |
|----------|---------|---------|
| `telegram.white-label.enabled` | `false` | Opt-in switch for the whole runtime; the auto-configuration stays inert when false. |
| `telegram.white-label.restore-on-startup` | `true` | Start every stored tenant bot on `ApplicationReadyEvent`. Each bot is attempted independently — one bad row costs that tenant only. |
| `telegram.white-label.poll-failure-budget` | `5m` | How long a tenant bot may fail to poll *continuously* before it is stopped and deregistered. Measured in time, not attempts, so a brief outage never kills a healthy bot. |

```yaml
telegram:
  managed-bots:
    enabled: true                 # required — the runtime is built on top of it
    encryption-key: "BASE64_ENCODED_32_BYTE_KEY"
  white-label:
    enabled: true
    restore-on-startup: true
    poll-failure-budget: 5m
```

### Routing a login to the right tenant

The library does not resolve tenants — it has no idea whether yours arrive by
subdomain, header, path segment or JWT claim. The host resolves its own tenant,
maps it to a `botUserId`, and asks the registry for that tenant's session
service:

```java
@RestController
@RequestMapping("/api/auth")
public class TenantAuthController {

    private final TenantBotRegistry<TenantUser, TenantSession> registry;
    private final TenantLookup tenants;   // yours: subdomain / header / path -> botUserId

    private AbstractSessionService<TenantUser, TenantSession> serviceFor(HttpServletRequest req) {
        long botUserId = tenants.resolve(req);
        return registry.sessionServiceFor(botUserId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "bot " + botUserId + " is not running"));
    }
}
```

`sessionServiceFor(long botUserId)` returns
`Optional<AbstractSessionService<U, S>>` and is **empty for any bot that is not
currently running** — never created, still starting, stopped, decommissioned, or
dropped after exhausting its poll-failure budget. Treat the empty case as a real
runtime state, not a programming error. `registry.running()` returns the ids
currently polling, which makes a useful health endpoint.

The built-in `AbstractTelegramAuthController` cannot serve tenants as-is: it
takes one `AbstractSessionService` and one `TelegramBotModule` in its
constructor, fixed for the life of the bean. A white-label host writes its own
controller that resolves both per request, as sketched above.

### Adding a tenant's own commands

`ManagedBotCustomizer` runs against each bot the runtime has just built, right
after the auth flow registered its handlers:

```java
@Bean
ManagedBotCustomizer supportCommands(SupportService support) {
    return (module, bot) -> module.command("/support", update ->
            support.openTicket(bot.botUserId(), update));
}
```

Commands are free to register. The **single-slot** handlers are not: the auth
flow may already own them, and registering a second one throws
`IllegalStateException` rather than silently replacing it.

| Slot | Claimed by `DefaultAuthFlow` when |
|------|-----------------------------------|
| `onCallbackQuery` | `requireApproval(true)`, **or** `codeConfirmation` is anything but `OFF` — so by default (`BUTTON`) it is already taken |
| `onContact` | `requireContact(true)` (which also registers a `/skip` command) |
| `onText` | `codeConfirmation(TYPED)` |

Anything that collides goes through `module.fallback(...)`, which the flow feeds
every update it does not own — callbacks outside its `tgauth:` namespace,
contacts with no login in progress, and, in `TYPED` mode, text it cannot use.
Note `fallback` also receives **unregistered `/commands`**: once the command
registry misses, the dispatcher cannot tell them from ordinary text.

### Rotation and restart costs

A token rotation cannot be applied in place — `TelegramBot` holds its token in a
final field — so `TenantBotRegistry#restart(ManagedBot)` stops the old runner and
builds a new one. Two costs come with every restart of a tenant, whether it is a
rotation, a manual `restart`, or an application redeploy:

- **In-flight logins on that tenant are lost.** The flow's pending-login state is
  a JVM-local map, not persisted; stopping the runner discards it. Users
  mid-login simply start again.
- **Telegram may redeliver.** The new runner polls from offset 0, so updates the
  old runner had received but never confirmed by advancing past them can arrive a
  second time.

Only the rotated tenant is affected — the other tenants keep polling.

### When a token dies

A tenant bot that fails to poll for the whole `poll-failure-budget` without a
single success is stopped and dropped from the registry, and a warning is logged.
`sessionServiceFor` goes empty for it from that moment.

> **A poll failure is not proof of a revoked token.** The runner gives up through
> the same path for an unparseable payload and for a `409` from a competing
> poller (another instance of your application on the same token) as it does for
> a revoked one. The budget is measured in *time* rather than attempts precisely
> so a brief network outage cannot kill a healthy bot — but the log line says
> "probably revoked", and it means probably.

Deregistration is in-memory only: the bot's row and encrypted token are still in
your `ManagedBotTokenStore`, so bringing it back needs no re-creation. Any of
these does it:

- The owner issues a fresh token in BotFather. Telegram sends the manager a
  `managed_bot` update, `ManagedBotService` re-fetches and re-stores it and fires
  `onTokenRotated`, and the bridge restarts the bot — no host code at all.
- The host calls `ManagedBotService#fetchAndStore(botUserId, ownerUserId)`, which
  publishes the same event and so reaches the registry the same way.
- The host calls `registry.start(bot)` itself, or restarts the application with
  `restore-on-startup: true`.

If the token really is dead, the bot starts, fails for another budget and drops
out again — so back off between attempts rather than looping.

### Threading, and the honest ceiling

Every running tenant bot costs **two platform threads**: one poll thread
(`tg-auth-poll-<username>`) and one single-threaded update worker
(`tg-auth-work-<username>`). Both are daemon threads. At a few dozen tenants that
is unremarkable; at several hundred it is not.

On Java 21+ a host can hand the runtime a virtual-thread factory, and both pools
use it:

```java
@Bean
ThreadFactory tenantThreadFactory() {
    return Thread.ofVirtual().name("tg-tenant-", 0).factory();
}
```

**The library itself stays on Java 17 and never references a virtual-thread
API** — the seam is a plain `java.util.concurrent.ThreadFactory`, and the
decision is entirely the host's.

Two things to know before reaching for it:

- **Supplying a factory erases the name distinction.** The supplied factory is
  used as-is for *both* pools — it owns its threads' names and daemon status, and
  forcing `setDaemon` on a virtual thread would throw — so `tg-auth-poll-` and
  `tg-auth-work-` disappear from thread dumps. Operators lose the ability to tell
  a stuck poll from a stuck handler at a glance. That is a real diagnostic cost,
  not a cosmetic one.
- **The registry resolves the factory with `getIfAvailable()`, so declare at most
  one.** Two `ThreadFactory` beans in the context fail startup with
  `NoUniqueBeanDefinitionException: … expected single matching bean but found 2`.
  If your application already has one for unrelated work, mark one `@Primary` or
  keep the runtime on the built-in default.

**The practical ceiling is untested.** No load test in this repository establishes
how many tenant bots one instance can carry, and threads are probably not the
binding constraint anyway: each bot holds a *simultaneous long-poll HTTP
connection* to Telegram from one address, and connection limits — your HTTP
client's pool, and Telegram's own tolerance for concurrent pollers from one IP —
will bite before thread count does. Measure it for your deployment; do not read a
number into this section, because there isn't one.

### The library owns the `ManagedBotEvents` bean

When the runtime is on, `TenantBotEventBridge` **is** the `ManagedBotEvents`
bean: the white-label auto-configuration is ordered before the managed-bots one
so its bridge wins the `@ConditionalOnMissingBean`, and `ManagedBotService` is
wired with it. That is what turns bot lifecycle into runtime lifecycle — created
starts, token-rotated restarts, decommissioned stops, each failure swallowed and
logged so one bad tenant cannot disturb the manager bot or the others.

So a host **cannot** also declare its own `ManagedBotEvents` bean: the context
fails with `NoUniqueBeanDefinitionException … found 2: yourEvents,
tenantBotEventBridge`. Per-bot wiring belongs in `ManagedBotCustomizer`; anything
else you need from the lifecycle you can do inside the `TenantBotFactory`, which
runs on every start.

### Single instance only

`TenantBotRegistry` is JVM-local and single-instance by design. Nothing here
attempts ownership, leasing or coordination, because **two application instances
polling the same bot collide**: Telegram answers `409 Conflict` and updates go to
whichever poller wins each race.

> **Do not scale this horizontally.** Running two instances with
> `telegram.white-label.enabled=true` against the same token store is not a
> degraded configuration, it is a broken one — updates are dropped, in-flight
> logins are split across instances that cannot see each other's pending state,
> and the resulting poll failures look exactly like revoked tokens. A
> multi-instance deployment needs a lease or a webhook design first; both are
> explicitly out of scope for this release. Run exactly one instance with
> `telegram.white-label.enabled=true`.

## Status & roadmap

> **MVP.** Long-polling transport, in-memory per-module event bus, single
> instance.

- [x] Multi-instance abstract toolkit (N user types, N bots).
- [x] `DefaultAuthFlow` with self-registering `/start`.
- [x] Contact-share + Approve/Reject inline keyboard (opt-in `Options`), 3-language bot texts.
- [x] Number matching (`codeConfirmation`) with per-user cooldown, and flow options bindable from YAML.
- [x] Managed bots (opt-in): `/newbot` deep link, encrypted token custody, lifecycle events, access settings, decommission.
- [x] White-label tenant bots (opt-in): a long-poll runtime per managed bot, per-tenant rate limiting, startup restore, poll-failure budget. Single instance only.
- [ ] SSE & WebSocket transports.
- [ ] Redis-backed event bus + multi-instance horizontal scaling (would also make in-flight login state survive failover).

## Publishing

Published to Maven Central via the [Sonatype Central
Portal](https://central.sonatype.com). See [`PUBLISHING.md`](PUBLISHING.md) for
prerequisites and the release procedure.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
