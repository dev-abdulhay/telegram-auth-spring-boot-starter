# Managed-bot intents (+ host-account linking) — design

**Date:** 2026-09-14 (revised 2026-09-15 against the 0.4.0 codebase; account linking added the same day)
**Target:** `io.github.dev-abdulhay:telegram-auth-spring-boot-starter` 0.5.0 (library), then Kidzo backend + admin (host)
**Status:** design approved in brainstorming; reviewed against the code; not implemented

Two features ship together in 0.5.0 because they compose: **intents** (§1–§10) correlate a created bot with the request that asked for it, and **host-account linking** (§11) binds a Telegram identity to an account the host already has. The bot-creation flow is what links an admin's Telegram in the first place.

## Qisqacha (Uzbek)

Kutubxonaning managed-bots qismi bot yaratish havolasini beradi, lekin yaratilgan botni qaysi so'rovga tegishli ekanini aniqlamaydi. Kidzo buni username bo'yicha qiladi, username esa Telegram'da tahrirlanadi. Dev'da shunday bo'ldi: `hs_2_bot` rezerv qilindi, bot `hs_2_dev_bot` bo'lib yaratildi va ulanmadi. Yechim — kutubxonaga «intent» tushunchasini qo'shish: foydalanuvchi avval manager botda `/start` bosadi, kutubxona uning Telegram id'sini intent'ga yozadi, bot yaratilganda intent **yaratuvchi** bo'yicha topiladi. Aniqlab bo'lmasa, platforma foydalanuvchiga uning botlari ro'yxatini ko'rsatib, qaysi bot qaysi maqsadga ekanini tanlatadi va keraksizini o'chirishga imkon beradi.

## 1. Problem

