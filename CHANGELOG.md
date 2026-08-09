# Changelog

All notable changes to this project will be documented in this file.

The format is loosely based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added (BREAKING for existing schemas)
- `BaseAuthSession.updatedAt` (`updated_at` column, `NOT NULL`) with the same
  `@PreUpdate` refresh behaviour as `BaseTelegramUser`. Hosts with existing
  session tables must add the column, e.g.
  `ALTER TABLE <session_table> ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();`

### Removed (BREAKING)
- `externalUserId` field (and `external_user_id` column mapping) from
  `BaseTelegramUser`, and the `externalUserId` component from
  `TelegramUserInfo`. Hosts that need to link their own user id can keep the
  mapping in their subclass entity.

### Fixed
- `BaseTelegramUser.updatedAt` now refreshes automatically on every entity
  update via a JPA `@PreUpdate` callback (previously it was only set once at
  creation and never changed).

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
