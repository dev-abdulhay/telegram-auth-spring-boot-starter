# Managed Bots — design

**Date:** 2026-08-31
**Branch:** `feat/managed-bots`, cut from `fix/auth/flow/security-hardening` (v0.4.0)
**Status:** approved design, not yet implemented

**Base dependency:** this builds on the v0.4.0 work, which is still awaiting its
merge into `dev`/`main`. It relies on `TelegramBotModule#claimSlot` (single-slot
handler guard) and the response-reading `TelegramBot#post` path introduced there,
so it must merge *after* v0.4.0.

## Why

Telegram's Bot API 9.6 (3 April 2026) lets a *manager bot* create bots on behalf of
users and hold their tokens. For this library the destination is **white-label auth
bots**: a host SaaS gives every tenant its own branded login bot instead of routing
all tenants through one shared bot.

That destination needs two things, and this spec is only the first:

1. **Managed-bots core** (this spec) — create/fetch/rotate/store tokens, access
   settings, decommission, event hooks.
2. **White-label runtime** (separate, later spec) — starting a `TelegramBotModule`
   and its polling loop at runtime for each managed bot, per-tenant flow options,
   and the tenant column on sessions/users.

Everything here is designed so that (2) plugs into the event hooks and
`ManagedBotTokenStore#findAll` without reshaping the core.

## Verified API surface

Read from the live docs on 2026-08-31; nothing below is from memory. Bot API is at
10.3, and the managed-bots surface arrived across two releases:

**Bot API 9.6 — 3 April 2026**
- `User.can_manage_bots` — whether the user may create managed bots.
- `KeyboardButtonRequestManagedBot` — fields `request_id` (Integer, unique per
  message), `suggested_name` (optional String), `suggested_username` (optional
  String); reached via `KeyboardButton.request_managed_bot`.
- `ManagedBotCreated` — single field `bot` (`User`); delivered as
  `Message.managed_bot_created`.
- `ManagedBotUpdated` — fields `user` (`User`, the creator) and `bot` (`User`);
  delivered as the top-level `Update.managed_bot`. **It carries no field saying
  what changed** — creation, token change and owner change share one shape.
- `getManagedBotToken(user_id)` → token as String.
- `replaceManagedBotToken(user_id)` → revokes the current token, returns the new
  one as String.
- Deep link: `https://t.me/newbot/{manager_bot_username}/{suggested_bot_username}[?name={suggested_bot_name}]`

**Bot API — 8 May 2026**
- `BotAccessSettings` — `is_access_restricted` (Boolean), `added_users`
  (Array of `User`, optional).
- `getManagedBotAccessSettings(user_id)` → `BotAccessSettings`.
- `setManagedBotAccessSettings(user_id, is_access_restricted, added_user_ids?)`
  → True. Note the asymmetry: reads return full `User` objects, writes take a
  JSON-serialized array of **at most 10** user ids, ignored when
  `is_access_restricted` is False.

**Discrepancy to record:** there is **no** method to delete a managed bot. The
prompt asked us to verify this rather than assume it. Deletion stays with the
owner via BotFather; we implement a local *decommission* instead (below).

Prerequisite the library cannot satisfy for the host: *Bot Management Mode* must
be enabled for the manager bot in the BotFather Mini App.

## Architecture

New package `io.github.dev_abdulhay.telegramauth.managedbots`, flat like the
existing `bot/` and `flow/` packages.

**Dependency direction:** `managedbots` → `bot/` (the HTTP client and
`TelegramBotModule`) and nothing else. The auth code (`flow/`, `service/`,
`web/`) does not depend on `managedbots`, and `managedbots` never touches auth
sessions. The two meet only at the shared client and module.

