# Changelog

All notable changes to this project will be documented in this file.

The format is loosely based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.4.0] - 2026-08-17

Login confirmation moves from "someone tapped a link" to "someone is looking at
the browser that started this login".

### Added
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