Telegram's bot-creation deep link `https://t.me/newbot/<manager>/<suggested_username>?name=...` carries no host payload. The `managed_bot` update that follows says **who** created the bot (`managed_bot.user.id`) and **which** bot it is, nothing else. The suggested username is only a suggestion: the user can edit it in Telegram's confirmation dialog (`ManagedBotLink`'s own javadoc says so).

Version 0.4.0 stores the token and publishes `ManagedBotEvents.onCreated(ManagedBot)`; correlating the new bot with the host's request is left to the host. The only field a host can match on without extra state is the username, which is unreliable by construction.

Observed in Kidzo dev on 2026-09-14: the org reserved `hs_2_bot`, the owner created `hs_2_dev_bot`, the token was stored (`managed_bot` row, tenant bot polling), but `BotConnectionService.connectPending` logged `created but no pending registration matches`. The org's bot row stayed `pending` and could not be activated.

A host cannot fix this on its own without a second identity channel: Kidzo staff have no linked Telegram id, so `ownerUserId` cannot be compared with anything the host knows.

## 2. Goals and non-goals

**Goals**
- Match a created managed bot to the host request by the creator's Telegram user id, so an edited username no longer breaks the link.
- Never bind a bot to a request whose creator is not proven to be the same Telegram user.
- When automatic matching is ambiguous, give the host what it needs to let the user resolve it: list that creator's unassigned bots, assign one to a request, decommission the rest.
- Purely additive: 0.4.0 hosts upgrade with no code change and no behaviour change.

**Non-goals**
- No notion of organisation, tenant or permission in the library. `hostRef` is an opaque string.
- No schema management: hosts create the intent table with their own migrations, as with `managed_bot`.
- No "claim after the fact" flow for bots created before the intent existed — those are resolved by a human through §6.
- Deleting the bot on Telegram itself (not possible via the Bot API; only its owner can, in BotFather).

## 3. Concept

`ManagedBotIntent` — a request to create one bot.

| Field | Type | Notes |
|---|---|---|
| `id` | String | random, URL-safe `[A-Za-z0-9_-]`, 22 chars (fits Telegram's 64-char `start` limit with the `mb_` prefix) |
| `suggestedUsername` | String, **nullable** | validated with `ManagedBotLink`'s rules when present; `ManagedBotLink.build` treats it as optional, so the intent must too |
| `suggestedName` | String, nullable | |
| `hostRef` | String, nullable, ≤ 128 chars | opaque host correlation key, e.g. `bot:2` |
| `ownerUserId` | Long, nullable | set on the first `/start` |
| `status` | enum | `OPEN`, `CLAIMED`, `COMPLETED`, `EXPIRED`, `CANCELLED` |
| `botUserId` | Long, nullable | set on `COMPLETED`; unique among non-null values |
| `createdAt`, `claimedAt`, `completedAt`, `expiresAt` | **`OffsetDateTime`** | `expiresAt = createdAt + intent-ttl` |

`OffsetDateTime`, not `Instant`: every timestamp in this library (`ManagedBot`, `BaseManagedBot`, `BaseAuthSession`) is an `OffsetDateTime`, and a record that mixes the two makes every host conversion a cast site.

### State machine

```
OPEN ──/start (first user)──▶ CLAIMED ──auto match / assignToIntent──▶ COMPLETED
  │                              │
  ├──now > expiresAt──▶ EXPIRED  └──cancelIntent──▶ CANCELLED
  └──cancelIntent──▶ CANCELLED
```

- TTL applies to `OPEN` only. A `CLAIMED` intent does not expire: its owner is proven, and the manual resolution screen (§6) needs it to survive. The host ends it with `cancelIntent`.
- `EXPIRED` is evaluated lazily on read (`OPEN` and `now > expiresAt`) and persisted when touched. An `OPEN` row nobody ever reads therefore stays `OPEN` in the database forever — which is why the purge query (§5.2) is written against `expires_at`, not against the status alone.
- `COMPLETED` rows are kept: they are the bot→intent link that `findUnassignedBots` relies on.

## 4. Flow

1. **Create.** Host calls `ManagedBotService.createIntent(suggestedUsername, suggestedName, hostRef)` → `ManagedBotIntentLink(intentId, url, expiresAt)` where `url = https://t.me/<managerUsername>?start=mb_<intentId>`.
2. **Claim.** The user opens the link; the manager bot receives `/start mb_<id>` in a private chat. `ManagedBotIntentFlow` (§5.5) asks `ManagedBotService.claimIntent(id, from.id)`:
   - `UNKNOWN` (no such row) → **the flow does not reply and does not claim the update**; it falls through to whatever else owns `/start` (normally `DefaultAuthFlow`). This is what keeps a login token that happens to begin with `mb_` working — see §5.5.
   - `CLOSED` (found, `EXPIRED` or `CANCELLED`) → reply `INVALID_LINK`.
   - `OTHER_OWNER` (`CLAIMED`/`COMPLETED` by a different Telegram user) → reply `INTENT_OTHER_OWNER`; nothing changes.
   - `COMPLETED` (this user's, already linked to a bot) → reply `INTENT_ALREADY_DONE`.
   - `CLAIMED` (first claim) or `RECLAIMED` (same user taps again; idempotent, no second `onIntentClaimed`) → reply `INTENT_PROMPT` with one inline URL button `BTN_CREATE_BOT` → `ManagedBotLink.build(managerUsername, suggestedUsername, suggestedName)`. If the claim-time match (step 2b) already completed the intent, reply `INTENT_ALREADY_DONE` instead.
   - Not a private chat → the flow claims the update and does nothing (same rule as `DefaultAuthFlow.onStart`; an intent payload must never reach the login path from a group).

   2b. **Claim-time match.** The first claim runs the same matching step as step 5 against the owner's unassigned bots. It covers the case where the bot already exists when the intent is claimed — a host still handing out `createLink` alongside intents, or a `fetchAndStore` recovery that landed first. Events: `onIntentClaimed(intent)` first, then exactly one of `onIntentMatched` / `onIntentAmbiguous` / nothing.
3. **Create on Telegram.** The user confirms in Telegram, possibly with an edited username.
4. **Store.** `managed_bot` update → existing `handleUpdate` fetches and stores the token **unchanged**.
5. **Match** (new, only on creation — never on rotation, never for an echo). The state changes are persisted *before* anything is published, so a throwing listener cannot leave a half-linked intent:
   - If the bot is already linked to an intent (`findByBotUserId` present) → do nothing (re-delivered update).
   - `candidates = intentStore.findClaimedByOwner(bot.ownerUserId())` (status `CLAIMED` only), **filtered to `bot.createdAt() >= intent.createdAt()`** — an intent cannot predate the bot it asked for, and without the filter a bot created for some other purpose before the intent existed becomes a silent auto-match candidate.
   - `candidates` empty → do nothing. This is the intent-less 0.4.0 flow; no event.
   - Exactly one candidate whose `suggestedUsername` equals `bot.username()` (case-insensitive, **null-safe on both sides** — `fetchAndStore` stores a `null` username, and `suggestedUsername` is optional) → match it.
   - Else exactly one candidate in total → match it.
   - Else → `onIntentUnmatched(bot, candidates)`.
   - Match = `status = COMPLETED`, `botUserId`, `completedAt`, save, then `onIntentMatched(bot, intent)`.
   - Publishing order on creation: `onCreated(bot)` → the intent event. Each publish is wrapped in a guard that logs and continues, so a host listener throwing in `onCreated` no longer swallows the intent event. (The white-label bridge already guards every callback; this brings direct hosts in line — note it in the CHANGELOG's *Changed* section.)
6. **Manual resolution** — see §6.

If intents are not configured (no `ManagedBotIntentStore` bean) steps 2 and 5 do not exist: no `/start` route is registered and no matching runs.

## 5. Library API

Package `io.github.dev_abdulhay.telegramauth.managedbots` unless noted.

### 5.1 Types

```java
public enum ManagedBotIntentStatus { OPEN, CLAIMED, COMPLETED, EXPIRED, CANCELLED }

public record ManagedBotIntent(String id, String suggestedUsername, String suggestedName,
        String hostRef, Long ownerUserId, ManagedBotIntentStatus status, Long botUserId,
        OffsetDateTime createdAt, OffsetDateTime claimedAt, OffsetDateTime completedAt,
        OffsetDateTime expiresAt) { }

public record ManagedBotIntentLink(String intentId, String url, OffsetDateTime expiresAt) { }

public enum IntentClaim { CLAIMED, RECLAIMED, COMPLETED, OTHER_OWNER, CLOSED, UNKNOWN }

/** {@code intent} is null for {@code UNKNOWN}. */
public record IntentClaimResult(IntentClaim outcome, ManagedBotIntent intent) { }

public class ManagedBotIntentException extends RuntimeException {
    public enum Reason { INTENT_NOT_FOUND, INTENT_NOT_CLAIMED, INTENT_CLOSED,
                         BOT_NOT_FOUND, OWNER_MISMATCH, BOT_ALREADY_ASSIGNED }
    public Reason reason();
}
```

Failure taxonomy, deliberately split:
- `IllegalStateException` — misconfiguration the host fixes in code (no intent store bean). Mirrors `TelegramManagedBotsAutoConfiguration`'s missing-encryption-key behaviour.
- `IllegalArgumentException` — an invalid suggested username, same rules and same type as `ManagedBotLink`.
- `ManagedBotIntentException(Reason)` — runtime outcomes a host UI branches on.

### 5.2 Store

```java
public interface ManagedBotIntentStore {
    void save(ManagedBotIntent intent);                       // upsert by id
    Optional<ManagedBotIntent> findById(String id);
    Optional<ManagedBotIntent> findByBotUserId(long botUserId);
    List<ManagedBotIntent> findClaimedByOwner(long ownerUserId);
    int deleteClosedBefore(OffsetDateTime cutoff);
}
```

- `deleteClosedBefore` deletes every intent that can no longer be claimed or completed: `CANCELLED`, `EXPIRED`, **and `OPEN` rows whose `expiresAt` already passed** — the lazily-expired rows nobody ever read. `CLAIMED` and `COMPLETED` are never deleted. In SQL: `WHERE status NOT IN ('CLAIMED','COMPLETED') AND expires_at < :cutoff`. A `CLAIMED` intent cancelled long after its `expiresAt` is therefore purged on the next sweep; that is intentional — a cancelled intent links nothing and the host has already had its events.
- `InMemoryManagedBotIntentStore` (tests, non-JPA hosts).
- `BaseManagedBotIntent` — `@MappedSuperclass` mirroring `BaseManagedBot`, but with an **assigned `String` `@Id`** (no `@GeneratedValue`): `id` (32), `suggested_username` (50, nullable), `suggested_name` (100), `host_ref` (128), `owner_user_id`, `status` (16, `EnumType.STRING`), `bot_user_id` (unique), `created_at`, `claimed_at`, `completed_at`, `expires_at` — all `OffsetDateTime`.
- `BaseManagedBotIntentRepository<I extends BaseManagedBotIntent>` and `JpaManagedBotIntentStore<I>` mirroring `BaseManagedBotRepository` / `JpaManagedBotTokenStore`, including the `@Transactional` derived-delete note and the find-then-update upsert (`repo.findById(id).orElseGet(factory)`).
- Required host indexes (documented, not created by the library): `(owner_user_id, status)`, `(status, expires_at)` for the purge, unique `bot_user_id` where not null.

### 5.3 `ManagedBotService` additions

```java
ManagedBotIntentLink createIntent(String suggestedUsername, String suggestedName, String hostRef);
Optional<ManagedBotIntent> findIntent(String intentId);
IntentClaimResult claimIntent(String intentId, long ownerUserId);   // called by the flow, public for hosts with a custom /start
void cancelIntent(String intentId);                 // OPEN|CLAIMED -> CANCELLED; else ManagedBotIntentException
List<ManagedBot> findUnassignedBots(String intentId);
ManagedBotIntent assignToIntent(String intentId, long botUserId);
void decommissionUnassigned(long botUserId);
```

- **Constructor compatibility.** `ManagedBotService`'s 6-arg constructor is public 0.4.0 API and is called with `new` by the auto-configuration and by tests. Intents get an **overload** — `(module, store, encryptor, events, retries, backoff, ManagedBotIntentStore, Duration intentTtl, Duration intentRetention)` — and the existing constructor delegates to it with a `null` store. No existing signature changes.
- `createIntent` throws `IllegalStateException("managed-bot intents need a ManagedBotIntentStore bean")` when no store is configured, and `IllegalArgumentException` for an invalid username. Reusing `ManagedBotLink`'s rules means **making its `validateUsername` accessible** (package-private → public static, or a public `ManagedBotLink.validateUsername(String)`); validating at creation keeps the exception off the update worker thread, where the claim reply builds the same link.
- `createIntent` also runs the retention purge opportunistically, at most once per minute (a `volatile OffsetDateTime` guard). JVM-local and not replicated, like the echo map — several instances each purge at most once a minute, which is harmless. (Note: `DefaultAuthFlow.purgeStalePending` is *not* throttled; it sweeps an in-memory map. This one hits the database, hence the guard.)
- `findUnassignedBots(intentId)`: the intent must be `CLAIMED` (else empty list); returns `tokenStore.findByOwnerUserId(intent.ownerUserId())` minus bots for which `intentStore.findByBotUserId` is present. **Not** filtered by `createdAt`: the manual screen is exactly where an older, pre-intent bot (today's `hs_2_dev_bot`) has to be reachable. Tokens are never exposed (existing `ManagedBot.toString` masking stays).
- `assignToIntent(intentId, botUserId)` rules, each failure a `ManagedBotIntentException` with a `Reason`:
  - `INTENT_NOT_FOUND`, `INTENT_NOT_CLAIMED`, `BOT_NOT_FOUND`,
  - `OWNER_MISMATCH` (bot's `ownerUserId` ≠ intent's),
  - `BOT_ALREADY_ASSIGNED` (including a unique-constraint violation from a concurrent assignment).
  - Success → `COMPLETED`, publish `onIntentMatched(bot, intent)`, return the saved intent.
- `decommissionUnassigned(botUserId)` — removal of an unwanted bot from the resolution screen: refuses with `ManagedBotIntentException(BOT_ALREADY_ASSIGNED)` when the bot is linked to any intent, otherwise delegates to the existing `decommission(botUserId)`. The existing `decommission` stays unchanged (hosts still retire live bots through it).

### 5.4 Events (`ManagedBotEvents`, all `default` no-op)

```java
default void onIntentClaimed(ManagedBotIntent intent) { }
default void onIntentMatched(ManagedBot bot, ManagedBotIntent intent) { }
default void onIntentUnmatched(ManagedBot bot, List<ManagedBotIntent> candidates) { }   // creation path: one bot, several intents
default void onIntentAmbiguous(ManagedBotIntent intent, List<ManagedBot> candidates) { } // claim path: one intent, several bots
```

- Order on creation: `onCreated(bot)` then exactly one of `onIntentMatched` / `onIntentUnmatched` / nothing.
- Order on claim: `onIntentClaimed(intent)` then exactly one of `onIntentMatched` / `onIntentAmbiguous` / nothing.
- Matching state is persisted before any publish, and each publish is guarded (§4.5), so a throwing listener cannot skip the next event or leave the intent half-linked.
- **`TenantBotEventBridge` must forward all four** to host delegates with the same identity filtering and `guard(...)` it already applies, otherwise white-label hosts never see them. `onIntentClaimed` and `onIntentAmbiguous` have no `botUserId`; log/guard them with `0` (or the intent id) in the message.

### 5.5 `/start` routing

Routing lives in the **module + dispatcher**, not in `DefaultAuthFlow`:

```java
// TelegramBotModule
public void startPayload(String prefix, Predicate<JsonNode> handler);   // duplicate prefix -> IllegalStateException
public Map<String, Predicate<JsonNode>> getStartPayloadRoutes();        // unmodifiable, like getCommands()
```

- `prefix` must be non-blank and limited to Telegram's `start` alphabet `[A-Za-z0-9_-]`.
- `BotUpdateDispatcher.route` — after `parseCommand` resolves `/start` and before invoking the command registry — looks the payload up against the registered prefixes (longest match wins) and, when one matches, invokes a *composed* handler on the executor:
  ```java
  invoke(u -> { if (!route.test(u) && next != null) next.accept(u); }, update);
  ```
  so the store lookup runs on the worker thread, never on the polling thread, and the fall-through decision is made where the answer is known.
- **Why a `Predicate` and not a `Consumer`.** `TokenGenerator` emits Base64URL (`A-Za-z0-9-_`), so a login token starting with `mb_` is not impossible — it is one in `64³ ≈ 262 144`. With a fire-and-forget consumer that user would get "link invalid" and no login, silently and unreproducibly. Returning `false` for an id the intent store does not know sends the update on to `DefaultAuthFlow` exactly as before, which makes the collision a non-event. The claim of §5.5 in the first draft — "add a test that login tokens never start with `mb_`" — is not implementable and is dropped.
- This also removes the registration-order hazard: `TelegramBotModule.command()` is a plain `Map.put` with no claim-guard, so "register `/start` when the slot is free" depends on bean creation order and fails silently when `DefaultAuthFlow` is constructed second. With dispatcher-level routing, `DefaultAuthFlow` is untouched and intents work whether or not it exists. When **no** `/start` handler is registered at all, an unclaimed payload simply goes nowhere (debug log), as today.
- `ManagedBotIntentFlow` — the claim handler, in the managedbots package, self-registering like `DefaultAuthFlow` does:
  ```java
  public ManagedBotIntentFlow(TelegramBotModule module, ManagedBotService service) {
      module.startPayload(START_PREFIX, this::onStart);      // "mb_", a constant, not configurable
  }
  protected boolean onStart(JsonNode update);                 // overridable
  protected String msg(FlowMessages.Key key, String lang);    // overridable wording, DefaultAuthFlow's pattern
  ```

### 5.6 Configuration

| Property | Default | Meaning |
|---|---|---|
| `telegram.managed-bots.intent-ttl` | `30m` | lifetime of an `OPEN` intent |
| `telegram.managed-bots.intent-retention` | `7d` | closed rows older than this are purged |

Added to `TelegramManagedBotsProperties` (already `@ConfigurationProperties("telegram.managed-bots")`). No scheduler: the purge runs from `createIntent`, throttled as in §5.3.

Auto-configuration (`TelegramManagedBotsAutoConfiguration`): the intent store is a **host** bean, injected as `ObjectProvider<ManagedBotIntentStore>` into the `managedBotService` bean method so the feature stays optional, and `ManagedBotIntentFlow` is a `@Bean` `@ConditionalOnBean(ManagedBotIntentStore.class)`. White-label wiring (`TelegramWhiteLabelAutoConfiguration`, the `@Lazy ManagedBotService` cycle break) is untouched.

### 5.7 Messages

**There is no `MessageSource` in this library.** `messages_tgauth.properties`, `_ru` and `_en` are dead files — no Java class reads them, and Spring's default basename is `messages`, not `messages_tgauth`. Bot texts live in `FlowMessages` (a hard-coded uz/ru/en table keyed by `FlowMessages.Key`) and hosts customise them by overriding `DefaultAuthFlow#msg(Key, String)`.

Intents therefore add four keys to `FlowMessages.Key`, and `ManagedBotIntentFlow` gets the same `protected String msg(Key, String lang)` override point. The invalid/expired case reuses the existing `INVALID_LINK`, whose text is already exactly right.

| Key | uz | ru | en |
|---|---|---|---|
| `INTENT_PROMPT` | `Botingizni yaratish uchun quyidagi tugmani bosing. Telegram'da bot nomini o'zgartirishingiz mumkin.` | `Нажмите кнопку ниже, чтобы создать бота. В Telegram имя бота можно изменить.` | `Tap the button below to create your bot. You can change the bot's username in Telegram.` |
| `BTN_CREATE_BOT` | `Bot yaratish` | `Создать бота` | `Create bot` |
| `INTENT_OTHER_OWNER` | `Bu havola boshqa foydalanuvchiga tegishli.` | `Эта ссылка принадлежит другому пользователю.` | `This link belongs to a different user.` |
| `INTENT_ALREADY_DONE` | `Bu havola bo'yicha bot allaqachon yaratilgan.` | `Бот по этой ссылке уже создан.` | `The bot for this link has already been created.` |

Housekeeping in the same release: delete the three dead `messages_tgauth*.properties` files (CHANGELOG *Removed*) and fix `tasks/tech-doc/TECH_DOC.md:612`, which documents the non-existent `MessageSource` override path.

## 6. Manual resolution (host responsibility, library-backed)

When `onIntentUnmatched(bot, candidates)` or `onIntentAmbiguous(intent, candidates)` fires, the host shows the creator's bots and lets an authorised user decide:

- List: `findUnassignedBots(intentId)` for any of the host's `CLAIMED` intents (all candidates share one owner, so any of them yields the same list).
- Assign: `assignToIntent(intentId, botUserId)` per bot → the normal `onIntentMatched` path runs.
- Remove: `decommissionUnassigned(botUserId)` after a confirmation that states the bot is disconnected from the platform and must be deleted in BotFather by its owner if no longer wanted.

**The list is owner-scoped, not host-scoped.** It contains every unassigned bot of that Telegram user, including bots they created for another organisation, another product, or by hand. The library cannot tell them apart — it has no tenant concept by design (§2). Hosts must gate this screen behind their own permission check and word the removal confirmation accordingly; the README section says so explicitly.

## 7. Error handling summary

| Situation | Behaviour |
|---|---|
| No intent store bean | intents disabled; `createIntent` → `IllegalStateException`; no `/start` route; matching skipped |
| `/start mb_<unknown>` | not claimed → falls through to the normal `/start` handler (login), which answers as it always has |
| `/start mb_<expired or cancelled>` | `INVALID_LINK` reply |
| Login token that begins with `mb_` | store miss → fall-through → login proceeds normally |
| Link forwarded, second user taps | `INTENT_OTHER_OWNER`; first claimer keeps it |
| Re-delivered `managed_bot` update | token re-stored (existing); bot already linked → no second match, no second event |
| Two concurrent `assignToIntent` for one bot | unique `bot_user_id` → second gets `BOT_ALREADY_ASSIGNED` |
| Remove a bot that is already assigned via `decommissionUnassigned` | `BOT_ALREADY_ASSIGNED`; nothing revoked |
| Token fetch fails | existing `onTokenFetchFailed`; no matching until a `fetchAndStore` recovery, which runs matching too — with `username == null`, so only the single-candidate rule can fire |
| Host listener throws in `onCreated` | matching state is already persisted; the guard logs and the intent event still fires |

`fetchAndStore` (recovery entry point) runs the same matching step as `handleUpdate` when it results in a creation.

## 8. Compatibility and release

- All additions optional; 0.4.0 behaviour unchanged when no intent store is present and `createLink` is used.
- `ManagedBotEvents` gains only `default` methods; existing implementations compile.
- `ManagedBotService`'s existing constructor is kept and delegates (§5.3).
- `TelegramBotModule.command("/start", ...)` keeps working; `startPayload` is new; `DefaultAuthFlow` is not modified.
- Behaviour change to note: managed-bot event publishing from `ManagedBotService` is now guarded per listener (§4.5).
- `AbstractSessionService.create(ip, ua)` and the two-arg `AuthContext` constructor are kept; the `hostRef` variants are overloads (§11.2).
- **The one forced migration:** `host_ref` on the host's auth-session table (§11.7). Everything else is new tables or new methods.
- Version `0.5.0`. Files that must move together:
  - `pom.xml` — `0.4.0` → `0.5.0` (still at 0.4.0 today).
  - `CHANGELOG.md` — Keep-a-Changelog `[0.5.0]` with *Added* / *Changed* / *Removed*.
  - **`README.md`** — there is no `docs/managed-bots.md`; the managed-bots documentation is README `## Managed bots` (line ~569). Add `### Intents` after `### Minimal usage` covering the flow, the store the host provides, the DDL below, the events, the resolution screen and its owner-scoped caveat. Add a `## Linking a Telegram account to a host account` section for §11 (the `hostRef` contract, the server-side-only rule, the self-service auto-link rule and its bearer-link caveat, manager-bot vs tenant-bot composition). Also update `## Install` (version), add `## Upgrading to 0.5.0` **with the `host_ref` migration**, and tick the roadmap. CLAUDE.md makes the README update mandatory for a functional change.
  - `tasks/tech-doc/TECH_DOC.md` — new types in the map, and the i18n correction from §5.7.
- Host DDL example (PostgreSQL), documented in the README:

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

## 9. Testing

Test stack is the repo's existing one: JUnit 5 + `spring-boot-starter-test`, **H2** for JPA, WireMock for the Bot API. No Testcontainers — the partial unique index is host DDL and is documented, not tested; uniqueness is asserted at store level instead.

- **Lifecycle:** create → claim → complete; claim by a second user; same user re-claims (idempotent, one `onIntentClaimed`); expiry of `OPEN`; `CLAIMED` never expires; cancel from `OPEN` and `CLAIMED`; cancel from `COMPLETED` → `ManagedBotIntentException(INTENT_CLOSED)`.
- **Matching (creation):** username match among several; single candidate with an edited username; two candidates without a username match → `onIntentUnmatched` with both; no candidates → no event; a bot older than the intent is not a candidate; re-delivered update → no second event; rotation → no matching; `fetchAndStore` creation → matching runs with a null username.
- **Matching (claim):** bot created before the claim but after the intent → matched on claim, reply is `INTENT_ALREADY_DONE`; two such bots → `onIntentAmbiguous`; a bot created before the intent → no event.
- **Event guard:** a listener throwing in `onCreated` still gets `onIntentMatched`, and the intent is `COMPLETED` in the store.
- **assignToIntent:** each `Reason`; success publishes `onIntentMatched`; concurrent assignment (JPA/H2) → one wins with `BOT_ALREADY_ASSIGNED`.
- **decommissionUnassigned:** refuses a bot linked to an intent; otherwise revokes and deletes exactly like `decommission` and publishes `onDecommissioned`.
- **findUnassignedBots:** excludes bots already linked; includes bots older than the intent; never returns another owner's bots; non-`CLAIMED` intent → empty.
- **Purge:** `deleteClosedBefore` removes `CANCELLED`, `EXPIRED` **and stale untouched `OPEN`** rows, keeps `CLAIMED` and `COMPLETED`; `createIntent` purges at most once a minute.
- **Store contract:** an intent-store contract test in the style of `ManagedBotStoreContract`, run against both `InMemoryManagedBotIntentStore` and `JpaManagedBotIntentStore` (H2, alongside `JpaManagedBotStoreTest`).
- **Routing** (extends `BotUpdateDispatcherTest`, `ManagedBotRoutingTest`, `TelegramBotModuleTest`): `/start mb_x` with a known intent → intent flow, login flow untouched; **unknown `mb_` payload → falls through to the `/start` command handler**, including a login token literally starting with `mb_`; `/start <login token>` unchanged; `/start@manager mb_x` routes; non-private chat ignored; duplicate prefix registration throws; no `/start` command registered → unclaimed payload is dropped, not an exception.
- **White-label bridge** (`TenantBotEventBridgeTest`): all four new events reach a host delegate exactly once and never recurse into the bridge.
- **Messages:** every `FlowMessages.Key`, new ones included, has uz/ru/en text.
- **Auto-config** (`ManagedBotsAutoConfigTest`): no intent store bean → context starts, no `ManagedBotIntentFlow`, `createIntent` throws; with a store bean → flow registered and the `mb_` route present.
- Existing 0.4.0 test suite (233 tests) passes untouched.

## 10. Kidzo integration (host, after 0.5.0 is released)

Tracked as a separate ticket; listed here so the library API is checked against a real consumer.

- Upgrade the starter to 0.5.0; Liquibase changeset for `managed_bot_intent` (entity `ManagedBotIntentEntity extends BaseManagedBotIntent`) plus the `ManagedBotIntentStore` bean.
- `BotService.createLink` → reserve the `pending` bot row, then `createIntent(suggestedUsername, name, "bot:" + botId)`; return the intent URL to the admin app.
- `KidzoManagedBotEvents`:
  - `onIntentClaimed` → mark the pending row as confirmed by the Telegram user (for the admin UI).
  - `onIntentMatched` → `BotConnectionService` connects the row identified by `hostRef` (replaces username matching; keep username matching only for bots created before the upgrade).
  - `onIntentUnmatched` / `onIntentAmbiguous` → flag the org's pending rows as "needs assignment".
- Admin «Botlar» tab: pending row shows "@user confirmed, waiting for the bot"; on "needs assignment" show "Bots you created" with **Assign to this purpose** and **Remove from platform** (ConfirmDialog, text about BotFather, and about the list possibly containing bots from other contexts). Gated by the existing bot-management permission; list fetched only through the org's own intents.
- Fix the current dev data: org 1's `hs_2_bot` pending row and the unmatched `hs_2_dev_bot` managed bot — create a fresh intent, have the owner tap `/start`, then assign `hs_2_dev_bot` through the new screen (it is older than the intent, so only the manual path offers it).

Account linking (§11), same ticket or the next one:

- Liquibase: `host_ref VARCHAR(128)` on the auth-session table, plus `telegram_user_id` (or equivalent) on the admin/staff table with a unique constraint — one Telegram identity, one account.
- "Telegramni bog'lash" button in the admin profile → host endpoint that calls `sessionService.create(ip, ua, "link:admin:" + adminId)` and returns the `t.me/<manager>?start=<token>` URL plus the existing poll endpoint; the manager bot's module runs with `requireContact = true`.
- `KidzoApproveHandler.onApprove`: `hostRef` starting with `link:` → bind, refusing a rebind with a `{"linked": false, "reason": "already_bound"}` payload; otherwise the normal admin login by Telegram id.
- Bot creation: mark the intent `self` only when the admin creates a bot for their own organisation; on `onIntentClaimed` bind the claimer's Telegram id to that admin **only** when the account has no Telegram yet. A mismatching claim raises a "link was forwarded" notice in the admin UI instead of rebinding.
- Admin login page gets "Telegram orqali kirish" once an account can be bound; password (or whatever exists today) stays the primary path.

## 11. Host-account linking (manager bot)

The platform has its own accounts (Kidzo admins). An admin creates bots for other people **and** signs in to the platform with Telegram. Both need the same missing piece: a way to bind one Telegram identity to one host account.

### 11.1 The three paths, and what the library owes each

| # | Path | Library work |
|---|---|---|
| 1 | Signed-in admin taps "Link Telegram" → manager bot → phone asked → bound | **New:** `hostRef` on the session, carried into `AuthContext` (§11.2) |
| 2 | Admin creates a bot for themselves → the intent claim binds their Telegram, no phone | **None.** `onIntentClaimed(intent)` already carries `ownerUserId` + `hostRef` (§11.4) |
| 3 | End users sign in through the tenant bot that was just created | **None.** White-label runtime + `DefaultAuthFlow`, shipped in 0.4.0 (§11.5) |

Path 2 is an *additional* way in, never the only one: an admin whose Telegram was auto-linked can afterwards sign in through the manager bot with the ordinary login flow, because the binding is already there.

### 11.2 `hostRef` on the auth session

Today `TelegramAuthApproveHandler.onApprove(TelegramUserInfo, AuthContext)` gets the Telegram identity and the request's IP/user-agent — and nothing that says *which* session this is or *why* it was created. A host therefore cannot tell "someone is logging in" from "admin 7 is linking their Telegram", short of overriding `AbstractSessionService.approve`. One opaque string fixes it, mirroring the intent's own `hostRef`:

```java
// BaseAuthSession
@Column(name = "host_ref", length = 128)
private String hostRef;                                  // + getter/setter

// AbstractSessionService
public CreatedSession create(String ipAddress, String userAgent);                  // unchanged, delegates with null
public CreatedSession create(String ipAddress, String userAgent, String hostRef);  // new; IllegalArgumentException over 128 chars

// AuthContext
public AuthContext(String ipAddress, String userAgent);                  // kept
public AuthContext(String ipAddress, String userAgent, String hostRef);  // new
public String getHostRef();
```

`approve(...)` passes `session.getHostRef()` into the `AuthContext` it builds. Nothing else in the library reads the value — it is opaque, exactly like the intent's `hostRef`.

**Security: `hostRef` is server-side only.** It says *whose account this session may bind to*, so accepting it from the browser would let anyone mint `link:admin:7` and attach their own Telegram to another account. The stock `POST /session` endpoint and `CreateSessionRequest` therefore stay unchanged and never read it. Hosts set it in their own code, from an already-authenticated principal:

```java
// host controller, admin is authenticated by the platform's own session
var created = sessionService.create(ip, ua, "link:admin:" + currentAdminId());
```

For hosts that would rather extend the stock controller than write an endpoint, `AbstractTelegramAuthController` gets one override point, `protected String hostRef(HttpServletRequest request)`, returning `null` by default and consulted in `create(...)`. Overriding it is host code, so the rule holds.

Also in this change: `AuthContext`'s javadoc references an `AuthContextEnricher` type that does not exist anywhere in the repository — correct the wording while touching the class.

### 11.3 Linking is host semantics

The library never merges accounts, never decides what a `hostRef` means, and gains no notion of "admin". What it guarantees is only this: the string the host attached at session creation comes back in `onApprove`, on the same session, after the Telegram identity is confirmed by the full flow (contact + code, whichever options are on).

Host rules the README states:
- Branch in `onApprove`: `ctx.getHostRef()` starting with `link:` → bind `info.telegramId()` (and `info.phone()`) to that account; otherwise treat it as a login.
- **Refuse a second binding.** If that Telegram id is already bound to a different account, do not rebind — return an `AuthApproveResult` payload the browser can render (`{"linked": false, "reason": "already_bound"}`) rather than throwing: an exception out of `onApprove` is rethrown by `approve(...)`, the transaction rolls back and the session simply stays `PENDING` with nothing shown to anyone.
- The library's `BaseTelegramUser` row is created by the flow at final confirmation as always, so no extra work is needed for later logins.

**Phone.** `requireContact` is a module-level `DefaultAuthFlow.Options` flag, not per session, so the manager bot's module turns it on: every login *and* link through the manager bot asks for the phone (a soft requirement — `/skip` exists). Path 2 never touches the login flow, so it asks for nothing, which is what §11.4 wants.

### 11.4 Auto-link at intent claim (path 2)

No library change: `onIntentClaimed(intent)` carries `ownerUserId` (the Telegram id that tapped `/start`) and `hostRef` (whatever the host wrote when creating the intent).

The binding decision is the host's, and the rule is **self-service only**: the host marks in the intent's `hostRef` whether the requester is creating this bot for themselves (`bot:42;self:admin:7`) or on someone else's behalf (`bot:42`). Only the first form may auto-link.

This matters because the intent URL is a **bearer capability**: whoever opens it first claims it. If an admin creates a bot for another organisation and forwards the link, the claimer is that organisation's owner — auto-linking them to the admin's account would hand a stranger the admin's sign-in. Hence:

- Auto-link only when the platform UI showed the link to the authenticated admin themselves; never for a link the admin is expected to forward.
- Keep `intent-ttl` short (30m default).
- If the account already has a Telegram bound and a *different* Telegram id claims the intent, do not rebind. Surface it in the admin UI instead — it means the link was forwarded.
- No phone is collected on this path. A host that wants one asks for it later, or requires the path-1 flow before granting anything sensitive.

### 11.5 Manager bot vs tenant bots (path 3)

Two distinct `TelegramBotModule` beans, as the white-label documentation already describes:

- **Manager bot** — one per platform. Owns the `managed_bot` slot (`ManagedBotUpdateHandler`), the `mb_` start-payload route (`ManagedBotIntentFlow`), and a `DefaultAuthFlow` with `requireContact = true` for admin login and linking.
- **Tenant bots** — one per created bot, started by `TenantBotRegistry`. Each has its own `DefaultAuthFlow` for that tenant's end users, its own rate-limit scope, and sessions carrying `botUserId`.

Nothing new is required here; the section exists so the composition is written down in one place, and so the README's Intents section can link to it.

### 11.6 Testing (in addition to §9)

- `create(ip, ua, hostRef)` persists the value; `create(ip, ua)` leaves it null; over 128 chars → `IllegalArgumentException`.
- `approve` hands the stored `hostRef` to `onApprove` via `AuthContext`; a null `hostRef` stays null.
- The two-arg `AuthContext` constructor still compiles and returns a null `hostRef` (source compatibility for hosts that build one in tests).
- `POST /session` ignores any `hostRef`-looking field in the request body; a controller subclass overriding `hostRef(HttpServletRequest)` has it persisted.
- JPA: the new column round-trips on H2 (`JpaLayerTest` neighbourhood).
- Linking semantics themselves (bind, refuse-rebind) are host tests, not library tests — the library only carries the string.

### 11.7 Migration cost, stated plainly

`host_ref` is a new nullable column on the host's auth-session table. Every 0.4.0 host must add it before upgrading, whether or not they use linking, or Hibernate schema validation fails at startup:

```sql
ALTER TABLE auth_session ADD COLUMN host_ref VARCHAR(128);
```

This is the only schema change 0.5.0 forces on an existing table (the intent table is new), and it belongs in the README's `## Upgrading to 0.5.0` section in exactly these words.

## 12. Implementation prompt (library repository)

Use this verbatim in a session opened in the `telegram-auth-spring-boot-starter` repository.

> Implement **managed-bot intents and host-account linking** for release 0.5.0 exactly as specified in the design document below (paste §1–§11). Work test-first: for each behaviour in §9 and §11.6 write the failing test, see it fail, then implement.
>
> Scope, in order:
> 1. `ManagedBotIntentStatus`, `ManagedBotIntent`, `ManagedBotIntentLink`, `IntentClaim`, `IntentClaimResult`, `ManagedBotIntentException` (+ `Reason`).
> 2. `ManagedBotIntentStore`, `InMemoryManagedBotIntentStore`, `BaseManagedBotIntent`, `BaseManagedBotIntentRepository`, `JpaManagedBotIntentStore` — mirror the existing token-store classes' structure, javadoc tone and null-handling; assigned `String` id, `OffsetDateTime` everywhere.
> 3. `ManagedBotService`: new constructor overload (old one delegates), `createIntent`, `findIntent`, `claimIntent`, `cancelIntent`, `findUnassignedBots`, `assignToIntent`, `decommissionUnassigned`; the matching step on creation (`handleUpdate` and `fetchAndStore`, never rotation, never echoes) and on first claim, with the `bot.createdAt >= intent.createdAt` candidate filter; guarded publishing; throttled retention purge. Expose `ManagedBotLink`'s username validation.
> 4. `ManagedBotEvents` default methods (four); forward all four in `TenantBotEventBridge` with its existing identity filter and guard.
> 5. `TelegramBotModule.startPayload(prefix, Predicate<JsonNode>)` + `getStartPayloadRoutes()`; `BotUpdateDispatcher` consults them before the command registry and falls through to it when the predicate returns false; `ManagedBotIntentFlow` with the claim rules of §4.2 and an inline URL button. `DefaultAuthFlow` must not change.
> 6. Auto-configuration: `ObjectProvider<ManagedBotIntentStore>`, `ManagedBotIntentFlow` bean `@ConditionalOnBean(ManagedBotIntentStore.class)`, properties `intent-ttl` and `intent-retention`.
> 7. Four new `FlowMessages.Key` entries with uz/ru/en text; `msg(Key, lang)` override point on the flow; delete the dead `messages_tgauth*.properties` files.
> 8. Host-account linking (§11.2): `host_ref` on `BaseAuthSession`, the `create(ip, ua, hostRef)` overload, `AuthContext(ip, ua, hostRef)` + `getHostRef()` passed through `approve`, the `hostRef(HttpServletRequest)` override point on `AbstractTelegramAuthController`, and the corrected `AuthContext` javadoc. The stock `POST /session` must not read `hostRef` from the request body — assert it in a test.
> 9. README: `### Intents` (flow, host store + DDL, events, resolution screen and its owner-scoped caveat) and `## Linking a Telegram account to a host account` (§11: the `hostRef` contract, server-side-only rule, self-service auto-link and its bearer-link caveat, manager-bot vs tenant-bot composition); `## Install` version; `## Upgrading to 0.5.0` **including the `host_ref` ALTER TABLE**; roadmap tick; CHANGELOG `[0.5.0]`; `tasks/tech-doc/TECH_DOC.md` update including the i18n correction; `pom.xml` to 0.5.0.
>
> Constraints: no breaking change to any 0.4.0 public signature (the one unavoidable cost is the additive `host_ref` column, which the upgrade notes must state); no organisation/tenant/"admin" concept in the library — `hostRef` stays an opaque string; never log tokens or ciphertext; keep handlers short (they run on the update worker thread) and never do I/O on the polling thread. Run the full test suite before committing. Conventional Commits, no AI attribution of any kind in commits or PR text. Report each §9 and §11.6 test group as done / not done.
