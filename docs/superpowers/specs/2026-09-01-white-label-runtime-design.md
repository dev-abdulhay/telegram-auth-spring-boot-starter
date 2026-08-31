# White-label runtime — design

**Date:** 2026-09-01
**Branch:** `feat/white-label-runtime`, cut from `main`
**Status:** approved design, not yet implemented
**Builds on:** `docs/superpowers/specs/2026-08-31-managed-bots-design.md`

## Why

The managed-bots core creates a bot for a tenant, fetches its token and stores it
encrypted — and then nothing happens. Nobody talks to that bot. This spec is the
second half: starting each managed bot at runtime so a tenant gets a working,
branded login bot of its own.

It plugs into the two seams the core left for exactly this purpose:
`ManagedBotEvents` and `ManagedBotTokenStore#findAll`.

## Decisions taken before designing

These were settled with the project owner and constrain everything below:

- **Single application instance.** No lease, ownership or heartbeat machinery.
  Two instances polling one bot would collide (Telegram answers 409), so
  horizontal scaling is out of scope and must not be attempted without a
  follow-up design.
- **Per-bot service instances.** Each tenant bot gets its own `TelegramBotModule`,
  session service and auth flow, rather than one service taking the module as a
  parameter — the alternative would break the v0.4.0 public API.
- **Shared tables with a `bot_user_id` column**, not a table per tenant. Runtime
  DDL is not workable under the library's `@MappedSuperclass` model.
- **Library stays on Java 17**, with a `ThreadFactory` seam so a host on 21+ can
  supply virtual threads. Raising the floor to 21 would force every consumer up.
- **Expected scale:** dozens of bots initially, growing. The thread model is not
  a constraint at that size; the ceiling is documented rather than engineered
  around.

## Architecture

New package `io.github.dev_abdulhay.telegramauth.whitelabel`.

**Dependency direction:** `whitelabel → managedbots, flow, service, bot`. Nothing
depends on `whitelabel`. This is why the runtime is a third package rather than
part of `managedbots`: the runtime needs `DefaultAuthFlow` and
`AbstractSessionService`, and `managedbots` is deliberately forbidden from
importing `flow/` or `service/`. Keeping the runtime separate preserves that rule
instead of quietly breaking it.

| Component | Responsibility |
| --- | --- |
| `TenantBotFactory<U, S>` | **Host-implemented.** Builds a fully wired module for one managed bot |
| `TenantBotRegistry<U, S>` | Owns the live `botUserId → RunningBot` map; start/stop/restart |
| `RunningBot<U, S>` | Holds one bot's module, runner and services |
| `ManagedBotCustomizer` | Optional host hook to add non-auth handlers |
| `TenantBotLifecycle` | Restores bots on startup, stops them on shutdown |
| `TenantBotEventBridge` | Wires `ManagedBotEvents` to registry operations |
| `TelegramWhiteLabelAutoConfiguration` | Opt-in wiring |

### `TenantBotFactory` — the central interface

```java
public interface TenantBotFactory<U extends BaseTelegramUser, S extends BaseAuthSession> {
    RunningBot<U, S> create(ManagedBot bot, String decryptedToken);
}
```

The library **cannot** build the module itself: `DefaultAuthFlow<U, S>` and
`AbstractSessionService<U, S>` are generic over the host's own user and session
entities, which the library never sees. So the host supplies this factory, and
inside it constructs its session service, its auth flow, and returns them.

The factory returns a `RunningBot<U, S>` rather than a bare module because the
registry must hand the session service back to the host's REST layer later — see
`sessionServiceFor` below. Returning only the module would leave the registry
holding an untyped service it could not usefully expose. `RunningBot` therefore
carries the module and the session service, and the registry attaches the runner
once it starts one:

```java
public record RunningBot<U extends BaseTelegramUser, S extends BaseAuthSession>(
        TelegramBotModule module, AbstractSessionService<U, S> sessionService) { }
```

The generic parameters travel all the way out to the registry and its
auto-configured bean, so a host with one user type declares
`TenantBotRegistry<AppUser, AppSession>` and gets typed services back with no
casting.