| Component | Responsibility |
| --- | --- |
| `ManagedBotService` | Orchestrates client + store + encryptor + events |
| `ManagedBotLink` | Deep-link builder, local username validation |
| `ManagedBotTokenStore` | Persistence contract |
| `InMemoryManagedBotStore` | Map-backed implementation for tests and non-JPA hosts |
| `BaseManagedBot` + `BaseManagedBotRepository` + `JpaManagedBotTokenStore` | JPA implementation, host subclasses the entity |
| `TokenEncryptor` + `AesGcmTokenEncryptor` | Token confidentiality at rest |
| `ManagedBotEvents` | Host-facing listener hooks |
| `ManagedBotUpdateHandler` | Processes `managed_bot` updates |
| `TelegramManagedBotsAutoConfiguration` | Opt-in wiring |

## Public API

```java
public interface ManagedBotService {
    String createLink(String suggestedUsername, String suggestedName);
    Optional<String> findToken(long botUserId);
    String rotateToken(long botUserId);
    BotAccess getAccessSettings(long botUserId);
    void setAccessSettings(long botUserId, boolean restricted, List<Long> addedUserIds);
    void decommission(long botUserId);
}
```

- `createLink` takes the manager username from `TelegramBotModule#getUsername()`;
  the host never passes it. Both arguments are optional (`null` drops that part of
  the link). Javadoc must state that the username is only a **suggestion** — the
  user can change it in the confirmation dialog, and availability cannot be
  checked through the Bot API.
- Username validation is local: `[A-Za-z0-9_]`, 5–32 chars, must end with `bot`
  (case-insensitive). `name` is URL-encoded.
- `findToken` reads the store and decrypts; it never calls Telegram.
- `setAccessSettings` rejects more than 10 ids locally with
  `IllegalArgumentException` — fail fast rather than let Telegram reject it.

Records:

```java
public record ManagedBot(long botUserId, String username, String firstName,
                         long ownerUserId, String encryptedToken,
                         OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    @Override public String toString() { /* encryptedToken masked as *** */ }
}

public record BotAccess(boolean restricted, List<ManagedBotUser> addedUsers) {}
public record ManagedBotUser(long userId, String username, String firstName) {}
```

`toString` is overridden precisely because a record's generated one would print
the token into any log line that includes the object.

```java
public interface ManagedBotTokenStore {
    void save(ManagedBot bot);
    Optional<ManagedBot> findByBotUserId(long botUserId);
    List<ManagedBot> findByOwnerUserId(long ownerUserId);
    List<ManagedBot> findAll();
    void deleteByBotUserId(long botUserId);
}

public interface ManagedBotEvents {
    default void onCreated(ManagedBot bot) {}
    default void onTokenRotated(ManagedBot bot) {}
    default void onTokenFetchFailed(long botUserId, long ownerUserId, Exception cause) {}
    default void onDecommissioned(long botUserId) {}
}
```

`findAll()` is unused by this spec and exists for the white-label runtime, which
must restore every tenant bot after a restart. Defining it once avoids widening
the contract later.

### Client extension

`TelegramBot` gains `getManagedBotToken`, `replaceManagedBotToken`,
`getManagedBotAccessSettings`, `setManagedBotAccessSettings`. The existing
`post(...)` discards the response body; these four need it, so `post` gains a
response-reading sibling. Additive — the auth paths keep the discarding variant.

`getUpdates` gains an `allowed_updates` parameter. Today the library sends none,
so Telegram applies its default list, which **excludes** `managed_bot`. With the
feature enabled the runner must send
`["message","callback_query","managed_bot"]` explicitly.

## Update flow

`TelegramBotModule#onManagedBot(Consumer<JsonNode>)` — single-slot with the same
`claimSlot` replace-guard as the existing handlers. `BotUpdateDispatcher` checks
`managed_bot` before `callback_query`; it is a top-level `Update` field, so no
routing conflict exists.

On each `managed_bot` update:

1. Read `bot` and `user`. Missing `bot.id` → WARN and return.
2. Look the bot up in the store. Absent = creation; present = token or owner
   change. The update itself cannot tell us which, so the store is the source of
   truth.
3. `getManagedBotToken(bot.id)` with the retry policy.
4. Encrypt and save (insert or overwrite — idempotent by design, since a
   re-delivered update must not produce a second row).
5. **Only then** publish `onCreated` or `onTokenRotated`.

