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

## What's in it

Three independent layers, each with its own switch. The auth flow stands alone;
managed bots need it; white-label needs both.

| Feature | What it does | Turned on by |
| --- | --- | --- |
| [Bot login flow](#build-a-module) | `/start <token>` deep link: the client opens a session, the user confirms in your bot, your backend returns whatever payload it decides | `telegram.auth.enabled=true` |
| [REST endpoints](#rest-api-per-module) | Create a session, long-poll for the outcome, read status, cancel — one set per user type | comes with the flow |
| [Contact share and approve/reject](#build-a-module) | Ask for the user's phone via contact-share (`/skip` allowed), and an inline ✅/❌ confirmation showing the session's IP and device | `Options.requireContact` / `requireApproval`, both off by default |
| [Number matching](#number-matching-codeconfirmation) | The browser shows a two-digit code the user must pick in the bot, so tapping a link is no longer enough to complete a login | on by default (`codeConfirmation=BUTTON`) |
| [Multiple user types](#multiple-user-types) | N independent bots, tables and REST prefixes from one dependency | declare one module per type |
| [Managed bots](#managed-bots) | Your bot creates bots on your users' behalf and keeps custody of their tokens, encrypted — with rotation, access settings and decommission | `telegram.managed-bots.enabled=true` |
| [Managed-bot intents](#intents) | Matches a created bot back to the request that asked for it by the creator's Telegram id, so an edited username no longer orphans the bot | declare a `ManagedBotIntentStore` bean |
| [Host-account linking](#linking-a-telegram-account-to-a-host-account) | An opaque `hostRef` set server-side at session creation reaches your approve handler, so you can tell a login from "admin 7 is linking their Telegram" | comes with the flow |
| [White-label tenant bots](#white-label-tenant-bots) | Every managed bot gets its own polling runtime and session service, so each tenant authenticates through its own branded bot | `telegram.white-label.enabled=true` |

Every property is listed in the [configuration reference](#configuration-reference).
[Status & roadmap](#status--roadmap) says what is *not* built yet.

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
    <version>0.5.0</version>
</dependency>
```

**Gradle (Kotlin DSL):**

```kotlin
implementation("io.github.dev-abdulhay:telegram-auth-spring-boot-starter:0.5.0")
```

Then enable the starter:

```yaml
telegram:
  auth:
    enabled: true                  # master switch; auto-config is inert when false
    cleanup-cron: "0 */5 * * * *"  # optional — expired-session sweep schedule
```

> **Upgrading from 0.4.x?** 0.5.0 adds one column to the session table and every
> host must apply it, whether or not it uses the new features. See
> [Upgrading to 0.5.0](#upgrading-to-050).
>
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

## Upgrading to 0.5.0

Nothing in 0.5.0 breaks a 0.4.0 public signature. There is exactly **one forced
migration**, and it is not optional:

1. **Add `host_ref` to every auth-session table.**

   ```sql
   ALTER TABLE auth_session ADD COLUMN host_ref VARCHAR(128);
   ```

   (Substitute your own table name, once per module — `admin_tg_session`,
   `customer_tg_session`, and so on.) The column is new and nullable, so there is
   nothing to backfill, but **every host must apply it whether or not it uses
   [linking](#linking-a-telegram-account-to-a-host-account)**: `BaseAuthSession`
   now maps the field, so Hibernate schema validation fails the application
   context at startup without it. This is the only change 0.5.0 forces on an
   existing table.

Everything else is additive, and none of it needs a decision on upgrade day:

2. **Managed-bot intents are opt-in.** No `ManagedBotIntentStore` bean means no
   `/start mb_` route, no matching, no behaviour change — `createLink` works
   exactly as it did. When you do want them, the new `managed_bot_intent` table
   is a **new** table, created by your own migration; the DDL is in
   [Intents](#intents).
3. **`ManagedBotEvents` gained four `default` methods** — `onIntentClaimed`,
   `onIntentMatched`, `onIntentUnmatched`, `onIntentAmbiguous`. Existing
   implementations compile and behave unchanged; no action needed.
4. **Managed-bot events are now published through a per-listener guard.** This is
   a real behaviour change, and the one to read twice if you have a
   `ManagedBotEvents` bean: an exception thrown by your `onCreated`,
   `onTokenRotated`, `onTokenFetchFailed` or `onDecommissioned` used to escape
   `ManagedBotService` — aborting the rest of the work on the update path, and
   reaching your own call site when you had called `rotateToken`,
   `decommission` or `fetchAndStore` yourself. It is now caught, logged at
   `WARN`, and the next event still fires. If you were relying on a throw to
   abort anything, you no longer can — record the failure yourself. (Under the
   white-label runtime the bridge already swallowed them, so nothing changes
   there.)
5. **`AbstractSessionService.create(ip, ua)` and the two-argument `AuthContext`
   constructor are kept**; the `hostRef` variants are overloads. `POST /session`
   still ignores anything `hostRef`-shaped in the request body, on purpose.
6. **`TelegramBotModule` gained `startPayload` / `getStartPayloadRoutes`.**
   `command("/start", …)` is untouched, and `DefaultAuthFlow` did not change.

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
| `telegram.managed-bots.intent-ttl` | `30m` | How long an `OPEN` [intent](#intents) stays claimable. A `CLAIMED` intent never expires — its owner is proven and the resolution screen needs it to survive. |
| `telegram.managed-bots.intent-retention` | `7d` | How long closed intents (expired, cancelled) are kept before the purge deletes them. |

```yaml
telegram:
  managed-bots:
    enabled: true
    encryption-key: "BASE64_ENCODED_32_BYTE_KEY"
    token-fetch-retries: 3
    token-fetch-backoff: 1s
    intent-ttl: 30m          # only used when an intent store bean is declared
    intent-retention: 7d
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

### Intents

`createLink` hands out a bot-creation deep link that carries **nothing of yours**.
The `managed_bot` update that comes back says who created the bot
(`ownerUserId`) and which bot it is (`botUserId`) — not which of your requests it
answers. The only field you could match on without extra state is the username,
and the username is a *suggestion*: Telegram lets the user edit it in the
confirmation dialog. When they do, the correlation breaks silently and the bot
arrives with nothing to attach it to.

An **intent** is that missing state. You create one before handing out the link;
the user opens the link on the **manager** bot first, which proves their Telegram
id; the bot they create afterwards is matched back to the intent by **that id**,
which an edited username cannot break. When matching cannot decide on its own,
the library gives you what a human needs to decide instead — see
[the resolution screen](#the-resolution-screen-and-its-owner-scoped-caveat).

Intents are opt-in on top of managed bots and purely additive: declare a
`ManagedBotIntentStore` bean and the feature turns on. Without that bean nothing
changes — no `/start` route is registered, no matching runs, `createIntent`
throws `IllegalStateException`, and `createLink` behaves exactly as it did in
0.4.0.

#### The store you provide

Same division of labour as `ManagedBotTokenStore`: the library owns the contract,
you own the table.

```java
public interface ManagedBotIntentStore {
    void save(ManagedBotIntent intent);                        // upsert by id
    Optional<ManagedBotIntent> findById(String id);
    Optional<ManagedBotIntent> findByBotUserId(long botUserId);
    List<ManagedBotIntent> findClaimedByOwner(long ownerUserId);
    int deleteClosedBefore(OffsetDateTime cutoff);
}
```

**Option A — JPA**, subclassing `BaseManagedBotIntent` and
`BaseManagedBotIntentRepository`:

```java
@Entity
@Table(name = "managed_bot_intent",
        indexes = {
            @Index(name = "idx_managed_bot_intent_owner_status", columnList = "owner_user_id,status"),
            @Index(name = "idx_managed_bot_intent_status_expires", columnList = "status,expires_at")
        })
public class TenantIntent extends BaseManagedBotIntent {}

public interface TenantIntentRepository extends BaseManagedBotIntentRepository<TenantIntent> {}

@Bean
ManagedBotIntentStore managedBotIntentStore(TenantIntentRepository repo) {
    return new JpaManagedBotIntentStore<>(repo, TenantIntent::new);
}
```

**Option B — in-memory** (tests, or hosts that do not use JPA):

```java
@Bean
ManagedBotIntentStore managedBotIntentStore() {
    return new InMemoryManagedBotIntentStore();
}
```

The primary key is **assigned, not generated**: `ManagedBotService` mints the id
(22 Base64URL characters), it travels inside a Telegram `/start` payload, and it
is looked up by that value — a surrogate key would buy nothing. That is why
`BaseManagedBotIntent` carries a bare `@Id`, and why the factory you pass to
`JpaManagedBotIntentStore` must return a **blank, unsaved** entity.

The table, like `managed_bot`, is yours to create (PostgreSQL):

```sql
CREATE TABLE managed_bot_intent (
    id                 VARCHAR(32)  PRIMARY KEY,
    suggested_username VARCHAR(50),
    suggested_name     VARCHAR(100),
    host_ref           VARCHAR(128),
    owner_user_id      BIGINT,
    status             VARCHAR(16)  NOT NULL,
    bot_user_id        BIGINT,
    created_at         TIMESTAMPTZ  NOT NULL,
    claimed_at         TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    expires_at         TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_managed_bot_intent_owner_status ON managed_bot_intent (owner_user_id, status);
CREATE INDEX idx_managed_bot_intent_status_expires ON managed_bot_intent (status, expires_at);
CREATE UNIQUE INDEX uq_managed_bot_intent_bot_user_id ON managed_bot_intent (bot_user_id) WHERE bot_user_id IS NOT NULL;
```

All three indexes earn their keep: `(owner_user_id, status)` is the matching
lookup (`findClaimedByOwner`), `(status, expires_at)` is the retention purge, and
the unique index on `bot_user_id` is what makes two concurrent assignments of one
bot resolve as `BOT_ALREADY_ASSIGNED` instead of both succeeding. The partial form
keeps the many unassigned rows out of that index; a plain `UNIQUE` works too on
any database that allows repeated `NULL`s.

#### The flow

1. **You create the intent.** `createIntent(...)` returns a
   `ManagedBotIntentLink` — the id, the URL
   `https://t.me/<manager>?start=mb_<intentId>`, and when it expires. Store the
   id against whatever row in your own schema this bot is for.
2. **You hand the URL to the user.** Not the `/newbot` link: this one goes to
   your **manager** bot.
3. **The user opens it.** `ManagedBotIntentFlow` claims the intent for that
   Telegram id, fires `onIntentClaimed(intent)`, and replies with one inline
   button that opens the real bot-creation link.
4. **The user creates the bot,** editing the username if they feel like it.
5. **Telegram sends the `managed_bot` update.** The token is fetched and stored
   as it always was, `onCreated(bot)` fires, and then the bot is matched back to
   the intent by its creator's id — `onIntentMatched(bot, intent)`.

Creating one:

```java
ManagedBotIntentLink link = managedBotService.createIntent(
        "mycompany_sales_bot",     // suggested username, nullable
        "My Company Sales",        // suggested name, nullable
        "bot:42");                 // hostRef — opaque to the library

link.intentId();    // store it against your own row
link.url();         // https://t.me/<manager>?start=mb_<intentId> — hand this to the user
link.expiresAt();   // createdAt + telegram.managed-bots.intent-ttl
```

- The suggested username is validated **eagerly**, with exactly the rules
  `createLink` uses, so an `IllegalArgumentException` lands in your request thread
  rather than on the bot's update worker when the claim reply builds the same
  link. `null` or blank means "no suggestion", as it does for `createLink`.
- `hostRef` is opaque and at most 128 characters; longer is an
  `IllegalArgumentException`. The library never reads it — it comes back to you
  on every intent event, and it is the same idea as the session `hostRef` in
  [Linking a Telegram account to a host account](#linking-a-telegram-account-to-a-host-account).
- No bean of type `ManagedBotIntentStore` in the context → `IllegalStateException`.

The rest of the lifecycle:

```java
Optional<ManagedBotIntent> intent = managedBotService.findIntent(intentId);
managedBotService.cancelIntent(intentId);   // OPEN|CLAIMED -> CANCELLED
```

```
OPEN ──/start (first user)──▶ CLAIMED ──auto match / assignToIntent──▶ COMPLETED
  │                              │
  ├──now > expiresAt──▶ EXPIRED  └──cancelIntent──▶ CANCELLED
  └──cancelIntent──▶ CANCELLED
```

**Expiry is lazy.** There is no scheduler. `findIntent` (and the claim path)
reports an overdue `OPEN` row as `EXPIRED` and persists that when it sees it, so
an intent nobody ever reads stays `OPEN` in your table until the purge reaps it —
which is why the purge predicate is written against `expires_at` and not against
the status alone. The purge itself runs from `createIntent`, at most once a
minute per JVM, deleting every row that is neither `CLAIMED` nor `COMPLETED` and
whose `expires_at` is older than `intent-retention`. `COMPLETED` rows are kept
forever: they *are* the bot→intent link that `findUnassignedBots` reads.
`cancelIntent` throws `ManagedBotIntentException` with `INTENT_NOT_FOUND` or
`INTENT_CLOSED`.

#### How a bot is matched to an intent

Matching runs on creation (from `handleUpdate` and from a `fetchAndStore`
recovery) — never on a token rotation, never for an echo of a change the library
itself made — and once more on the first claim, because the bot may already exist
by the time the link is opened.

On creation, for the bot's `ownerUserId`:

1. Already linked to an intent (`findByBotUserId` present)? Do nothing — this is
   a re-delivered update.
2. Candidates are that owner's `CLAIMED` intents, **filtered to
   `bot.createdAt() >= intent.createdAt()`**. An intent cannot predate the bot it
   asked for, and without the filter a bot created for something else entirely
   would become a silent auto-match candidate.
3. No candidates → do nothing, no event. This is the intent-less 0.4.0 path.
4. Exactly one candidate whose `suggestedUsername` equals the bot's username
   (case-insensitive, null-safe on both sides) → match it.
5. Otherwise exactly one candidate at all → match it.
6. Otherwise → `onIntentUnmatched(bot, candidates)`, and nothing is linked.

On the first claim the same reasoning runs mirrored — one intent against that
owner's unassigned bots — and the undecidable case is `onIntentAmbiguous(intent,
candidates)`.

A match sets `status = COMPLETED`, `botUserId` and `completedAt`, and **persists
that before any event is published**, so a listener that throws cannot leave a
half-linked intent behind.

#### Events

Four more no-op defaults on `ManagedBotEvents`:

```java
default void onIntentClaimed(ManagedBotIntent intent) { }
default void onIntentMatched(ManagedBot bot, ManagedBotIntent intent) { }
default void onIntentUnmatched(ManagedBot bot, List<ManagedBotIntent> candidates) { }    // one bot, several intents
default void onIntentAmbiguous(ManagedBotIntent intent, List<ManagedBot> candidates) { } // one intent, several bots
```

- On creation: `onCreated(bot)` first, then exactly one of `onIntentMatched` /
  `onIntentUnmatched` / nothing.
- On claim: `onIntentClaimed(intent)` first, then exactly one of
  `onIntentMatched` / `onIntentAmbiguous` / nothing.
- Every managed-bot callback is now published through a per-listener guard: a
  handler that throws is logged at `WARN` and the next event still fires. In
  0.4.0 an exception from `onCreated` escaped `ManagedBotService` and took the
  rest of the work with it; it no longer does. This matches what the white-label
  bridge has always done.
- `TenantBotEventBridge` forwards all four to your own `ManagedBotEvents` bean,
  with the same self-filtering and swallow-and-log it already applies to the
  other callbacks — so intents work the same whether or not the white-label
  runtime is on.

```java
@Bean
ManagedBotEvents managedBotEvents(BotConnectionService connections) {
    return new ManagedBotEvents() {
        @Override
        public void onIntentClaimed(ManagedBotIntent intent) {
            connections.markConfirmedByUser(intent.hostRef(), intent.ownerUserId());
        }
        @Override
        public void onIntentMatched(ManagedBot bot, ManagedBotIntent intent) {
            connections.connect(intent.hostRef(), bot.botUserId());   // no username guessing
        }
        @Override
        public void onIntentUnmatched(ManagedBot bot, List<ManagedBotIntent> candidates) {
            candidates.forEach(i -> connections.needsAssignment(i.hostRef()));
        }
        @Override
        public void onIntentAmbiguous(ManagedBotIntent intent, List<ManagedBot> candidates) {
            connections.needsAssignment(intent.hostRef());
        }
    };
}
```

#### `/start mb_…` routing, and why the handler returns a `boolean`

`ManagedBotIntentFlow` claims the intent and replies with the bot-creation
button. It is auto-configured for you as soon as a `ManagedBotIntentStore` bean
exists (`@ConditionalOnBean`), and it self-registers into the manager bot's
module exactly the way `DefaultAuthFlow` registers `/start` — constructing it is
all the wiring it needs. Override `msg(FlowMessages.Key, String lang)` to change
the wording; the four new keys are `INTENT_PROMPT`, `BTN_CREATE_BOT`,
`INTENT_OTHER_OWNER` and `INTENT_ALREADY_DONE`, and an expired or cancelled link
reuses the existing `INVALID_LINK`.

It does **not** take the `/start` command slot. Routing is a new, separate
registry on the module, consulted by `BotUpdateDispatcher` after it has resolved
`/start` and before the command registry:

```java
public void startPayload(String prefix, Predicate<JsonNode> handler);   // duplicate prefix -> IllegalStateException
public Map<String, Predicate<JsonNode>> getStartPayloadRoutes();        // unmodifiable, like getCommands()
```

The prefix must be non-blank and limited to Telegram's start-payload alphabet
`[A-Za-z0-9_-]`; anything else is an `IllegalArgumentException`. The longest
matching prefix wins. The route runs on the update worker thread, never on the
polling thread, so the store lookup it performs cannot stall polling.

**The handler returns whether it owned the update, and `false` sends the update
on to the normal `/start` handler.** That fall-through is not a nicety. Login
tokens are Base64URL, so a token beginning with `mb_` is not impossible — it is
roughly one in `64³ ≈ 262 144`. A fire-and-forget consumer would answer that user
"link invalid" and never log them in, silently and unreproducibly. Because the
intent store simply does not know the id, `claimIntent` returns `UNKNOWN`, the
flow returns `false`, and the login proceeds exactly as it always has.

The same design removes a registration-order hazard: `DefaultAuthFlow` is not
modified and does not have to be constructed first. If a module registers no
`/start` handler at all, an unclaimed payload is silently dropped — the update is
still consumed, but `BotUpdateDispatcher`'s composed handler simply does nothing
when the command handler is `null`; there is no log line. That is also a routing
change from 0.4.0, not "as before": a `/start <payload>` with no `/start` command
registered used to fall through to the **text** handler, whereas a matched
start-payload route now consumes the update at the dispatcher and returns, so the
text handler is never reached for it.

What the user sees for each outcome:

| `/start mb_<id>` | Reply |
|---|---|
| id the store does not know | nothing from this flow — falls through to the login handler |
| expired or cancelled | `INVALID_LINK` |
| claimed or completed by a **different** Telegram user | `INTENT_OTHER_OWNER`; the first claimer keeps it |
| already completed, same user | `INTENT_ALREADY_DONE` |
| first claim, or the same user tapping again | `INTENT_PROMPT` plus one inline URL button, `BTN_CREATE_BOT` |
| not a private chat | nothing; the update is consumed and dropped |

The private-chat rule mirrors `DefaultAuthFlow`'s exactly: an intent must never
be claimable from a group, where anyone could tap a forwarded link. One more case
hides behind the prompt row: if the claim itself found a waiting bot and
completed the intent on the spot, asking for a bot now would be absurd, so the
reply is `INTENT_ALREADY_DONE` instead of the prompt.

#### The resolution screen, and its owner-scoped caveat

`onIntentUnmatched` and `onIntentAmbiguous` mean a human has to choose. Three
methods back the screen you build for that:

```java
List<ManagedBot> bots = managedBotService.findUnassignedBots(intentId);
ManagedBotIntent done = managedBotService.assignToIntent(intentId, botUserId);
managedBotService.decommissionUnassigned(botUserId);
```

- `findUnassignedBots` returns that intent's owner's managed bots that no intent
  claims. It is **not** filtered by age — a bot created before intents existed is
  precisely the case this screen has to repair. It returns an empty list when the
  intent is unknown or not `CLAIMED`. The `ManagedBot` records it returns carry
  the token only in its encrypted form, masked in `toString` as everywhere else —
  do not put it on the screen.
- `assignToIntent` links the bot by hand and runs the normal `onIntentMatched`
  path, returning the saved intent. Call it outside your own `@Transactional`
  method when you can: the store flushes immediately so the unique `bot_user_id`
  index can arbitrate a concurrent assignment, and that flush failure would
  otherwise poison the caller's own transaction.
- `decommissionUnassigned` is `decommission` with a guard: it refuses a bot that
  some intent already claims, so a misclick cannot disconnect a live tenant.
  Everything else about it is `decommission` — the token is revoked, the row is
  forgotten, and **the bot keeps existing on Telegram**, because the Bot API has
  no way to delete it. Only its owner can, in BotFather. Say so in your
  confirmation dialog.

Every failure is a `ManagedBotIntentException` carrying a `Reason` you can branch
on, never a bare `RuntimeException`:

| `Reason` | When |
|---|---|
| `INTENT_NOT_FOUND` | no such intent |
| `INTENT_NOT_CLAIMED` | the intent is `OPEN`, `COMPLETED`, `EXPIRED` or `CANCELLED` |
| `INTENT_CLOSED` | `cancelIntent` on an intent that is already completed, cancelled or expired |
| `BOT_NOT_FOUND` | the token store does not know that bot |
| `OWNER_MISMATCH` | the bot was created by a different Telegram user than the one who claimed the intent |
| `BOT_ALREADY_ASSIGNED` | the bot already belongs to an intent — including a concurrent `assignToIntent` losing the race on the unique `bot_user_id` index, and `decommissionUnassigned` on an assigned bot |

A genuine store failure is **not** dressed up as one of these: if the save fails
and the store still reports the bot as unassigned, the original exception is
logged and rethrown unchanged.

> **The list is owner-scoped, not host-scoped.** `findUnassignedBots` returns
> every unassigned managed bot belonging to that **Telegram user** — including
> bots they created for another organisation, another product of yours, or by
> hand for something unrelated. The library cannot tell them apart: it has no
> notion of organisation or tenant, by design. Two consequences, and neither is
> optional:
>
> - **Gate this screen behind your own permission check.** Reach it only through
>   an intent your requesting account owns, and only for a user allowed to manage
>   that account's bots.
> - **Word the removal confirmation accordingly.** "Remove from platform" may be
>   pointed at a bot that has nothing to do with the request in front of the
>   user, so name the bot, say that it will be disconnected from your platform,
>   and say that the bot itself survives until its owner deletes it in BotFather.

### Deleting a managed bot

The Bot API exposes **no method to delete a managed bot** — this is a live
Telegram platform limitation, not a gap in this library. `decommission(long
botUserId)` does the next best thing: it revokes the current token (discarding
the replacement Telegram issues) and forgets the bot locally, firing
`ManagedBotEvents#onDecommissioned`. The bot itself keeps existing under the
owning user's Telegram account — the user removes it themselves through
BotFather.

Revoking is itself a token change, so Telegram sends the manager a
`managed_bot` update echoing it. `ManagedBotService` swallows that echo;
otherwise the update would look like a brand-new bot and the service would fetch
the fresh token and re-create the row it just deleted. `rotateToken` is guarded
the same way, so a rotation you initiate fires `onTokenRotated` once rather than
twice.

The guard is **one-shot** — one `replaceManagedBotToken` call echoes once. For
`decommission`, if that call itself fails (for example the owner already deleted
the bot in BotFather), no echo is coming, so the guard is disarmed immediately
rather than left armed; only a revocation that *succeeds* but whose echo is slow
or never arrives waits out the 5-minute TTL. So an owner who genuinely
re-authorises a bot you just decommissioned, or rotates one moments after you
did, is handled normally: the second update is not mistaken for your echo, and
the bot comes back. **The guard is JVM-local and not replicated** (like
the flow's pending-login state): on a multi-instance deployment an echo delivered
to a different instance, or after a restart, is still processed as if the owner
had done it.

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
loop. Note that `ownerUserId` is **always written** to the row — for a bot the
store already knows, pass the owner it already holds (the username and first
name, which this entry point cannot supply, are kept from the existing row):

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

## Linking a Telegram account to a host account

Your platform already has accounts of its own — admins, staff, operators. Sooner
or later one of them wants to sign in with Telegram, or to create bots through
the manager bot, and both need the same missing piece: a way to bind **one
Telegram identity to one of your accounts**.

Until 0.5.0 the approve handler could not help. `onApprove(TelegramUserInfo,
AuthContext)` gets the confirmed Telegram identity and the request's IP and user
agent — and nothing at all that says *which* session this is or *why* it was
created. "Someone is logging in" and "admin 7 is linking their Telegram" arrive
looking identical.

One opaque string fixes it.

### The `hostRef` contract

```java
// AbstractSessionService
public CreatedSession create(String ipAddress, String userAgent);                  // unchanged — delegates with null
public CreatedSession create(String ipAddress, String userAgent, String hostRef);  // new

// AuthContext
public AuthContext(String ipAddress, String userAgent);                  // kept
public AuthContext(String ipAddress, String userAgent, String hostRef);  // new
public String getHostRef();
```

The value is stored on the session row (`host_ref VARCHAR(128)` on
`BaseAuthSession`) and handed back to your approve handler on that same session,
after the Telegram identity has been confirmed by the whole flow — contact, ✅,
number matching, whichever of them you turned on. Longer than 128 characters is
an `IllegalArgumentException` at `create`. Nothing else in the library reads it:
no notion of "admin", no account merging, no interpretation whatsoever. It is the
same idea, and the same 128-character budget, as the intent's own `hostRef`.

```java
@Override
public AuthApproveResult onApprove(TelegramUserInfo info, AuthContext ctx) {
    String ref = ctx.getHostRef();
    if (ref != null && ref.startsWith("link:")) {
        return link(ref.substring("link:".length()), info);
    }
    return login(info);   // ordinary sign-in, exactly as before
}
```

### `hostRef` is server-side only

This is a security rule, not a style preference. `hostRef` says *whose account
this session is allowed to bind to*, so a client that could set it could mint
`link:admin:7` and attach its own Telegram account to somebody else's.

The stock `POST /session` endpoint therefore **never reads it**, and
`CreateSessionRequest` has no field for it. Set it in your own code, from an
already-authenticated principal:

```java
// your controller; the admin is authenticated by your platform's own session
var created = sessionService.create(req.getRemoteAddr(), req.getHeader("User-Agent"),
        "link:admin:" + currentAdminId());
// return created.rawToken() and the t.me deep link to the browser, as usual
```

If you would rather extend the stock controller than write an endpoint, there is
exactly one override point, and it takes the request rather than the body:

```java
@RestController @RequestMapping("/api/admin-auth")
public class AdminAuthController extends AbstractTelegramAuthController<AdminUser, AdminSession> {

    public AdminAuthController(AdminSessionService service, TelegramBotModule module) {
        super(service, module);
    }

    @Override
    protected String hostRef(HttpServletRequest request) {
        Long adminId = currentAdminId(request);         // your session, your cookie, your rules
        return adminId == null ? null : "link:admin:" + adminId;
    }
}
```

It returns `null` by default, which means "an ordinary login". Because the
override is your code reading your own server-side state, the rule still holds.

### Linking is host semantics

The library carries the string; what it *means* is yours. Three rules are worth
stating because the failure modes are unpleasant:

- **Branch, don't assume.** A `hostRef` that is `null` or does not match your
  linking prefix is an ordinary login and must stay one.
- **Refuse a second binding, and refuse it with a payload.** If that Telegram id
  is already bound to a different account, do not rebind — return something the
  browser can render, such as
  `new AuthApproveResult(Map.of("linked", false, "reason", "already_bound"))`.
  Do **not** throw: an exception out of `onApprove` is logged and rethrown by
  `approve(...)`, the transaction rolls back, and the session is left sitting in
  its previous state with nothing shown to anybody. The user stares at a spinner
  and you get a stack trace.
- **Ask for the phone at the module, not the session.** `requireContact` is a
  `DefaultAuthFlow.Options` flag on the module, not a per-session choice, so the
  manager bot's module either asks every login and link for a phone or asks none
  of them. It is a soft requirement in any case — `/skip` exists.

The `BaseTelegramUser` row is created by the flow at final confirmation exactly as
it always was, so later sign-ins need nothing extra from you.

### Linking without a login, at intent claim

There is a second way in, and it needs **no library support at all**:
`onIntentClaimed(intent)` already carries `ownerUserId` — the Telegram id that
just tapped the link — and `hostRef` — whatever you wrote when you created the
intent. An admin who creates a bot for their own organisation can be bound right
there, with no phone step and no second trip through the login flow.

**Only bind self-service links.** An intent URL is a *bearer capability*:
whoever opens it first claims it. If your admin creates a bot on behalf of
another organisation and forwards the link, the claimer is that organisation's
owner — auto-linking them to the admin's account would hand a stranger the
admin's sign-in. So encode the difference in the `hostRef` itself and read it
back on claim:

```java
managedBotService.createIntent(username, name, "bot:42;self:admin:7");  // admin creates it for themselves
managedBotService.createIntent(username, name, "bot:42");               // admin creates it for someone else
```

- Mark a link `self:` only when your UI showed it to the authenticated admin for
  their own use — never for a link they are expected to forward.
- Bind only when the account has **no** Telegram id yet. If it already has one
  and a *different* id claims the intent, that means the link was forwarded:
  surface it in your admin UI, do not rebind.
- Keep `intent-ttl` short. 30 minutes is the default for this reason.
- No phone is collected on this path. If you need one, require the linking flow
  above before granting anything sensitive.

### Manager bot vs tenant bots

Both features live on a `TelegramBotModule`, and it helps to be explicit about
which one:

- **The manager bot** — one per platform. It owns the `managed_bot` slot
  (`ManagedBotUpdateHandler`), the `mb_` start-payload route
  (`ManagedBotIntentFlow`), and a `DefaultAuthFlow` of its own — typically with
  `requireContact(true)` — for admin login and linking.
- **Tenant bots** — one per created bot, started by `TenantBotRegistry`
  ([white-label](#white-label-tenant-bots)). Each has its own `DefaultAuthFlow`
  for that tenant's end users, its own rate-limit scope, and sessions carrying
  its `botUserId`.

Nothing new is needed to compose them; they are separate modules and they do not
interfere.

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
> This one is caught rather than suffered: `TenantBotRegistry#start`
> compares the object the factory returned against the ones it already holds and
> throws `IllegalStateException` if a second tenant is handed the same session
> service or module. The second tenant fails to start, loudly, instead of quietly
> running on the first tenant's identity. It is a backstop, not a substitute for
> getting the scope right — it only fires once two tenants are live.
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
row, and what every tenant-scoped query keys off. Leave it out and the module
still works — it polls, logins succeed — but `AbstractSessionService#create`
writes a `null` `bot_user_id`, and the service falls back to its table-wide
behaviour on both counts below.

**Rate limiting.** Every tenant then shares **one** `maxPendingPerIp` bucket, so
a flood against one tenant locks logins for all of them, and no session can
afterwards be attributed to the bot that created it.

**Session lookup.** With a bot id set, `findByRawToken`, `approve`, `awaitCode`
and `reject` resolve the session by `token_hash` **and** `bot_user_id`, so a
token minted by tenant A simply does not exist as far as tenant B's service is
concerned. Without it, one tenant's service will happily complete another's
session — and publish the terminal event on the wrong bot's `AuthEventBus`,
leaving the browser that started the login waiting forever on a session the
database already shows as `APPROVED`.

A module with no bot id keeps the original unscoped queries exactly as they
were, so nothing changes for a statically configured host. If you implement
`BaseAuthSessionRepository` by hand rather than letting Spring Data derive it,
note the two added methods: `findByTokenHashAndBotUserId` and
`findWithLockByTokenHashAndBotUserId`.

> **Migrating an existing deployment: drain the in-flight logins first.** The
> tenant-scoped queries match on `bot_user_id = ?`, and a `NULL` never satisfies
> that. So every live (`PENDING` / `AWAITING_CODE`) session your *statically*
> configured bot already wrote — all of which carry a `NULL` `bot_user_id` —
> becomes invisible to the tenant modules the moment you switch a deployment over
> to managed bots. Those logins cannot be approved, rejected or completed; they
> simply sit there until they pass their `sessionTtl` (5 minutes by default) and
> expire. Nothing is corrupted and no data is lost, but the users holding them
> have to start over.
>
> The clean cut-over is therefore to stop accepting new logins, let the in-flight
> ones drain or expire — one `sessionTtl` is the longest you can wait, since that
> is how long a live row stays useful — and only then start the tenant bots.
> Deleting or expiring the leftover live rows outright does the same job faster.

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

Three things to know before reaching for it:

- **Supplying a factory erases the name distinction.** The supplied factory is
  used as-is for *both* pools — it owns its threads' names and daemon status — so
  `tg-auth-poll-` and `tg-auth-work-` disappear from thread dumps. Operators lose
  the ability to tell a stuck poll from a stuck handler at a glance. That is a
  real diagnostic cost, not a cosmetic one. (The runtime could not re-flag those
  threads even if it wanted to: on a virtual thread `setDaemon(false)` throws
  outright, and `setDaemon(true)` is a no-op that only looks like it worked.)
- **The registry resolves the factory with `getIfAvailable()`, so declare at most
  one.** Two `ThreadFactory` beans in the context fail startup with
  `NoUniqueBeanDefinitionException: … expected single matching bean but found 2`.
  If your application already has one for unrelated work, mark one `@Primary` or
  keep the runtime on the built-in default.
- **One *unrelated* `ThreadFactory` bean is the case that actually bites**, and it
  is silent. `getIfAvailable()` cannot tell a factory meant for the bot pools from
  one you declared for a scheduler or a batch job: it finds the single candidate
  and adopts it for both pools of every tenant bot. There is no error, no warning,
  and nothing in the logs — the only symptom is that `tg-auth-poll-*` and
  `tg-auth-work-*` are simply not in the thread dump, and your bots are running on
  someone else's threads. There is no way to tell the runtime "use the built-in
  default anyway": if a `ThreadFactory` bean is visible, it wins. So if the one in
  your context was not meant for the bots, keep it out of the candidate pool —
  declare it as a narrower type, or qualify it behind your own configuration
  instead of exposing it as a bare `ThreadFactory` bean.

**The practical ceiling is untested.** No load test in this repository establishes
how many tenant bots one instance can carry, and threads are probably not the
binding constraint anyway: each bot holds a *simultaneous long-poll HTTP
connection* to Telegram from one address, and connection limits — your HTTP
client's pool, and Telegram's own tolerance for concurrent pollers from one IP —
will bite before thread count does. Measure it for your deployment; do not read a
number into this section, because there isn't one.

### `TenantBotEventBridge` takes over `ManagedBotEvents` — and forwards to yours

When the runtime is on, `TenantBotEventBridge` is the `ManagedBotEvents`
`ManagedBotService` is wired with: the white-label auto-configuration is ordered
before the managed-bots one, so its bridge wins the `@ConditionalOnMissingBean`
that would otherwise register a no-op, and the bridge is `@Primary` so it stays
the one candidate the service resolves to. That is what turns bot lifecycle into
runtime lifecycle — created starts, token-rotated restarts, decommissioned stops,
each failure swallowed and logged so one bad tenant cannot disturb the manager
bot or the others.

**You can still declare your own `ManagedBotEvents` bean.** It is not shadowed:
the bridge is handed every `ManagedBotEvents` in the context and hands every
callback on to yours — the four lifecycle ones it acts on (`onCreated`,
`onTokenRotated`, `onDecommissioned`, `onTokenFetchFailed`) and the four
[intent](#intents) ones it simply relays (`onIntentClaimed`, `onIntentMatched`,
`onIntentUnmatched`, `onIntentAmbiguous`). Two rules govern that forwarding:

- **The registry runs first.** Your hook is called *after* the tenant bot has
  been started, restarted or stopped, so a hook that throws cannot keep a tenant
  down.
- **Your exceptions are swallowed, exactly as the registry's are.** The bridge
  catches `Throwable` and logs a warning — this is the manager bot's update
  worker thread, and nothing on it may escape. Do not rely on an exception from
  your hook reaching anything; log or record what you need yourself.

The bridge is itself a `ManagedBotEvents` bean, so it filters itself out of that
forwarding list by identity — it never calls itself, and adding your own bean
costs you nothing.

Per-bot wiring still belongs in `ManagedBotCustomizer`; anything else you need at
start time you can do inside the `TenantBotFactory`, which runs on every start.

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
- [x] Managed-bot intents (opt-in): a created bot is matched to the request that asked for it by the creator's Telegram id, with host-driven manual resolution when matching is ambiguous.
- [x] Host-account linking: an opaque `hostRef` set server-side at session creation reaches the approve handler, so a host can tell a login from a link.
- [x] White-label tenant bots (opt-in): a long-poll runtime per managed bot, per-tenant rate limiting, startup restore, poll-failure budget. Single instance only.
- [ ] SSE & WebSocket transports.
- [ ] Redis-backed event bus + multi-instance horizontal scaling (would also make in-flight login state survive failover).

## Publishing

Published to Maven Central via the [Sonatype Central
Portal](https://central.sonatype.com). See [`PUBLISHING.md`](PUBLISHING.md) for
prerequisites and the release procedure.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
