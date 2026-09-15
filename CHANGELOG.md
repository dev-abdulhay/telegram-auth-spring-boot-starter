# Changelog

All notable changes to this project will be documented in this file.

The format is loosely based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.5.0] - 2026-09-15

Two features that compose. **Managed-bot intents** close the hole the 0.4.0
managed-bots feature left open: the `/newbot` deep link carries no payload of
the host's, so the only thing a host could correlate a created bot with was the
suggested username — which Telegram lets the user edit in the confirmation
dialog. When they do, the bot arrives orphaned and nothing can repair it
automatically. An intent records the request *before* the link is handed out,
the user proves their Telegram id by opening that link on the manager bot, and
the created bot is matched back by that id instead. **Host-account linking**
carries one opaque `hostRef` from session creation into the approve handler, so
a host can finally tell "someone is logging in" from "admin 7 is linking their
Telegram" — and the bot-creation flow is what links an admin's Telegram in the
first place.

Everything here is additive except one new nullable column on the session table,
which every host must add. See **Migration** below.

### Added
- **Managed-bot intents** (`io.github.dev_abdulhay.telegramauth.managedbots`), an
  opt-in layer on top of managed bots: declare a `ManagedBotIntentStore` bean and
  it turns on; leave it out and nothing changes. `ManagedBotIntent` (a record
  carrying `id`, `suggestedUsername`, `suggestedName`, `hostRef`, `ownerUserId`,
  `status`, `botUserId` and four `OffsetDateTime` timestamps, plus the
  `START_PREFIX = "mb_"` constant), `ManagedBotIntentStatus`
  (`OPEN`/`CLAIMED`/`COMPLETED`/`EXPIRED`/`CANCELLED`) and `ManagedBotIntentLink`
  (`intentId`, `url`, `expiresAt`).
- `ManagedBotIntentStore` with `save` (upsert by id), `findById`,
  `findByBotUserId`, `findClaimedByOwner` and `deleteClosedBefore(cutoff)`, plus
  two implementations: `InMemoryManagedBotIntentStore` (map-backed, not durable)
  and `JpaManagedBotIntentStore<I extends BaseManagedBotIntent>`, paired with the
  `@MappedSuperclass BaseManagedBotIntent` and
  `BaseManagedBotIntentRepository<I>` a host subclasses with its own entity. As
  with `managed_bot`, the library registers no store bean and creates no table —
  the host owns both. The id is **assigned, not generated**: it is minted by
  `ManagedBotService`, travels in a `/start` payload and is looked up by that
  value.
- `ManagedBotService` intent methods: `createIntent(suggestedUsername,
  suggestedName, hostRef)` returning the `ManagedBotIntentLink` whose `url` is
  `https://t.me/<manager>?start=mb_<intentId>`; `findIntent(intentId)` (reporting
  and persisting an overdue `OPEN` row as `EXPIRED`); `claimIntent(intentId,
  ownerUserId)` returning an `IntentClaimResult`, public because a host that
  replaced `/start` with its own handler still needs the decision;
  `cancelIntent(intentId)`; and the three manual-resolution methods
  `findUnassignedBots(intentId)`, `assignToIntent(intentId, botUserId)` and
  `decommissionUnassigned(botUserId)`.
- A nine-argument `ManagedBotService` constructor — `(module, store, encryptor,
  events, tokenFetchRetries, tokenFetchBackoff, ManagedBotIntentStore, Duration
  intentTtl, Duration intentRetention)`. The 0.4.0 six-argument constructor is
  unchanged and delegates to it with a `null` store, which turns intents off
  completely.
- `IntentClaim` (`CLAIMED`, `RECLAIMED`, `COMPLETED`, `OTHER_OWNER`, `CLOSED`,
  `UNKNOWN`) and `IntentClaimResult(outcome, intent)`; and
  `ManagedBotIntentException` with a `Reason` enum — `INTENT_NOT_FOUND`,
  `INTENT_NOT_CLAIMED`, `INTENT_CLOSED`, `BOT_NOT_FOUND`, `OWNER_MISMATCH`,
  `BOT_ALREADY_ASSIGNED` — for runtime outcomes a host UI branches on.
  Misconfiguration stays an `IllegalStateException` (no intent store bean, the
  same way a missing encryption key is) and an unusable suggested username stays
  an `IllegalArgumentException`, validated eagerly at `createIntent` with
  `ManagedBotLink`'s rules so it never surfaces on the update worker thread.