The factory must set the bot id on the module — `builder(token, username)
.botUserId(bot.botUserId())` — because that is what makes tenant scoping work
(below).

### The prototype-bean requirement

**A tenant's services must be obtained as prototype-scoped Spring beans, never
with a plain `new`.** This is the single easiest thing to get wrong, and it fails
silently.

Services built with `new` are not Spring beans, so they get no AOP proxy, and
therefore:

- `@Transactional` on `approve` / `reject` / `awaitCode` / `create` does nothing.
  Each repository call runs in its own short transaction instead of one.
- `findWithLockByTokenHash`'s `PESSIMISTIC_WRITE` lock is released the moment the
  query returns, making the lock pointless — concurrent approve/reject stop
  serialising.
- `publishAfterCommit` falls through to its "no transaction active" branch and
  publishes immediately, losing the guarantee that no subscriber observes an
  event whose DB state was rolled back.

The same defect was found and fixed in `JpaManagedBotTokenStore` during the
managed-bots work; this spec refuses to reintroduce it one layer up.

So the host's factory resolves its services through an `ObjectProvider`
(prototype scope keeps the proxy) rather than constructing them directly. The
README must state this in the strongest terms available, because `new` compiles,
runs, passes a smoke test, and corrupts data only under concurrency.

### Sweeping

Tenant services must **not** each run the scheduled sweep. `@Scheduled` only
fires on singletons, so a prototype-scoped tenant service would never sweep
anyway — and if it did, N bots would mean N identical full-table queries.

`findByStatusInAndExpiresAtBefore` is already global across the session table, so
**one** Spring-managed sweeper covers every tenant. Tenant services override
`sweepExpired` to a no-op.

## Tenant scoping

`BaseAuthSession` gains a nullable `Long botUserId` mapped to `bot_user_id`.
Nullable is deliberate: rows written by existing hosts have no bot, and the
column must be additive so upgrading needs no backfill.

`TelegramBotModule` gains a nullable `botUserId` too, set by the builder. Static
modules leave it null; `TenantBotFactory` sets it. `AbstractSessionService.create`
copies it onto the session.

**Queries:**

- `findByTokenHash` is unchanged. Tokens are 32 random bytes and globally unique,
  so there is nothing to scope.
- Rate limiting becomes per-tenant: a flood against tenant A must not lock out
  tenant B. The existing `countByIpAddressAndStatusInAndExpiresAtAfter` stays as
  it is — changing its signature would break hosts — and a second derived query
  taking `botUserId` is added beside it. The service picks based on whether the
  module carries a bot id.
- The sweep query stays global, per the section above.

**REST routing** stays with the host. `AbstractTelegramAuthController` is bound to
one session service, and an incoming login must be routed to the right tenant's
service. How a request identifies its tenant — subdomain, header, path segment —
is the host's multi-tenancy scheme, and the library will not guess at it. The
library's contribution is
`Optional<AbstractSessionService<U, S>> sessionServiceFor(long botUserId)` on the
registry, typed through the registry's own generic parameters so the host needs no
cast; resolving the tenant is the host's job. It returns empty for a bot that is
not running, which the host surfaces as a 404 rather than a 500.

## Lifecycle

**Event bridge**, wiring `ManagedBotEvents` to the registry:

| Event | Action |
| --- | --- |
| `onCreated(bot)` | Decrypt the token, build the module, start the runner |
| `onTokenRotated(bot)` | Full restart with the new token |
| `onDecommissioned(id)` | Stop the runner, drop it from the registry |
| `onTokenFetchFailed` | Log only — there is nothing to start |

A rotation is a **full restart** because `TelegramBot` holds its token in a final
field. Two consequences, both of which the README must state:

- In-flight logins on that tenant are lost. `DefaultAuthFlow`'s `pendingLogins`
  map is JVM-local, so a user midway through the contact or code step starts over.
- The new runner begins at offset 0, so Telegram may redeliver updates the old
  runner had handled but not yet confirmed.