Step 5 is last so that a listener can call `findToken` and get a value. A failed
fetch writes nothing: a half-written row with no token would be worse than no
row. The handler runs on the existing single worker thread, so the network call
never blocks the poll loop, and `route(...)` already wraps handlers in
try/catch so one bad update cannot collapse the batch.

## Decommission

The Bot API has no delete. `decommission(botUserId)` therefore:

1. `replaceManagedBotToken` — revokes the token we hold; the new one is
   deliberately **discarded**, which leaves the bot unreachable by us.
2. Delete the store row.
3. Publish `onDecommissioned`.

The order is load-bearing and gets a test: deleting first would destroy the
credentials needed for step 1 and leave an orphan bot we can no longer revoke.
If revocation fails (e.g. the owner already deleted the bot in BotFather) we log
WARN and still delete locally — local cleanup is fail-open.

Documentation must be explicit: the bot still exists on Telegram's side and
remains owned by the user, who deletes it through BotFather.

## Storage and security

JPA follows the library's existing shape: `BaseManagedBot` is a
`@MappedSuperclass` the host subclasses with `@Entity @Table(...)`, and
`BaseManagedBotRepository` is `@NoRepositoryBean` with derived queries. Columns:
`bot_user_id` (unique, indexed), `username`, `first_name`, `owner_user_id`
(indexed), `encrypted_token` (`length = 512`), `created_at`, `updated_at`.
Hosts without JPA use `InMemoryManagedBotStore`.

`AesGcmTokenEncryptor` is the default: AES-256-GCM, a fresh random 12-byte IV per
write, stored as `Base64(IV || ciphertext || tag)`. The key comes from
`telegram.managed-bots.encryption-key` (Base64, 32 bytes).

**With the feature enabled and no key configured, the context fails to start.**
There is no plaintext fallback: a no-op default is the kind of thing that gets
forgotten and leaves tokens readable in a database dump. A host that supplies its
own `TokenEncryptor` bean (KMS, Vault) replaces the default, and then no key
property is required.

Tokens are never logged. `ManagedBot#toString` masks the field.

## Configuration

New `@ConfigurationProperties("telegram.managed-bots")` — deliberately a separate
namespace from `telegram.auth.*`, mirroring the module boundary.

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `false` | Gates the whole auto-configuration |
| `encryption-key` | — | Base64, 32 bytes; required unless a custom `TokenEncryptor` bean exists |
| `token-fetch-retries` | `3` | Attempts for `getManagedBotToken` |
| `token-fetch-backoff` | `1s` | First backoff, doubling per attempt |

**Rate limits:** a 429 response is a wait signal, not a failure. The
response-reading client path honours `retry_after` with one wait-and-retry that
is counted **separately** from the retry budget above. This is the only
behavioural change to the client, and it applies solely to the new methods.

## Testing

Unit:
- Link builder: valid and invalid usernames, the `bot` suffix rule,
  case-insensitivity, URL-encoded names, null arguments.
- Store contract: one test class run against **both** implementations.
- `AesGcmTokenEncryptor`: round-trip, and two encryptions of the same token
  differing (IV randomness).
- `ManagedBot#toString` masking.
- Decommission ordering, asserted on call sequence.
- Dispatcher routing: `managed_bot` reaches the new slot; existing routes intact.

Integration, against a mocked Bot API server (**WireMock, test scope only** — the
repo's `RecordingBot` stubs sit above the HTTP layer and cannot exercise
`retry_after` or transport failures):
- create → token → event,
- token rotation,
- 429 with `retry_after` honoured,
- retries exhausted → `onTokenFetchFailed`, nothing written to the store.

No tests against the real Telegram API.

## Documentation

README gains a Managed Bots section: what it does, prerequisites (Bot Management
Mode, `can_manage_bots`), config keys, the minimal usage flow, the "no delete"
caveat, and token-custody security notes. CHANGELOG gets an `[Unreleased]` entry.

## Out of scope

Starting bots at runtime, per-tenant flow options, and the tenant column on
sessions and users. Those belong to the white-label runtime spec, which builds on
`ManagedBotEvents` and `ManagedBotTokenStore#findAll`.