- **Automatic matching**, run on creation (from `handleUpdate` and from a
  `fetchAndStore` recovery — never on a rotation, never for an echo of the
  library's own token change) and once more on the first claim, because the bot
  may already exist by then. Candidates are the creator's `CLAIMED` intents,
  filtered to `bot.createdAt() >= intent.createdAt()` so a bot created for some
  other purpose before the intent existed is never auto-matched; a single
  username hit wins, else a single candidate wins, else nothing is linked and the
  host is asked. The match is persisted **before** any event is published.
- Four `ManagedBotEvents` hooks, all `default` no-ops:
  `onIntentClaimed(intent)`, `onIntentMatched(bot, intent)`,
  `onIntentUnmatched(bot, candidates)` (one bot, several intents — the creation
  path) and `onIntentAmbiguous(intent, candidates)` (one intent, several bots —
  the claim path). `TenantBotEventBridge` forwards all four to host-declared
  `ManagedBotEvents` beans with the same self-filtering and swallow-and-log it
  already applies, so white-label hosts see intents too.
- `TelegramBotModule#startPayload(String prefix, Predicate<JsonNode> handler)`
  and `getStartPayloadRoutes()`, a second registry consulted by
  `BotUpdateDispatcher` after it resolves `/start` and before the command
  registry, longest matching prefix first. The prefix is limited to Telegram's
  start-payload alphabet `[A-Za-z0-9_-]` (`IllegalArgumentException` otherwise)
  and a second handler for the same prefix is an `IllegalStateException`. **The
  handler returns whether it owned the update**: `false` falls through to the
  normal `/start` handler, which is what keeps a login token that happens to
  begin with `mb_` — Base64URL, so roughly one in `64³` — logging in normally
  instead of being answered "link invalid" and dropped. The composed handler runs
  on the update worker thread, never on the polling thread. `command("/start",
  …)` is untouched and `DefaultAuthFlow` did not change.
- `ManagedBotIntentFlow`, the claim handler: self-registering like
  `DefaultAuthFlow`, auto-configured as a `@ConditionalOnBean(ManagedBotIntentStore.class)`
  bean, replying with an inline URL button that opens `ManagedBotLink.build(...)`.
  Overridable `onStart(JsonNode)` and `msg(FlowMessages.Key, String lang)`. It
  mirrors `DefaultAuthFlow`'s private-chat rule exactly, so an intent can never be
  claimed from a group.
- Four `FlowMessages.Key` entries in uz/ru/en — `INTENT_PROMPT`,
  `BTN_CREATE_BOT`, `INTENT_OTHER_OWNER`, `INTENT_ALREADY_DONE`. An expired or
  cancelled link reuses the existing `INVALID_LINK`.
- `telegram.managed-bots.intent-ttl` (default `30m`, the lifetime of an `OPEN`
  intent — a `CLAIMED` one never expires, because its owner is proven and the
  resolution screen needs it to survive) and
  `telegram.managed-bots.intent-retention` (default `7d`). There is no scheduler:
  expiry is evaluated lazily on read, and the retention purge runs from
  `createIntent`, throttled to at most once a minute per JVM.
- `ManagedBotLink.validateUsername(String)` is now public, so the same three
  rules validate a suggestion at `createIntent` and at link build time.
- **Host-account linking.** `BaseAuthSession` gained `getHostRef()` /
  `setHostRef(String)` backed by a new nullable `host_ref VARCHAR(128)` column;
  `AbstractSessionService.create(ipAddress, userAgent, hostRef)` is a new
  overload (`IllegalArgumentException` beyond 128 characters) and the two-argument
  `create` delegates to it with `null`; `AuthContext` gained a three-argument
  constructor and `getHostRef()`, and `approve(...)` passes the stored value
  through. Nothing in the library interprets it — no notion of "admin", no
  account merging — it is opaque exactly like the intent's own `hostRef`.
- `AbstractTelegramAuthController#hostRef(HttpServletRequest)`, a `protected`
  override point returning `null` by default and consulted by the stock
  `POST /session`. **`hostRef` is server-side only**: `CreateSessionRequest` has
  no field for it and the endpoint never reads one from the body, because a
  client that could set it could mint `link:admin:7` and attach its own Telegram
  account to somebody else's.

### Changed
- **Managed-bot event publishing is now guarded per listener.** A
  `ManagedBotEvents` callback that throws — `onCreated`, `onTokenRotated`,
  `onTokenFetchFailed`, `onDecommissioned` or any of the four new intent hooks —
  is logged at `WARN` and the next event still fires, instead of escaping
  `ManagedBotService`. Previously such a throw aborted the rest of the work on
  the update path — where the dispatcher caught and logged it — and reached the
  host's own call site when the host had called `rotateToken`, `decommission` or
  `fetchAndStore` directly. The guard is what makes "matching state is persisted,
  then events are published" a promise rather than a hope, and it brings the
  direct path in line with the white-label bridge, which has always guarded every
  callback. Hosts that relied on a throw to abort anything must record the
  failure themselves.
- **Three more intent-store call sites are now guarded**, all in paths a 0.4.0
  host already depended on with no intent store involved at all: the auto-match
  run right after a bot is stored (`ManagedBotService#handleUpdate`), the
  retention purge inside `createIntent`, and the claim-time match inside
  `claimIntent`. A throwing store is caught and logged at `WARN` instead of
  escaping — otherwise a store outage would cost the bot its `onCreated` event
  (with no update ever re-delivered to try again), fail `createIntent` over
  housekeeping nobody is waiting on, or leave the user who tapped an intent link
  with no reply at all.
- `assignToIntent`'s failure-translation catch no longer loses the original save
  exception when the concurrent-winner probe it runs afterwards also fails. The
  probe failure is attached to it as a suppressed exception instead.
- `AuthContext`'s javadoc no longer refers to an `AuthContextEnricher` type. No
  such type exists anywhere in the repository and none ever did; the class now
  describes what it actually carries.

### Removed
- **`messages_tgauth.properties`, `messages_tgauth_ru.properties` and
  `messages_tgauth_en.properties`.** All three were dead: there is no
  `MessageSource` anywhere in this library, no class ever read them, and Spring's
  default basename is `messages`, not `messages_tgauth` — so a host that
  "overrode" one changed nothing. Bot texts live in `FlowMessages`, a hard-coded
  uz/ru/en table keyed by `FlowMessages.Key`, and are customised by overriding
  `DefaultAuthFlow#msg(Key, String)` (or `ManagedBotIntentFlow#msg(Key, String)`).
  Deleting them removes a documented path that never worked.

### Migration
- **Required, for every host, whether or not it uses linking:**

  ```sql
  ALTER TABLE auth_session ADD COLUMN host_ref VARCHAR(128);
  ```

  once per session table (`admin_tg_session`, `customer_tg_session`, …). The
  column is new and nullable, so there is nothing to backfill, but
  `BaseAuthSession` now maps the field and Hibernate schema validation fails the
  application context at startup without it. This is the only change 0.5.0 forces
  on an existing table.
- **Optional, only if you adopt intents:** a new `managed_bot_intent` table,
  created by your own migration like `managed_bot` before it. The PostgreSQL DDL
  — including the `(owner_user_id, status)` and `(status, expires_at)` indexes and
  the partial unique index on `bot_user_id`, which is what makes two concurrent
  assignments of one bot resolve as `BOT_ALREADY_ASSIGNED` — is in the README's
  Intents section.
- No other action is needed. The new `ManagedBotEvents` methods are `default`, the
  new `ManagedBotService` constructor and `create` / `AuthContext` signatures are
  overloads, and every 0.4.0 signature still compiles.

### Documentation
- README: a new `### Intents` subsection under `## Managed bots` (the problem,
  the store and DDL the host provides, the flow, the matching rules, the four
  events, the `/start` routing and why its handler returns a `boolean`, and the
  resolution screen **with its owner-scoped caveat** — `findUnassignedBots`
  returns every unassigned bot of that Telegram user, including ones created for
  something else entirely, so hosts must gate the screen behind their own
  permission check); a new `## Linking a Telegram account to a host account`
  section; `## Upgrading to 0.5.0`; the two new properties in the managed-bots
  configuration table; install snippets at `0.5.0`; roadmap ticks.
- README: `assignToIntent` now notes it should be called outside the host's own
  transaction, since the store flushes to let the unique `bot_user_id` index
  arbitrate a concurrent assignment. The `/start` routing section no longer
  claims an unclaimed payload with no `/start` handler logs anything (it
  doesn't) or behaves "as before" (a matched start-payload route now consumes
  the update and returns, instead of falling through to the text handler).
- `tasks/tech-doc/TECH_DOC.md` §9.2 no longer claims bot texts are
  `messages_tgauth*.properties` resolved through Spring's `MessageSource`, and
  its component map lists the managed-bots and intent types.

## [0.4.0] - 2026-09-10

This release folds three previously separate efforts into 0.4.0: **managed
bots**, an opt-in feature that lets a manager bot create tenant bots and hold
custody of their tokens; the **white-label tenant runtime** built on top of
managed bots, giving each tenant its own branded bot end to end; and the
**login-confirmation hardening** that moves a login from "someone tapped a
link" to "someone is looking at the browser that started this login". It also
drops the unused `liquibase-core` and `caffeine` dependencies, and fixes a
string of issues found while stabilizing managed bots and white-label after
they landed.

### Added
- **Managed bots** (`io.github.dev_abdulhay.telegramauth.managedbots`), a
  separate, opt-in feature independent of the auth flow: a manager bot can
  create bots on a user's behalf and keep custody of their tokens.
- `ManagedBotService`: `createLink(suggestedUsername, suggestedName)` builds
  the `/newbot` deep link; `findToken(botUserId)` reads the decrypted token
  locally; `rotateToken(botUserId)` revokes and re-stores it;
  `getAccessSettings(botUserId)` / `setAccessSettings(botUserId, restricted,
  addedUserIds)` read/write who besides the owner may use the bot;
  `decommission(botUserId)` revokes the token and forgets the bot locally
  (Telegram has no method to delete a managed bot, so the bot itself keeps
  existing under the owner's account); `handleUpdate(update)` processes one
  `managed_bot` update, fetching the token with retry/backoff before
  publishing it;
  `fetchAndStore(botUserId, ownerUserId)` does the same work without an
  update — the recovery entry point for a bot that exists on Telegram with no
  token stored here (retries exhausted, or the process died after the poller
  advanced Telegram's offset). It throws instead of re-publishing
  `onTokenFetchFailed`, so recovering from inside that callback cannot loop.
- `TelegramBot.DEFAULT_MAX_RATE_LIMIT_WAIT` (60s) and a
  `TelegramBot(HttpClient, token, baseUrl, maxRateLimitWait)` constructor: the
  longest `retry_after` a bot will sit out in-line. The wait runs on the
  module's single update worker, so beyond the budget the call now throws
  `TelegramApiException` instead of stalling every other update — including
  logins — for minutes. Previously hardcoded at 60s with no way to lower it.
- `ManagedBotTokenStore` contract with two implementations:
  `InMemoryManagedBotStore` (map-backed, non-durable) and
  `JpaManagedBotTokenStore<M extends BaseManagedBot>`, paired with the
  `@MappedSuperclass BaseManagedBot` and `BaseManagedBotRepository<M>` a host
  subclasses with its own entity — the auto-configuration does not register a
  store bean, since only the host knows its entity type.
- `TokenEncryptor` / default `AesGcmTokenEncryptor`: AES-256-GCM with a fresh
  random IV per write, stored as `Base64(IV || ciphertext || tag)`. Tokens are
  never logged and are masked in `toString` (`ManagedBot`, `BaseManagedBot`).
  Declaring a host `TokenEncryptor` bean (e.g. backed by a KMS or vault)
  replaces the default and then no encryption key property is needed.
- `ManagedBotEvents` lifecycle hooks — `onCreated`, `onTokenRotated`,
  `onTokenFetchFailed`, `onDecommissioned` — each with a no-op default.
- `TelegramBotModule#onManagedBot(Consumer<JsonNode>)`, a single-slot handler
  for `managed_bot` updates with the same replace-guard as
  `onCallbackQuery`/`onContact`/`onText`.
- Four `TelegramBot` managed-bot API methods: `getManagedBotToken`,
  `replaceManagedBotToken`, `getManagedBotAccessSettings`,
  `setManagedBotAccessSettings`; plus `TelegramApiException` (the API's
  `ok:false` / unrecoverable-status failure) and a `getUpdates(offset,
  timeoutSeconds, allowedUpdates)` overload.
- `telegram.managed-bots.*` properties, wired by
  `TelegramManagedBotsAutoConfiguration` when
  `telegram.managed-bots.enabled=true`: `encryption-key` (required unless a
  custom `TokenEncryptor` bean is supplied), `token-fetch-retries` (default
  3), `token-fetch-backoff` (default 1s, doubling per retry).
- **Behaviour change once managed bots are enabled on a module:** its poller
  now sends Telegram an explicit `allowed_updates` list
  (`["message", "callback_query", "managed_bot"]`), because Telegram's own
  default list excludes `managed_bot`. A host relying on the default list to
  observe other update types (e.g. `my_chat_member`) through
  `module.fallback(...)` will stop receiving them on that module. The
  library's own auth flow is unaffected — it consumes only `message` and
  `callback_query`.
- **White-label tenant bots**
  (`io.github.dev_abdulhay.telegramauth.whitelabel`), a second opt-in layer on
  top of managed bots: every stored `ManagedBot` gets its own long-poll runner,
  `TelegramBotModule` and session service, so each tenant authenticates its
  users through its own branded bot. Requires
  `telegram.managed-bots.enabled=true` as well. **Single instance only** — two
  application instances polling one bot collide with Telegram's `409`, and
  nothing here attempts leasing or ownership.
- `TenantBotFactory<U, S>`: `create(ManagedBot bot, String decryptedToken)`
  returning `RunningBot<U, S>`. The host implements it — the library cannot,
  because `AbstractSessionService` and `DefaultAuthFlow` are generic over the
  host's own entity types. The context fails to start with
  `IllegalStateException` when the runtime is enabled without this bean. The
  session service it returns must be a **container-managed, prototype-scoped
  bean that receives the module as a construction argument**: `new` loses the
  AOP proxy (so `@Transactional`, the `PESSIMISTIC_WRITE` lock and
  `publishAfterCommit` all silently stop working), a singleton would freeze the
  first tenant's module for every later tenant, and a prototype that autowires
  its module by type would get the manager module instead of its own.
- `RunningBot<U, S>(TelegramBotModule module, AbstractSessionService<U, S>
  sessionService)`: the record a factory returns, carrying the service as well
  as the module so the registry can hand it back to the host's REST layer.
- `TenantBotRegistry<U, S>`: `start(ManagedBot)`, `stop(botUserId)`,
  `restart(ManagedBot)`, `sessionServiceFor(botUserId)` (empty for any bot not
  currently polling), `running()` and `stopAll()`. JVM-local, and safe against
  concurrent or re-delivered starts for the same bot id.
- `TenantBotEventBridge<U, S>`: the `ManagedBotEvents` implementation that turns
  bot lifecycle into runtime lifecycle — created starts, token-rotated
  restarts, decommissioned stops — swallowing and logging each failure so one
  bad tenant cannot disturb the manager bot or the others. When the runtime is
  on the bridge is the `@Primary` `ManagedBotEvents` bean, so a host declaring
  its own does not replace it — the bridge forwards every callback on to the
  host's bean after the registry work. Per-bot wiring still belongs in
  `ManagedBotCustomizer`.
- `TenantBotLifecycle<U, S>`: starts every stored bot on `ApplicationReadyEvent`
  (each independently, so one unusable row costs only that tenant) and stops
  them all on `@PreDestroy`.
- `ManagedBotCustomizer`: `customize(TelegramBotModule module, ManagedBot bot)`,
  the hook for a tenant's own commands. It runs *after* the auth flow has
  claimed its single-slot handlers (`onCallbackQuery` under `requireApproval` or
  any `codeConfirmation` other than `OFF`, `onContact` under `requireContact`,
  `onText` under `TYPED`), so anything colliding routes through `fallback(...)`.
- `PollFailureListener` (in the `bot` package):
  `onPollFailure(TelegramBotModule module, Duration failingFor)`, notified once
  after the runner has stopped polling and released both pools.
- Two `TelegramBotRunner` constructors: `(module, ThreadFactory)` and
  `(module, ThreadFactory, Duration failureBudget, PollFailureListener)`. The
  thread factory is the Java 21+ virtual-thread seam — the library stays on
  Java 17 and never references a virtual-thread API — and a supplied factory is
  used as-is for both pools, which erases the `tg-auth-poll-` /
  `tg-auth-work-` name distinction in thread dumps. A `null` failure budget
  keeps retrying forever, the behaviour every pre-white-label host has today.
- `TelegramBotModule.Builder#botUserId(Long)` and `getBotUserId()`: the tenant a
  module belongs to. A white-label factory must set it.
- `BaseAuthSession#getBotUserId()` / `setBotUserId(Long)`, backed by a new
  nullable `bot_user_id` column.
- `BaseAuthSessionRepository#countByIpAddressAndBotUserIdAndStatusInAndExpiresAtAfter(...)`,
  the per-tenant rate-limit count.
- `telegram.white-label.*` properties, wired by
  `TelegramWhiteLabelAutoConfiguration` when
  `telegram.white-label.enabled=true` (default `false`):
  `restore-on-startup` (default `true`) and `poll-failure-budget` (default
  `5m`) — how long a tenant bot may fail to poll continuously before it is
  stopped and deregistered, measured in time rather than attempts so a brief
  outage cannot kill a healthy bot. A poll failure is **not** proof of a
  revoked token: an unparseable payload and a `409` from a competing poller
  reach the same path.
- **Number matching** (`DefaultAuthFlow.Options.codeConfirmation`, default
  `BUTTON`): the browser shows a 2-digit code and the bot asks for it, either as
  an inline keyboard of candidates (`BUTTON`) or as typed text (`TYPED`); `OFF`
  restores the 0.3.x behaviour. A wrong answer never invites a retry — `BUTTON`
  ends the login on the first miss, `TYPED` allows three of a hundred
  candidates — and every wrong answer is logged at `WARN`.
- New non-terminal session status `AWAITING_CODE`, with
  `AbstractSessionService.awaitCode(tokenHash)` performing the
  `PENDING → AWAITING_CODE` transition. It does **not** call the host
  `approveHandler`, which stays reserved for the final approval.
- **Per-user cooldown** after a login dies at the code step
  (`codeCooldown` 5 min, doubling up to `codeCooldownMax` 1 h once
  `codeCooldownThreshold` — default 1 — failed logins accumulate; a successful
  login clears the ladder). Rejecting the session alone is no obstacle: the
  attacker simply opens another one. All attempts of a single `TYPED` login
  count as one strike; ❌ is never blocked by a cooldown.
- `ConfirmCodeGenerator` + default `ConfirmCode` (first two bytes of the token
  hash, modulo 100), pluggable via `TelegramBotModule.Builder#confirmCodeGenerator`.
  The code is derived, never stored — no new column, no migration.
- `TelegramBotModule#onText(Consumer<JsonNode>)`, a single-slot handler for text
  updates that matched no command. `BotUpdateDispatcher` routing order is now
  `callback_query` → commands → `contact` → `text` → `fallback`; an
  unregistered `/command` reaches the text handler, and `DefaultAuthFlow`
  forwards everything it does not own to the module fallback.
- `GET /session/{token}/poll?since=` — `since=PENDING` opts into the code step
  and answers `202 { status:"AWAITING_CODE", confirmCode }`;
  `since=AWAITING_CODE` waits for a terminal state, which is what stops a client
  from busy-looping on the state it is already in. Omitting `since` keeps the
  0.3.x terminal-only contract, answering a mid-poll code transition with `204`.
- Flow options are bindable from `telegram.auth.flow.*`, with optional per-type
  overrides under `telegram.auth.flows.<name>.*` falling back to `flow` and then
  to the built-in defaults. The starter auto-configures a
  `DefaultAuthFlow.Options` bean; declaring your own replaces it.
- `Options.codeButtons` (3–10, default 3), `maxCodeAttempts` (0 = per-mode
  default), `effectiveMaxCodeAttempts()`, and overridable
  `DefaultAuthFlow#codeChoices(int, int)` / `#formatCode(int)` /
  `#sessionDetails(S, String)`.
- `FlowMessages` keys `CONFIRM_WARNING`, `CONFIRM_STEP_DONE`,
  `CODE_PROMPT_BUTTON`, `CODE_PROMPT_TYPED`, `CODE_WRONG`, `CODE_NOT_A_NUMBER`,
  `CODE_ATTEMPTS_EXHAUSTED`, `TOO_MANY_ATTEMPTS` — all in uz/ru/en. Every
  confirmation question now ends with a warning that nobody should ever ask the
  user to tap ✅.

### Changed
- Sessions created through a module that carries a `botUserId` are now
  rate-limited **per tenant** instead of across the whole session table: a flood
  against one tenant no longer consumes another tenant's `maxPendingPerIp`
  quota. A statically configured module has no bot id and keeps counting
  table-wide, so nothing changes for hosts that do not use white-label.
- `bot_user_id` on the session table is a **new nullable column — additive, no
  backfill**. Existing rows and every session created by a statically
  configured module leave it `NULL`. White-label hosts should index it
  alongside `ip_address` (`ip_address,bot_user_id,status` rather than
  `ip_address,status`), since the per-tenant count filters on
  `(ip_address, bot_user_id, status, expires_at)`.
- **Session lookup is now tenant-scoped too.** When the module carries a
  `botUserId`, `AbstractSessionService`'s `findByRawToken`, `approve`,
  `awaitCode` and `reject` resolve the row by `token_hash` **and**
  `bot_user_id`. A module without a bot id keeps the original unscoped queries
  unchanged, so nothing changes for a statically configured host. Two derived
  finders back this: `BaseAuthSessionRepository.findByTokenHashAndBotUserId`
  and `findWithLockByTokenHashAndBotUserId` (the locked variant carries the
  same `PESSIMISTIC_WRITE`). Hosts that implement the repository by hand rather
  than letting Spring Data derive it must add both.
- `TelegramBotRunner.start()` returns `boolean` instead of `void`: `true` when
  the poll loop was submitted, `false` for a blank token or a runner that was
  already running. Source-compatible for callers that ignore the result.
- `TenantBotRegistry.stop` logs at WARN when it finds only an unpublished
  reservation and drops the stop. The behaviour is unchanged and deliberate,
  but a dropped stop during a token rotation leaves the bot polling a revoked
  token until the failure budget expires, and that was previously invisible.
- `TenantBotRegistry.stopAll` makes a second pass over the live ids, so a bot
  whose `start()` was still mid-flight during the first pass does not keep
  polling after the registry has reported everything stopped.
- The build now sets `maven.compiler.release=17` alongside source/target, so
  the Java 17 floor is enforced by the compiler's platform API set rather than
  by whichever JDK happens to run the build.
- **Registration moved to the last confirmation.** The user row was created when
  ✅ was pressed; with a code step configured, ✅ only unlocks the number
  question, so a login phished or abandoned at the code step no longer leaves an
  `ACTIVE` account behind.
- `AWAITING_CODE` sessions hold their per-IP rate-limit slot and are swept to
  `EXPIRED` alongside `PENDING`. Counting only `PENDING` would have let an
  attacker park sessions at the code step to bypass `maxPendingPerIp`, and
  sweeping only `PENDING` would have left half-finished logins alive forever.
  `TERMINAL_STATUSES` is deliberately unchanged — it drives the retention purge,
  which must never delete a live session.
- `AuthEventBus` no longer promises "terminal events only": dispatch still
  removes the listener, so one subscription observes exactly one event, but
  `AWAITING_CODE` is non-terminal and the client re-subscribes on its next poll.
  `InMemoryAuthEventBus` behaviour is unchanged.
- `DELETE /session/{token}` now also cancels a session sitting at
  `AWAITING_CODE`.
- `sessionTtl` default `3m` → `5m`; the contact and code steps share that window.

### Removed
- **`liquibase-core` and `caffeine` are no longer dependencies of the
  starter.** Neither was referenced anywhere in the library's own code — the
  starter ships no Liquibase changelog and never did (hosts own their own
  schema; see the README) — so both were dead weight on every consumer's
  classpath. Their only real effect was a footgun: because JPA is mandatory
  for this starter, every host has a `DataSource`, which made Spring Boot's
  `LiquibaseAutoConfiguration` activate automatically and then fail startup
  looking for a changelog that does not exist, **unless the host explicitly
  set `spring.liquibase.enabled=false`.** That requirement was never
  documented. Hosts that were setting it may now remove it; hosts that were
  not need do nothing differently, since removing the dependency removes the
  failure along with it.

### Fixed
- **Re-authorising a bot right after decommissioning it is no longer swallowed.**
  `decommission` registered a *blanket* echo guard, suppressing every
  `managed_bot` update for that bot for five minutes rather than the single echo
  its own token revocation produces. An owner who re-created the same bot inside
  that window got nothing: no row, no `onCreated`, and — under the white-label
  runtime — no tenant bot, until the window expired. The guard is now one-shot,
  matching `rotateToken`.
- **A host's own `ManagedBotEvents` bean no longer collides with the white-label
  event bridge.** `TenantBotEventBridge` was registered under a
  `@ConditionalOnMissingBean` resolving against its own type, so a host that
  declared a `ManagedBotEvents` bean ended up with two candidates and a context
  that failed with `NoUniqueBeanDefinitionException`. The bridge is `@Primary`
  now, and forwards all four callbacks to any host-declared `ManagedBotEvents`
  **after** doing its registry work — so a host hook cannot keep a tenant from
  starting, and its exceptions are swallowed and logged exactly as the
  registry's are. The bridge filters itself out of that forwarding, so it never
  calls itself. Previously documented as an unavoidable startup failure; it is
  an extension point.
- **`TenantBotEventBridge`'s self-filter is now proxy-safe.** It picked itself
  out of the forwarding candidates with `!= this`, which is correct in a stock
  context — the bridge is a plain bean with no `@Transactional`/`@Async` and
  nothing proxies it — but a host with a broad auto-proxy creator or aspect
  whose pointcut matches library classes could get a JDK or CGLIB proxy of the
  bridge among the candidates instead. `!= this` did not recognise that proxy
  as itself, so the bridge forwarded into it, the proxy delegated straight
  back, and it recursed until `StackOverflowError` — caught by the existing
  guard, but only after a log storm and thousands of `registry.start` calls.
  The comparison now unwraps Spring AOP proxies first.
- **A tenant bot with a blank stored token no longer looks healthy.** An empty
  decrypt yields `Optional.of("")`, which passed the registry's no-token check;
  the runner then declined to poll and returned, while `TenantBotRegistry.start`
  published the bot as started. It appeared in `running()`, handed out a session
  service through `sessionServiceFor`, and accrued no poll failures — so the
  failure budget never fired either. A permanently dead tenant that every health
  check reported as healthy. `start()` now throws and releases its reservation.
- **One tenant can no longer complete another tenant's login.** `create` wrote
  `bot_user_id`, but every lookup queried by token hash alone, so
  `registry.sessionServiceFor(B).approve(tokenMintedByA)` succeeded and
  published the terminal event on **B's** `AuthEventBus` — tenant A's browser
  waited forever while the row read `APPROVED`. Not privilege escalation (the
  token is a secret and the Telegram identity is genuine), but it is the tenant
  isolation this feature advertises.
- **The singleton mis-wiring of `TenantBotFactory` is now detected.** A host
  returning a singleton session service serves every tenant after the first the
  *first* tenant's module — a silent cross-tenant token, event-bus and
  rate-limit leak. `TenantBotRegistry.start` now compares the returned session
  service and module by identity against the live entries and throws
  `IllegalStateException` naming the prototype-scope requirement.
- `TelegramWhiteLabelAutoConfiguration` fails with a message naming the missing
  switch when `telegram.white-label.enabled=true` but `telegram.managed-bots`
  is off. It previously surfaced a raw
  `NoSuchBeanDefinitionException: ManagedBotTokenStore` for a type the host
  never asked for, naming neither property.
- `ManagedBotService.decommission(botUserId)` no longer resurrects the bot it
  just decommissioned. Revoking a token *is* a token change, so Telegram
  echoed it back as a `managed_bot` update; that update found an unknown bot,
  fetched the brand-new working token, re-created the row and fired
  `onCreated` — making the documented "left unreachable by us" promise false.
  The service now keeps a bounded, 5-minute, JVM-local record of the token
  changes it initiated and drops their echoes.
- **A failed revocation no longer leaves the echo guard armed with nothing to
  disarm it.** `decommission` arms the guard before calling
  `replaceManagedBotToken`, because the echo can be in flight before the call
  returns — but when that call itself throws (the owner already deleted the
  bot in BotFather), no echo is ever coming. The guard used to sit armed for
  the rest of its five-minute window regardless, silently swallowing the
  owner's next genuine `managed_bot` update for that bot — for example,
  re-creating the same bot fired no `onCreated` and stored no row. A failed
  revocation now disarms the guard immediately.
- `ManagedBotService.rotateToken(botUserId)` publishes `onTokenRotated` once
  instead of twice, via the same guard. The rotation suppression is one-shot,
  so a genuinely owner-initiated rotation arriving later is still announced.
- `setManagedBotAccessSettings` / `ManagedBotService.setAccessSettings` can
  now clear an allow-list. An empty `addedUserIds` list omitted the parameter
  entirely, and Telegram then kept the previous list — an access revocation
  that silently did nothing. An empty list now transmits `added_user_ids=[]`;
  only `null` omits the parameter.
- The poller's `allowed_updates` list is recomputed on every long-poll
  iteration instead of once before the loop. A host whose polling started
  before the auto-configured `ManagedBotUpdateHandler` singleton was built
  pinned the list to `null` forever, so `managed_bot` was never requested —
  no error, no log, the feature silently dead.
- `JpaManagedBotTokenStore`'s `Supplier<M> factory` contract is documented: it
  must return a blank, unsaved entity, because `save` uses
  `getBotUserId() == null` as its is-new test.
- The confirmation-code guess re-checks the user's `BLOCKED` status. The entry
  checks run before the code question is asked — and the `TYPED` text path had
  no entry check at all — so a user blocked *mid-flow* (while the code question
  was on screen) could still complete the login. `handleGuess` now answers
  `ACCESS_DENIED` for a blocked user in both `BUTTON` and `TYPED` modes.

### Documentation
- The README install snippets now name `0.4.0`, and the note warning that
  `main` was ahead of the latest published release is gone: 0.4.0 is on Maven
  Central, so the documented features are the released ones.
- `examples/basic-auth` no longer requires a local `mvn install` first — it
  resolves the starter from Maven Central like any other host would. Installing
  locally is now only needed when testing the example against uncommitted
  library changes.

### Breaking
- `codeConfirmation` defaults to `BUTTON`, so `Options.defaults()` and the
  3-argument `DefaultAuthFlow` constructor change behaviour. Opt out with
  `.codeConfirmation(CodeConfirmation.OFF)` or
  `telegram.auth.flow.code-confirmation: OFF`.
- `WaitResponse` is now `(String status, Map payload, Integer confirmCode)`. The
  2-argument constructor remains and `confirmCode` is omitted from the JSON when
  null, so existing clients are unaffected.
- `BaseAuthSessionRepository.findByStatusAndExpiresAtBefore` →
  `findByStatusInAndExpiresAtBefore(Collection<Status>, OffsetDateTime)`, and
  `countByIpAddressAndStatusAndExpiresAtAfter` →
  `countByIpAddressAndStatusInAndExpiresAtAfter(String, Collection<Status>, OffsetDateTime)`.
- `AuthEvent.Type` gained `AWAITING_CODE`; an exhaustive `switch` needs a branch.
- `BaseAuthSession.Status` gained `AWAITING_CODE`. No DDL change is required —
  it fits the existing `VARCHAR(20)` — unless the column is constrained by a
  `CHECK` or an enum type.
- `AbstractSessionService.approve` / `reject` now accept a session in
  `AWAITING_CODE` as well as `PENDING`. Step ordering is the flow's
  responsibility, so a host calling `approve` directly still bypasses the code
  step by design.

## [0.3.0] - 2026-08-09

### Added
- **Contact-share + inline approve/reject** (opt-in, non-breaking):
  `DefaultAuthFlow.Options` with `requireContact` (soft phone request via
  Telegram contact-share, `/skip` allowed, spoofed contacts refused) and
  `requireApproval` (inline ✅/❌ confirmation instead of auto-approve —
  strongly recommended: auto-approve is phishable). Both default to `false`.
- `FlowMessages`: built-in bot texts in `uz` (default) / `ru` / `en`, resolved
  from Telegram `language_code`; override `DefaultAuthFlow#msg` to customise.
- `TelegramBot`: `sendMessage(chatId, text, replyMarkupJson)`,
  `answerCallbackQuery(id, text)`, `editMessageText(chatId, messageId, text)`;
  non-2xx responses are now logged.
- `TelegramBotModule`: `onCallbackQuery(...)` / `onContact(...)` handler hooks
  and builder options `sessionRetention` (default 1 day; the sweeper now
  deletes terminal sessions older than this), `maxPendingPerIp` (default 50;
  `POST /session` returns `429 Too Many Requests` beyond it),
  `trustProxyHeaders` (default `false`) and `trustedProxyHops` (default 1 —
  how many trusted proxies sit between the client and the app).
- `BaseAuthSession.approvePayload` (`approve_payload VARCHAR(4000)`, nullable):
  the approve payload is persisted as JSON, so a poll arriving after approval
  still receives it (previously it was delivered only to a live long-poll and
  could be lost forever). Existing schemas:
  `ALTER TABLE <session_table> ADD COLUMN approve_payload VARCHAR(4000);`
- `BaseAuthSessionRepository`: `findWithLockByTokenHash` (pessimistic lock),
  `countByIpAddressAndStatusAndExpiresAtAfter`, `deleteByStatusInAndExpiresAtBefore`
  (a `@Modifying` JPQL bulk delete returning `int`, not a derived query — a
  derived `deleteBy…` would load every matching row into the persistence context
  and delete them one at a time during the scheduled sweep).
  The per-IP count ignores overdue sessions, so a caller is never locked out
  while waiting for the sweeper to mark them `EXPIRED`.
  **Index `ip_address` on your session table** — the per-IP count runs on every
  session creation, so without one `POST /session` full-scans the table:
  `@Table(name = "…", indexes = @Index(columnList = "ip_address,status"))`.

### Added (BREAKING for existing schemas)
- `BaseAuthSession.updatedAt` (`updated_at` column, `NOT NULL`) with the same
  `@PreUpdate` refresh behaviour as `BaseTelegramUser`. Hosts with existing
  session tables must add the column, e.g.
  `ALTER TABLE <session_table> ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();`

### Changed (BREAKING)
- `AbstractSessionService.approve(...)` and `reject(...)` now return `boolean`
  (`false` when the session is missing, already terminal, or expired) and take
  a row lock, so concurrent approve/reject calls can no longer double-fire the
  host `approveHandler`. Hosts overriding these methods must update signatures.
- Auth events are published **after the DB transaction commits** — a client can
  no longer observe an `APPROVED` event whose transaction later rolled back.
- `X-Forwarded-For` is no longer trusted by default when resolving the client
  IP; opt in with `trustProxyHeaders(true)` when running behind a proxy. When
  trusted, the client entry is counted `trustedProxyHops` positions from the
  **right** — each trusted hop appends the peer it received from, and everything
  further left is client-supplied and can be forged to bypass `maxPendingPerIp`.
  A header with fewer entries than the configured hop count is ignored in favour
  of the socket address.
- `TelegramBotModule.onCallbackQuery(...)` / `onContact(...)` now throw
  `IllegalStateException` when a second handler is registered instead of
  silently replacing the first (re-registering the *same* handler stays a
  no-op). A host that registered its own callback handler alongside
  `requireApproval(true)` used to disable login approval with no error anywhere;
  route your own updates through `fallback(...)`, which the flow already
  forwards everything outside its `tgauth:` namespace to.
- An approve payload that does not fit `approve_payload` (4000 chars) is no
  longer written to the row — it is logged and delivered via the live event
  only, instead of failing the whole approve transaction.
- `AbstractTelegramUserService.register(...)` never re-activates a `BLOCKED`
  user (returns the user unchanged), and a `null`/blank phone no longer erases
  a previously stored phone. `DefaultAuthFlow` denies `BLOCKED` users with a
  localized "access denied" message.
- `DefaultAuthFlow` handlers accept **private chats only** (`chat.id ==
  from.id`). A `/start` deep link pasted into a group used to register the
  *group id* as a Telegram user and approve the session for it; with
  `requireApproval` the inline ✅ landed in the group where any member could
  tap it and approve someone else's browser session under their own account.
  Group/channel updates are now ignored, and a `tgauth:` callback arriving from
  any chat other than the presser's own is refused.
- With `requireApproval`, the user is registered when ✅ is pressed instead of
  on `/start`, so a rejected or abandoned login no longer leaves an `ACTIVE`
  account behind. A phone collected in the contact step is carried to that
  moment and saved with it.
- The inline confirmation now shows the session's IP and user-agent
  (`FlowMessages.Key.CONFIRM_DETAILS`), so the user can distinguish their own
  sign-in from one a phisher started for them. Override
  `DefaultAuthFlow#confirmPrompt(S, String)` to change the wording or layout.
- `DefaultAuthFlow` forwards updates it does not own to the module `fallback`
  (`callback_data` outside the `tgauth:` namespace, contacts with no login in
  progress) instead of dropping them, so a host can keep its own inline
  keyboards while the flow owns `onCallbackQuery` / `onContact`.
- `protected void proceedAfterIdentity(...)` changed signature to
  `(long userId, JsonNode from, String rawToken, String phone, String lang)` —
  it now owns registration. Subclasses overriding it must update.

### Removed (BREAKING)
- `externalUserId` field (and `external_user_id` column mapping) from
  `BaseTelegramUser`, and the `externalUserId` component from
  `TelegramUserInfo`. Hosts that need to link their own user id can keep the
  mapping in their subclass entity.

### Fixed
- `sendMessage` / `answerCallbackQuery` / `editMessageText` now carry a 10 s
  request timeout. Only `getUpdates` had one, so a single stalled connection
  pinned the new single-threaded dispatcher worker indefinitely and the bot
  stopped answering **every** user, not just the one being messaged.
- The dispatcher's worker queue is bounded (100) and a full queue blocks the
  poll thread until a slot frees, so a slow handler throttles polling instead of
  buffering an unbounded backlog of updates whose offsets the next poll would
  confirm to Telegram. Blocking rather than running the overflow inline
  (`CallerRunsPolicy`) keeps handler execution single-threaded and in arrival
  order under load. `TelegramBotRunner.stop()` drains that queue (5 s) before
  forcing threads down, instead of discarding queued updates outright.
- `BotUpdateDispatcher.dispatch(...)` no longer collapses the whole batch to
  `-1` when routing one update fails. The offset stayed put, so Telegram
  re-delivered the entire batch and every handler in it ran a second time; only
  an unparseable or non-`ok` response signals back-off now.
- `requireApproval` builds `callback_data` as `tgauth:<action>:<rawToken>` and
  now fails fast with an `IllegalStateException` when that exceeds Telegram's
  64-byte limit. Previously the API silently rejected the keyboard (logged as a
  non-2xx warning) and the user saw nothing. The built-in 43-char token leaves
  6 bytes of headroom; a custom `TokenGenerator` must stay inside it.
- `maxPendingPerIp` is documented as **best-effort**: the count and the insert
  are not atomic, so a simultaneous burst from one IP can land a few rows over
  the limit. Use a gateway/WAF rate limiter for an exact ceiling. Its default is
  50 rather than 10, which was low enough to `429` legitimate users sharing one
  address (office NAT, carrier CGNAT, a CDN egress IP).
- `DefaultAuthFlow`'s parked-login map is swept on every park, not only on
  `/start`, and is capped at 10 000 entries (oldest evicted) so abandoned logins
  cannot grow it without bound. Its JVM-local, non-replicated nature is now
  documented: `✅`/`❌` survive a restart because the token travels in the
  callback and the session lives in the DB, but a pending contact-share or
  `/skip` does not.
- `BaseTelegramUser.updatedAt` now refreshes automatically on every entity
  update via a JPA `@PreUpdate` callback (previously it was only set once at
  creation and never changed).
- Poll endpoint race: the event-bus subscription is now registered **before**
  the final DB status check, so an approval landing in that window is never
  missed by the waiting client.
- Polling loop no longer hammers the Telegram API without a pause when
  `getUpdates` returns a non-ok response (invalid token, 409 from a competing
  poller) — it now backs off by `pollingInterval`.
- Update handlers now run on a dedicated single worker thread per bot instead
  of the polling thread, so a slow handler no longer stalls update fetching
  (ordering is preserved).

## [0.2.0] - 2026-06-20

### Changed (BREAKING)
- Rewritten as an abstract multi-instance toolkit. The starter now ships only
  generic base classes (`BaseTelegramUser`, `BaseAuthSession`,
  `BaseTelegramUserRepository`, `BaseAuthSessionRepository`,
  `AbstractTelegramUserService`, `AbstractSessionService`,
  `AbstractTelegramAuthController`) plus a per-type `TelegramBotModule`.
- The starter no longer creates tables, ships a Liquibase changelog, or
  registers any concrete entity/controller. Hosts own all of these.

### Removed
- Concrete `MTelegramUser`/`MTelegramAuthSession`, their repositories, the
  concrete `SessionService`/`TelegramUserService`/`TelegramAuthController`, the
  bundled changelog, and the `TelegramAuthRegisterHandler` hook.

### Added
- `TelegramBotModule` config object with a code-built command registry
  (`Consumer<JsonNode>`) + fallback handler, per-module bot instance, and
  isolated event bus.
- `DefaultAuthFlow` that self-registers a working `/start` flow.
- Per-module long-poll lifecycle supporting N independent bots.

## [0.1.2] - 2026-05-13

### Fixed
- `@EntityScan` consumer entity scan'ni o'chirib qo'yardi, endi
  `@AutoConfigurationPackage` ishlatiladi (additive scan). Avval starter'ni
  ulagan ilovaning o'z `@Entity`-larini Hibernate ko'rmas edi va
  `IllegalArgumentException: Not a managed type` xatosi chiqar edi. Endi
  starter o'z entity paketini Spring Boot'ning ichki auto-scan ro'yxatiga
  *qo'shadi*, host ilovaning default detection'ini buzmaydi.

  Implementation detail: `TelegramAuthEntityScanConfig` endi alohida
  `@AutoConfiguration` bo'lib, `@AutoConfigureBefore({HibernateJpaAutoConfiguration,
  JpaRepositoriesAutoConfiguration})` bilan ro'yxatga olingan.
  Bu tartib zarur — Spring Data JPA registrar'i `AutoConfigurationPackages`
  ni config-class parsing paytidayoq o'qib singleton'ni cache qiladi;
  bizning paket ro'yxatga undan oldin qo'shilishi kerak.

### Added
- Integration test (`AdditiveEntityScanTest`) — host ilovaning o'z entity'si
  va starter'ning entity'lari bir vaqtda Hibernate metamodel'da bo'lishini
  tekshiradi (regressiya himoyasi).

## [0.1.1] - 2026-05-12

### Changed
- Gradle build tizimidan Maven'ga to'liq ko'chirish.
- `groupId` `io.github.dev-abdulhay` ga moslangan (Maven Central uchun).

## [0.1.0] - 2026-05-12

### Added
- Phase 1 (MVP) chiqishi: long-polling transporti, in-memory event bus,
  Liquibase changelog, REST API (`/api/tg-auth/session*`).