**Startup restore.** On `ApplicationReadyEvent`, `store.findAll()` and start each
bot. **One tenant's failure must never stop the others** — a decryption failure
(a rotated key), a factory exception, or a failed start is logged, that bot is
skipped, and the loop continues. Otherwise a single bad row leaves the whole
application with no bots at all.

**Token death.** When an owner revokes the token in BotFather, `getUpdates`
starts returning 401. Today the runner reads that as `maxId < 0`, backs off, and
retries **forever** — a dead bot holding two threads and sending one request per
second at Telegram with an invalid token, which is a good way to get an IP
flagged.

`TelegramBotRunner` therefore counts consecutive failures and, once a budget is
exhausted, stops itself and notifies a listener. Two details matter:

- The listener is a small `PollFailureListener` interface **in `bot/`**, because
  `bot/` must not know that `managedbots` or `whitelabel` exist. The runtime
  wires it to its own handling.
- The budget is measured in **time, not attempts**, with exponential backoff. A
  409 from a competing poller and an ordinary network blip arrive through the
  same path, so the budget must be generous — 5 minutes of unbroken failure by
  default. A stopped bot is announced through an event, never dropped silently.

## Threading

`TelegramBotRunner` accepts an optional `ThreadFactory`; the default is today's
named platform factory, so nothing changes for existing hosts. The registry
passes the configured factory to every tenant runner.

It cannot be a property — it is an object — so the host supplies an optional
`ThreadFactory` bean and the registry takes it via `ObjectProvider`, falling back
to the platform default. On Java 21+ the host writes:

```java
Thread.ofVirtual().name("tg-tenant-", 0).factory()
```

The library never references a Java 21 API; `ThreadFactory` has existed since 1.5.
The host on 21 gets virtual threads, the host on 17 gets platform threads, and the
library compiles to 17 either way.

**The ceiling, stated honestly.** With platform threads each bot costs two — one
polling, one working. At dozens of bots this is irrelevant. Several hundred is
survivable (blocked threads commit little stack), but the untested limit is more
likely the number of simultaneous long-poll HTTPS connections to
`api.telegram.org` from one address than the threads themselves. The README
documents this as measured-not-guaranteed and points at virtual threads as the
first lever.

## Configuration

New `@ConfigurationProperties("telegram.white-label")`:

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `false` | Gates the whole runtime |
| `restore-on-startup` | `true` | Start stored bots when the application is ready |
| `poll-failure-budget` | `5m` | Unbroken failure duration before a bot is stopped |

The auto-configuration requires a `TenantBotFactory` bean; without one it fails
fast with a message naming the interface, in the same spirit as the managed-bots
encryption-key rule.

## Extensibility

`ManagedBotCustomizer` is an optional host bean invoked **after** the auth flow
has registered its handlers:

```java
public interface ManagedBotCustomizer {
    void customize(TelegramBotModule module, ManagedBot bot);
}
```

A tenant's own commands, support inbox and notification logic go here. Ordering
matters: the auth flow claims its single-slot handlers first, so a customizer that
needs text handling when `codeConfirmation` is `TYPED` must route through
`fallback(...)` — the flow forwards everything it does not own there. The README
documents which slots the flow claims under which options.

## Testing

- Registry: start, stop, restart against a fake factory; starting twice is
  idempotent.
- Startup restore with three stored bots where one fails to decrypt — the other
  two must still start. This locks the "one bad tenant cannot kill the rest"
  guarantee.
- Event bridge: created starts a runner, decommissioned stops and deregisters,
  rotated yields a module carrying the new token.
- `bot_user_id` is written on session creation, and rate limiting is scoped per
  tenant (a flood against A leaves B able to log in).
- Poll-failure budget: a bot returning persistent non-ok stops once the budget
  expires and notifies the listener. The budget is configurable down to
  milliseconds so the test is not timing-fragile.
- **Integration test proving `@Transactional` actually applies** to a tenant
  service obtained through the prototype-bean path. This is the whole point of
  that requirement; without the test we would learn we had reintroduced the Task-4
  defect only in production.

## Out of scope

Multi-instance lease and ownership, webhook mode, an async poller, and sharing
users across tenants. Each needs its own design.
