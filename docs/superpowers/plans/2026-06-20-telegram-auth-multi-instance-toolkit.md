# telegram-auth Multi-instance Abstract Toolkit — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the fixed single-flow `telegram-auth-spring-boot-starter` into an abstract, multi-instance toolkit where a host app registers N independent Telegram user types by subclassing generic base classes and declaring one config object per type.

**Architecture:** The starter ships only generic abstract base classes (entity `@MappedSuperclass`, `@NoRepositoryBean` repositories, abstract services, abstract controller) plus a code-built per-type config object (`TelegramBotModule`) and shared type-agnostic infra (`TokenGenerator`, bot lifecycle). The starter creates **no** tables, **no** concrete entities, **no** concrete controllers. The host writes 6 subclasses + 1 `@Configuration` per user type; the default reg/auth flow works out of the box and any method can be `@Override`-n.

**Tech Stack:** Java 17, Spring Boot (provided-scope `spring-boot-starter-web`, `-data-jpa`), Jackson (`JsonNode`), JUnit 5 + AssertJ + Spring Boot Test, H2 (test).

## Global Constraints

- Java 17 (`maven.compiler.source/target=17`). No language features beyond 17.
- Spring Boot deps are `provided` scope — never assume they are transitive to the host.
- Starter must define **zero** `@Entity`, `@Table`, concrete `@RestController`, or DB tables.
- Package root: `io.github.dev_abdulhay.telegramauth`.
- Maven version moves `0.1.2` → `0.2.0` (breaking).
- Command handler signature is `java.util.function.Consumer<com.fasterxml.jackson.databind.JsonNode>`.
- Controller routing prefix lives ONLY on the host subclass `@RequestMapping`; table names live ONLY on the host subclass `@Table`. Neither appears in `TelegramBotModule`.
- Compile check: `mvn -q -DskipTests compile` → expect `BUILD SUCCESS`.
- Run one test class: `mvn -q -Dtest=<ClassName> test` → expect `BUILD SUCCESS`.

---

## File Structure

**Starter — created (new):**
- `entity/BaseTelegramUser.java` — `@MappedSuperclass`, user fields + `Status{PENDING,ACTIVE,BLOCKED}`.
- `entity/BaseAuthSession.java` — `@MappedSuperclass`, session fields + `Status{PENDING,APPROVED,REJECTED,EXPIRED}`.
- `repository/BaseTelegramUserRepository.java` — `@NoRepositoryBean`, `findByTelegramId`.
- `repository/BaseAuthSessionRepository.java` — `@NoRepositoryBean`, `findByTokenHash`, `findByStatusAndExpiresAtBefore`.
- `service/AbstractTelegramUserService.java` — generic register/find.
- `service/AbstractSessionService.java` — generic create/approve/reject/sweep.
- `web/AbstractTelegramAuthController.java` — generic REST endpoints (no `@RestController`).
- `bot/TelegramBot.java` — Telegram Bot API wrapper (rename + extend of `TelegramBotClient`).
- `bot/TelegramBotModule.java` — per-type config object + builder (owns bot + event bus + command registry).
- `bot/BotUpdateDispatcher.java` — module-driven update router (command + fallback). *(rewritten)*
- `bot/TelegramBotRunner.java` — per-module long-poll loop. *(rewritten)*
- `bot/TelegramBotLifecycle.java` — starts/stops one runner per `TelegramBotModule` bean.
- `flow/DefaultAuthFlow.java` — default `/start` handler; self-registers into its module.

**Starter — kept as-is (reused):**
- `security/TokenGenerator.java`, `service/AuthEvent.java`, `service/AuthEventBus.java`, `service/InMemoryAuthEventBus.java`.
- `api/TelegramAuthApproveHandler.java`, `api/dto/TelegramUserInfo.java`, `api/dto/AuthContext.java`, `api/dto/AuthApproveResult.java`.
- `web/dto/CreateSessionRequest.java`, `CreateSessionResponse.java`, `WaitResponse.java`, `SessionStatusResponse.java`.

**Starter — modified:**
- `config/TelegramAuthAutoConfiguration.java` — trimmed to `TokenGenerator` + `TelegramBotLifecycle`.
- `config/TelegramAuthProperties.java` — slimmed to `enabled` + `cleanupCron`.
- `pom.xml` — version `0.2.0`.

**Starter — deleted:**
- `entity/MTelegramUser.java`, `entity/MTelegramAuthSession.java`
- `repository/TelegramUserRepository.java`, `repository/TelegramAuthSessionRepository.java`
- `service/SessionService.java`, `service/TelegramUserService.java`
- `web/TelegramAuthController.java`, `config/TelegramAuthEntityScanConfig.java`
- `api/TelegramAuthRegisterHandler.java`, `api/dto/RegisterContext.java`
- `resources/db/changelog/telegram-auth-changelog.xml`
- `src/test/java/com/example/scantest/Foo.java`, `AdditiveEntityScanTest.java`

**Test support — created incrementally (under `src/test/java/com/example/demo`):**
- `DemoApp.java` (boot config), `DemoUser.java`, `DemoSession.java`, `DemoUserRepository.java`, `DemoSessionRepository.java`, `DemoUserService.java`, `DemoSessionService.java`, `DemoAuthController.java`, `DemoTgConfig.java`, plus per-task test classes.

---

### Task 1: Tear down v0.1.x concrete flow to a compiling reusable core

**Files:**
- Delete: `entity/MTelegramUser.java`, `entity/MTelegramAuthSession.java`, `repository/TelegramUserRepository.java`, `repository/TelegramAuthSessionRepository.java`, `service/SessionService.java`, `service/TelegramUserService.java`, `web/TelegramAuthController.java`, `config/TelegramAuthEntityScanConfig.java`, `api/TelegramAuthRegisterHandler.java`, `api/dto/RegisterContext.java`, `bot/BotUpdateDispatcher.java`, `bot/TelegramBotRunner.java`, `src/main/resources/db/changelog/telegram-auth-changelog.xml`, `src/test/java/com/example/scantest/Foo.java`, `src/test/java/com/example/scantest/AdditiveEntityScanTest.java`
- Rewrite: `config/TelegramAuthAutoConfiguration.java`, `config/TelegramAuthProperties.java`
- Modify: `pom.xml` (version)

**Interfaces:**
- Consumes: nothing.
- Produces: a compiling project exposing `TokenGenerator`, `AuthEvent`, `AuthEventBus`, `InMemoryAuthEventBus`, `TelegramBotClient`, all `api/dto` (minus `RegisterContext`) and `web/dto`, plus a slim `TelegramAuthProperties{enabled, cleanupCron}`.

- [ ] **Step 1: Delete obsolete files**

```bash
git rm src/main/java/io/github/dev_abdulhay/telegramauth/entity/MTelegramUser.java \
       src/main/java/io/github/dev_abdulhay/telegramauth/entity/MTelegramAuthSession.java \
       src/main/java/io/github/dev_abdulhay/telegramauth/repository/TelegramUserRepository.java \
       src/main/java/io/github/dev_abdulhay/telegramauth/repository/TelegramAuthSessionRepository.java \
       src/main/java/io/github/dev_abdulhay/telegramauth/service/SessionService.java \
       src/main/java/io/github/dev_abdulhay/telegramauth/service/TelegramUserService.java \
       src/main/java/io/github/dev_abdulhay/telegramauth/web/TelegramAuthController.java \
       src/main/java/io/github/dev_abdulhay/telegramauth/config/TelegramAuthEntityScanConfig.java \
       src/main/java/io/github/dev_abdulhay/telegramauth/api/TelegramAuthRegisterHandler.java \
       src/main/java/io/github/dev_abdulhay/telegramauth/api/dto/RegisterContext.java \
       src/main/java/io/github/dev_abdulhay/telegramauth/bot/BotUpdateDispatcher.java \
       src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotRunner.java \
       src/main/resources/db/changelog/telegram-auth-changelog.xml \
       src/test/java/com/example/scantest/Foo.java \
       src/test/java/com/example/scantest/AdditiveEntityScanTest.java
```

- [ ] **Step 2: Slim `TelegramAuthProperties`**

Replace the whole file with:

```java
package io.github.dev_abdulhay.telegramauth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Global, type-agnostic starter properties. Per-type settings (bot token,
 * session TTL, command registry) live in code via {@code TelegramBotModule}.
 */
@ConfigurationProperties(prefix = "telegram.auth")
public class TelegramAuthProperties {

    /** Master switch; auto-config stays inert if {@code false}. */
    private boolean enabled = false;

    /** Spring cron for the per-module expired-session sweep. */
    private String cleanupCron = "0 */5 * * * *";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getCleanupCron() { return cleanupCron; }
    public void setCleanupCron(String cleanupCron) { this.cleanupCron = cleanupCron; }
}
```

- [ ] **Step 3: Trim `TelegramAuthAutoConfiguration` to the reusable core**

Replace the whole file with:

```java
package io.github.dev_abdulhay.telegramauth.config;

import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates the starter when {@code telegram.auth.enabled=true}. Registers only
 * type-agnostic infrastructure; per-type beans (entities, repositories,
 * services, controllers, {@code TelegramBotModule}) are declared by the host.
 */
@AutoConfiguration(after = {HibernateJpaAutoConfiguration.class, JpaRepositoriesAutoConfiguration.class})
@ConditionalOnProperty(prefix = "telegram.auth", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TelegramAuthProperties.class)
@EnableScheduling
public class TelegramAuthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TokenGenerator telegramAuthTokenGenerator() {
        return new TokenGenerator();
    }
}
```

- [ ] **Step 4: Bump version in `pom.xml`**

Change the project version (the `<version>` directly under `<artifactId>telegram-auth-spring-boot-starter</artifactId>`):

```xml
<version>0.2.0</version>
```

- [ ] **Step 5: Verify the project compiles**

Run: `mvn -q -DskipTests compile`
Expected: `BUILD SUCCESS` (no references to deleted classes remain).

- [ ] **Step 6: Verify no stale test references and the test tree is clean**

Run: `mvn -q -DskipTests test-compile`
Expected: `BUILD SUCCESS` (scantest deleted; no other tests yet).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor!: tear down v0.1.x fixed flow to reusable core (0.2.0)"
```

---

### Task 2: Generic JPA layer — base entities + base repositories

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/entity/BaseTelegramUser.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/entity/BaseAuthSession.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/repository/BaseTelegramUserRepository.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/repository/BaseAuthSessionRepository.java`
- Test: `src/test/java/com/example/demo/DemoApp.java`, `DemoUser.java`, `DemoSession.java`, `DemoUserRepository.java`, `DemoSessionRepository.java`, `JpaLayerTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `BaseTelegramUser` fields via getters/setters: `Long id`, `Long telegramId`, `String phone/firstName/lastName/username/languageCode/externalUserId`, `Status status` (`PENDING|ACTIVE|BLOCKED`), `OffsetDateTime createdAt/updatedAt`.
  - `BaseAuthSession` fields: `Long id`, `String tokenHash`, `Long telegramUserId`, `Status status` (`PENDING|APPROVED|REJECTED|EXPIRED`), `String ipAddress/userAgent`, `OffsetDateTime createdAt/expiresAt/approvedAt`.
  - `BaseTelegramUserRepository<U> extends JpaRepository<U,Long>` with `Optional<U> findByTelegramId(Long)`.
  - `BaseAuthSessionRepository<S> extends JpaRepository<S,Long>` with `Optional<S> findByTokenHash(String)` and `List<S> findByStatusAndExpiresAtBefore(BaseAuthSession.Status, OffsetDateTime)`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/example/demo/DemoApp.java`:

```java
package com.example.demo;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

@SpringBootConfiguration
@EnableAutoConfiguration
public class DemoApp {
}
```

`src/test/java/com/example/demo/DemoUser.java`:

```java
package com.example.demo;

import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "demo_tg_user")
public class DemoUser extends BaseTelegramUser {
}
```

`src/test/java/com/example/demo/DemoSession.java`:

```java
package com.example.demo;

import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "demo_tg_session")
public class DemoSession extends BaseAuthSession {
}
```

`src/test/java/com/example/demo/DemoUserRepository.java`:

```java
package com.example.demo;

import io.github.dev_abdulhay.telegramauth.repository.BaseTelegramUserRepository;

public interface DemoUserRepository extends BaseTelegramUserRepository<DemoUser> {
}
```

`src/test/java/com/example/demo/DemoSessionRepository.java`:

```java
package com.example.demo;

import io.github.dev_abdulhay.telegramauth.repository.BaseAuthSessionRepository;

public interface DemoSessionRepository extends BaseAuthSessionRepository<DemoSession> {
}
```

`src/test/java/com/example/demo/JpaLayerTest.java`:

```java
package com.example.demo;

import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = DemoApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "telegram.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:jpalayer;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
class JpaLayerTest {

    @Autowired DemoUserRepository users;
    @Autowired DemoSessionRepository sessions;

    @Test
    void userRoundTripsAndFindsByTelegramId() {
        DemoUser u = new DemoUser();
        u.setTelegramId(42L);
        u.setFirstName("Ali");
        u.setStatus(BaseTelegramUser.Status.ACTIVE);
        users.save(u);

        assertThat(users.findByTelegramId(42L)).isPresent()
                .get().extracting(BaseTelegramUser::getFirstName).isEqualTo("Ali");
    }

    @Test
    void sessionFindsByTokenHashAndByExpiry() {
        DemoSession s = new DemoSession();
        s.setTokenHash("hash-1");
        s.setStatus(BaseAuthSession.Status.PENDING);
        s.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        sessions.save(s);

        assertThat(sessions.findByTokenHash("hash-1")).isPresent();
        List<DemoSession> overdue = sessions.findByStatusAndExpiresAtBefore(
                BaseAuthSession.Status.PENDING, OffsetDateTime.now());
        assertThat(overdue).hasSize(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=JpaLayerTest test`
Expected: FAIL — compilation error, `BaseTelegramUser`/`BaseAuthSession`/repositories do not exist.

- [ ] **Step 3: Implement the base entities and repositories**

`src/main/java/io/github/dev_abdulhay/telegramauth/entity/BaseTelegramUser.java`:

```java
package io.github.dev_abdulhay.telegramauth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

import java.time.OffsetDateTime;

/**
 * Base Telegram user. {@code @MappedSuperclass} — has no table of its own.
 * Host apps subclass with {@code @Entity @Table(name = "...")} per user type.
 */
@MappedSuperclass
public abstract class BaseTelegramUser {

    public enum Status { PENDING, ACTIVE, BLOCKED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "telegram_id", nullable = false, unique = true)
    private Long telegramId;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "username", length = 50)
    private String username;

    @Column(name = "language_code", length = 5)
    private String languageCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "external_user_id", length = 100)
    private String externalUserId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTelegramId() { return telegramId; }
    public void setTelegramId(Long telegramId) { this.telegramId = telegramId; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getLanguageCode() { return languageCode; }
    public void setLanguageCode(String languageCode) { this.languageCode = languageCode; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getExternalUserId() { return externalUserId; }
    public void setExternalUserId(String externalUserId) { this.externalUserId = externalUserId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
```

`src/main/java/io/github/dev_abdulhay/telegramauth/entity/BaseAuthSession.java`:

```java
package io.github.dev_abdulhay.telegramauth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

import java.time.OffsetDateTime;

/**
 * Base login session. {@code @MappedSuperclass} — host apps subclass with
 * {@code @Entity @Table(name = "...")} per user type.
 */
@MappedSuperclass
public abstract class BaseAuthSession {

    public enum Status { PENDING, APPROVED, REJECTED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "telegram_user_id")
    private Long telegramUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public Long getTelegramUserId() { return telegramUserId; }
    public void setTelegramUserId(Long telegramUserId) { this.telegramUserId = telegramUserId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(OffsetDateTime approvedAt) { this.approvedAt = approvedAt; }
}
```

`src/main/java/io/github/dev_abdulhay/telegramauth/repository/BaseTelegramUserRepository.java`:

```java
package io.github.dev_abdulhay.telegramauth.repository;

import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Base repository for any {@link BaseTelegramUser} subtype. Host repositories
 * extend this with their concrete entity: {@code interface AdminUserRepository
 * extends BaseTelegramUserRepository<AdminUser> {}}.
 */
@NoRepositoryBean
public interface BaseTelegramUserRepository<U extends BaseTelegramUser>
        extends JpaRepository<U, Long> {

    Optional<U> findByTelegramId(Long telegramId);
}
```

`src/main/java/io/github/dev_abdulhay/telegramauth/repository/BaseAuthSessionRepository.java`:

```java
package io.github.dev_abdulhay.telegramauth.repository;

import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Base repository for any {@link BaseAuthSession} subtype. Derived queries are
 * used (not JPQL) so the methods resolve against the concrete entity name.
 */
@NoRepositoryBean
public interface BaseAuthSessionRepository<S extends BaseAuthSession>
        extends JpaRepository<S, Long> {

    Optional<S> findByTokenHash(String tokenHash);

    List<S> findByStatusAndExpiresAtBefore(BaseAuthSession.Status status, OffsetDateTime time);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=JpaLayerTest test`
Expected: `BUILD SUCCESS`, both tests green.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: generic base entities and repositories"
```

---

### Task 3: Abstract user service

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/service/AbstractTelegramUserService.java`
- Test: `src/test/java/com/example/demo/DemoUserService.java`, `UserServiceTest.java`

**Interfaces:**
- Consumes: `BaseTelegramUserRepository<U>`, `BaseTelegramUser.Status`.
- Produces: `AbstractTelegramUserService<U>` with:
  - ctor `(BaseTelegramUserRepository<U> repo, Supplier<U> factory)`
  - `U register(Long telegramId, String phone, String firstName, String lastName, String username, String languageCode)` — upserts by telegramId, sets `status = ACTIVE`, returns saved entity.
  - `Optional<U> findByTelegramId(Long telegramId)`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/example/demo/DemoUserService.java`:

```java
package com.example.demo;

import io.github.dev_abdulhay.telegramauth.service.AbstractTelegramUserService;
import org.springframework.stereotype.Service;

@Service
public class DemoUserService extends AbstractTelegramUserService<DemoUser> {
    public DemoUserService(DemoUserRepository repo) {
        super(repo, DemoUser::new);
    }
}
```

`src/test/java/com/example/demo/UserServiceTest.java`:

```java
package com.example.demo;

import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = DemoApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "telegram.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:usersvc;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
class UserServiceTest {

    @Autowired DemoUserService service;

    @Test
    void registerCreatesActiveUserThenUpsertsSameRow() {
        DemoUser first = service.register(7L, "+998", "Ali", null, "ali", "uz");
        assertThat(first.getId()).isNotNull();
        assertThat(first.getStatus()).isEqualTo(BaseTelegramUser.Status.ACTIVE);

        DemoUser second = service.register(7L, "+998", "Ali Updated", null, "ali", "uz");
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getFirstName()).isEqualTo("Ali Updated");
        assertThat(service.findByTelegramId(7L)).isPresent();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=UserServiceTest test`
Expected: FAIL — `AbstractTelegramUserService` does not exist.

- [ ] **Step 3: Implement the abstract user service**

`src/main/java/io/github/dev_abdulhay/telegramauth/service/AbstractTelegramUserService.java`:

```java
package io.github.dev_abdulhay.telegramauth.service;

import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.repository.BaseTelegramUserRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Default user lifecycle, generic over the host's {@link BaseTelegramUser}
 * subtype. Generics cannot {@code new U()}, so a {@link Supplier} factory is
 * injected (host passes {@code MyUser::new}). Override any method to change
 * behaviour.
 */
public abstract class AbstractTelegramUserService<U extends BaseTelegramUser> {

    protected final BaseTelegramUserRepository<U> repo;
    private final Supplier<U> factory;

    protected AbstractTelegramUserService(BaseTelegramUserRepository<U> repo, Supplier<U> factory) {
        this.repo = repo;
        this.factory = factory;
    }

    /** Upsert by telegramId and mark the user ACTIVE. */
    @Transactional
    public U register(Long telegramId, String phone, String firstName,
                      String lastName, String username, String languageCode) {
        U user = repo.findByTelegramId(telegramId).orElseGet(factory);
        OffsetDateTime now = OffsetDateTime.now();
        if (user.getCreatedAt() == null) {
            user.setCreatedAt(now);
        }
        user.setTelegramId(telegramId);
        user.setPhone(phone);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setUsername(username);
        user.setLanguageCode(languageCode);
        user.setStatus(BaseTelegramUser.Status.ACTIVE);
        user.setUpdatedAt(now);
        return repo.save(user);
    }

    @Transactional(readOnly = true)
    public Optional<U> findByTelegramId(Long telegramId) {
        return repo.findByTelegramId(telegramId);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=UserServiceTest test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: abstract telegram user service"
```

---

### Task 4: `TelegramBot` API wrapper (rename + extend) and `TelegramBotModule` config object

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBot.java`
- Delete: `src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotClient.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotModule.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotModuleTest.java`

**Interfaces:**
- Consumes: `TelegramAuthApproveHandler`, `AuthEventBus`, `InMemoryAuthEventBus`, `AuthApproveResult`.
- Produces:
  - `TelegramBot` (non-final, overridable methods): ctor `(HttpClient http, String token)`; `String getUpdates(long offset, int timeoutSeconds) throws Exception`; `void sendMessage(long chatId, String text)`; `String maskedToken()`.
  - `TelegramBotModule` (final) with getters `getBotToken()`, `getUsername()`, `getSessionTtl()`, `getPollingTimeout()`, `getPollingInterval()`, `getApproveHandler()`, `getBot()`, `getBus()`, `getCommands()` (live `Map<String,Consumer<JsonNode>>`), `getFallback()`; mutators `command(String, Consumer<JsonNode>)`, `fallback(Consumer<JsonNode>)`.
  - `TelegramBotModule.builder(String token, String username)` → `Builder` with `.sessionTtl(Duration)`, `.pollingTimeout(Duration)`, `.pollingInterval(Duration)`, `.approveHandler(TelegramAuthApproveHandler)`, `.bot(TelegramBot)` (override for tests), `.build()`.

- [ ] **Step 1: Write the failing test**

`src/test/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotModuleTest.java`:

```java
package io.github.dev_abdulhay.telegramauth.bot;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dev_abdulhay.telegramauth.api.dto.AuthApproveResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramBotModuleTest {

    @Test
    void builderWiresDefaultsAndRegistry() {
        AtomicReference<JsonNode> seen = new AtomicReference<>();
        Consumer<JsonNode> handler = seen::set;

        TelegramBotModule m = TelegramBotModule.builder("123:ABCDEF", "demo_bot")
                .sessionTtl(Duration.ofMinutes(5))
                .approveHandler((info, ctx) -> new AuthApproveResult(Map.of("ok", true)))
                .build();
        m.command("/start", handler);

        assertThat(m.getUsername()).isEqualTo("demo_bot");
        assertThat(m.getSessionTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(m.getPollingTimeout()).isEqualTo(Duration.ofSeconds(30)); // default
        assertThat(m.getBot()).isNotNull();
        assertThat(m.getBus()).isNotNull();
        assertThat(m.getCommands()).containsKey("/start");
        assertThat(m.getApproveHandler().onApprove(null, null).payload()).containsEntry("ok", true);
    }

    @Test
    void customBotOverrideIsUsed() {
        TelegramBot fake = new TelegramBot(java.net.http.HttpClient.newHttpClient(), "x") {
            @Override public void sendMessage(long chatId, String text) { /* no network */ }
        };
        TelegramBotModule m = TelegramBotModule.builder("123:ABCDEF", "demo_bot")
                .bot(fake)
                .build();
        assertThat(m.getBot()).isSameAs(fake);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=TelegramBotModuleTest test`
Expected: FAIL — `TelegramBot` / `TelegramBotModule` do not exist.

- [ ] **Step 3: Create `TelegramBot` and delete `TelegramBotClient`**

`src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBot.java`:

```java
package io.github.dev_abdulhay.telegramauth.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Thin Telegram Bot API wrapper, one instance per {@link TelegramBotModule}.
 * Methods are overridable so hosts/tests can substitute behaviour.
 */
public class TelegramBot {

    private static final Logger log = LoggerFactory.getLogger(TelegramBot.class);

    private final HttpClient http;
    private final String token;
    private final String baseUrl;

    public TelegramBot(HttpClient http, String token) {
        this(http, token, "https://api.telegram.org");
    }

    public TelegramBot(HttpClient http, String token, String baseUrl) {
        this.http = http;
        this.token = token;
        this.baseUrl = baseUrl;
    }

    public String getUpdates(long offset, int timeoutSeconds) throws Exception {
        String url = baseUrl + "/bot" + token + "/getUpdates?offset=" + offset
                + "&timeout=" + timeoutSeconds;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds((long) timeoutSeconds + 5))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            log.warn("getUpdates non-2xx: {}", resp.statusCode());
        }
        return resp.body();
    }

    public void sendMessage(long chatId, String text) {
        try {
            String body = "chat_id=" + chatId + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/bot" + token + "/sendMessage"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("sendMessage failed", e);
        }
    }

    public String maskedToken() {
        if (token == null || token.length() < 12) return "***";
        return token.substring(0, 6) + "..." + token.substring(token.length() - 4);
    }
}
```

```bash
git rm src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotClient.java
```

- [ ] **Step 4: Create `TelegramBotModule`**

`src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotModule.java`:

```java
package io.github.dev_abdulhay.telegramauth.bot;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dev_abdulhay.telegramauth.api.TelegramAuthApproveHandler;
import io.github.dev_abdulhay.telegramauth.api.dto.AuthApproveResult;
import io.github.dev_abdulhay.telegramauth.service.AuthEventBus;
import io.github.dev_abdulhay.telegramauth.service.InMemoryAuthEventBus;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Per-user-type configuration object. The host builds one bean per type. It
 * owns that type's bot instance, its isolated event bus, and its command
 * registry. Routing prefix and table name are NOT here — they live on the host
 * subclass {@code @RequestMapping} / {@code @Table}.
 */
public final class TelegramBotModule {

    private final String botToken;
    private final String username;
    private final Duration sessionTtl;
    private final Duration pollingTimeout;
    private final Duration pollingInterval;
    private final TelegramAuthApproveHandler approveHandler;
    private final TelegramBot bot;
    private final AuthEventBus bus;

    private final Map<String, Consumer<JsonNode>> commands = new ConcurrentHashMap<>();
    private volatile Consumer<JsonNode> fallback;

    private TelegramBotModule(Builder b) {
        this.botToken = b.botToken;
        this.username = b.username;
        this.sessionTtl = b.sessionTtl;
        this.pollingTimeout = b.pollingTimeout;
        this.pollingInterval = b.pollingInterval;
        this.approveHandler = b.approveHandler;
        this.bot = (b.bot != null) ? b.bot : new TelegramBot(HttpClient.newHttpClient(), b.botToken);
        this.bus = (b.bus != null) ? b.bus : new InMemoryAuthEventBus();
    }

    public static Builder builder(String botToken, String username) {
        return new Builder(botToken, username);
    }

    /** Register or replace a command handler (e.g. {@code "/start"}). */
    public void command(String command, Consumer<JsonNode> handler) {
        commands.put(command, handler);
    }

    /** Handler for updates with no matching command (callback_query, contact, plain text). */
    public void fallback(Consumer<JsonNode> handler) {
        this.fallback = handler;
    }

    public String getBotToken() { return botToken; }
    public String getUsername() { return username; }
    public Duration getSessionTtl() { return sessionTtl; }
    public Duration getPollingTimeout() { return pollingTimeout; }
    public Duration getPollingInterval() { return pollingInterval; }
    public TelegramAuthApproveHandler getApproveHandler() { return approveHandler; }
    public TelegramBot getBot() { return bot; }
    public AuthEventBus getBus() { return bus; }
    public Map<String, Consumer<JsonNode>> getCommands() { return commands; }
    public Consumer<JsonNode> getFallback() { return fallback; }

    public static final class Builder {
        private final String botToken;
        private final String username;
        private Duration sessionTtl = Duration.ofMinutes(3);
        private Duration pollingTimeout = Duration.ofSeconds(30);
        private Duration pollingInterval = Duration.ofSeconds(1);
        private TelegramAuthApproveHandler approveHandler = (info, ctx) -> new AuthApproveResult(Map.of());
        private TelegramBot bot;
        private AuthEventBus bus;

        private Builder(String botToken, String username) {
            this.botToken = botToken;
            this.username = username;
        }

        public Builder sessionTtl(Duration v) { this.sessionTtl = v; return this; }
        public Builder pollingTimeout(Duration v) { this.pollingTimeout = v; return this; }
        public Builder pollingInterval(Duration v) { this.pollingInterval = v; return this; }
        public Builder approveHandler(TelegramAuthApproveHandler v) { this.approveHandler = v; return this; }
        public Builder bot(TelegramBot v) { this.bot = v; return this; }
        public Builder eventBus(AuthEventBus v) { this.bus = v; return this; }

        public TelegramBotModule build() { return new TelegramBotModule(this); }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q -Dtest=TelegramBotModuleTest test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: TelegramBot wrapper and per-type TelegramBotModule config"
```

---

### Task 5: Abstract session service

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/service/AbstractSessionService.java`
- Test: `src/test/java/com/example/demo/DemoSessionService.java`, `SessionServiceTest.java`

**Interfaces:**
- Consumes: `BaseAuthSessionRepository<S>`, `TokenGenerator`, `TelegramBotModule` (`getSessionTtl`, `getApproveHandler`, `getBus`), `BaseTelegramUser`, `AuthEvent`, `AuthContext`, `TelegramUserInfo`, `AuthApproveResult`, `TelegramAuthProperties.cleanupCron`.
- Produces: `AbstractSessionService<U,S>` with:
  - ctor `(BaseAuthSessionRepository<S> sessionRepo, Supplier<S> factory, TokenGenerator tokenGenerator, TelegramBotModule module)`
  - nested `record CreatedSession(String rawToken, BaseAuthSession entity)`
  - `CreatedSession create(String ip, String ua)`
  - `Optional<S> findByRawToken(String rawToken)`
  - `String hash(String rawToken)`
  - `void approve(String tokenHash, U user)`
  - `void reject(String tokenHash)`
  - `void sweepExpired()` (`@Scheduled`)

- [ ] **Step 1: Write the failing test**

`src/test/java/com/example/demo/DemoSessionService.java`:

```java
package com.example.demo;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import io.github.dev_abdulhay.telegramauth.service.AbstractSessionService;

public class DemoSessionService extends AbstractSessionService<DemoUser, DemoSession> {
    public DemoSessionService(DemoSessionRepository repo, TokenGenerator tg, TelegramBotModule module) {
        super(repo, DemoSession::new, tg, module);
    }
}
```

`src/test/java/com/example/demo/SessionServiceTest.java`:

```java
package com.example.demo;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBot;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import io.github.dev_abdulhay.telegramauth.service.AuthEvent;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SessionServiceTest {

    private TelegramBotModule module() {
        TelegramBot fake = new TelegramBot(HttpClient.newHttpClient(), "x") {
            @Override public void sendMessage(long chatId, String text) { }
        };
        return TelegramBotModule.builder("123:ABC", "demo_bot")
                .bot(fake)
                .sessionTtl(Duration.ofMinutes(3))
                .approveHandler((info, ctx) -> new io.github.dev_abdulhay.telegramauth.api.dto.AuthApproveResult(
                        Map.of("tgId", info.telegramId())))
                .build();
    }

    // In-memory stub repo so this stays a fast unit test.
    @Test
    void approvePublishesPayloadOnTheModuleBus() {
        TelegramBotModule module = module();
        StubSessionRepo repo = new StubSessionRepo();
        DemoSessionService svc = new DemoSessionService(repo, new TokenGenerator(), module);

        var created = svc.create("1.2.3.4", "JUnit");
        String hash = svc.hash(created.rawToken());

        AtomicReference<AuthEvent> got = new AtomicReference<>();
        module.getBus().subscribe(hash, got::set);

        DemoUser u = new DemoUser();
        u.setTelegramId(99L);
        svc.approve(hash, u);

        assertThat(got.get()).isNotNull();
        assertThat(got.get().type()).isEqualTo(AuthEvent.Type.APPROVED);
        assertThat(got.get().payload()).containsEntry("tgId", 99L);
        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(BaseAuthSession.Status.APPROVED);
    }
}
```

`src/test/java/com/example/demo/StubSessionRepo.java` (minimal in-memory repo — only the methods the service calls):

```java
package com.example.demo;

import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.repository.BaseAuthSessionRepository;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** Hand-written in-memory repo for unit tests. Only implements what the service uses. */
@SuppressWarnings({"unchecked", "ConstantConditions", "NullableProblems"})
public class StubSessionRepo implements BaseAuthSessionRepository<DemoSession> {

    private final Map<Long, DemoSession> store = new HashMap<>();
    private final AtomicLong seq = new AtomicLong(0);

    @Override public Optional<DemoSession> findByTokenHash(String tokenHash) {
        return store.values().stream().filter(s -> tokenHash.equals(s.getTokenHash())).findFirst();
    }
    @Override public List<DemoSession> findByStatusAndExpiresAtBefore(BaseAuthSession.Status status, OffsetDateTime time) {
        return store.values().stream()
                .filter(s -> s.getStatus() == status && s.getExpiresAt() != null && s.getExpiresAt().isBefore(time))
                .toList();
    }
    @Override public <Sx extends DemoSession> Sx save(Sx entity) {
        if (entity.getId() == null) entity.setId(seq.incrementAndGet());
        store.put(entity.getId(), entity);
        return entity;
    }
    @Override public Optional<DemoSession> findById(Long id) { return Optional.ofNullable(store.get(id)); }
    @Override public List<DemoSession> findAll() { return new ArrayList<>(store.values()); }
    @Override public <Sx extends DemoSession> List<Sx> saveAll(Iterable<Sx> entities) {
        List<Sx> out = new ArrayList<>(); entities.forEach(e -> out.add(save(e))); return out;
    }

    // --- unused JpaRepository surface: no-op / empty defaults ---
    @Override public void flush() { }
    @Override public <Sx extends DemoSession> Sx saveAndFlush(Sx entity) { return save(entity); }
    @Override public <Sx extends DemoSession> List<Sx> saveAllAndFlush(Iterable<Sx> entities) { return saveAll(entities); }
    @Override public void deleteAllInBatch(Iterable<DemoSession> entities) { }
    @Override public void deleteAllByIdInBatch(Iterable<Long> ids) { }
    @Override public void deleteAllInBatch() { store.clear(); }
    @Override public DemoSession getOne(Long id) { return store.get(id); }
    @Override public DemoSession getById(Long id) { return store.get(id); }
    @Override public DemoSession getReferenceById(Long id) { return store.get(id); }
    @Override public List<DemoSession> findAll(Sort sort) { return findAll(); }
    @Override public Page<DemoSession> findAll(Pageable pageable) { return Page.empty(); }
    @Override public List<DemoSession> findAllById(Iterable<Long> ids) { return new ArrayList<>(); }
    @Override public long count() { return store.size(); }
    @Override public void deleteById(Long id) { store.remove(id); }
    @Override public void delete(DemoSession entity) { store.remove(entity.getId()); }
    @Override public void deleteAllById(Iterable<? extends Long> ids) { ids.forEach(store::remove); }
    @Override public void deleteAll(Iterable<? extends DemoSession> entities) { }
    @Override public void deleteAll() { store.clear(); }
    @Override public boolean existsById(Long id) { return store.containsKey(id); }
    @Override public <Sx extends DemoSession> Optional<Sx> findOne(Example<Sx> ex) { return Optional.empty(); }
    @Override public <Sx extends DemoSession> List<Sx> findAll(Example<Sx> ex) { return new ArrayList<>(); }
    @Override public <Sx extends DemoSession> List<Sx> findAll(Example<Sx> ex, Sort sort) { return new ArrayList<>(); }
    @Override public <Sx extends DemoSession> Page<Sx> findAll(Example<Sx> ex, Pageable p) { return Page.empty(); }
    @Override public <Sx extends DemoSession> long count(Example<Sx> ex) { return 0; }
    @Override public <Sx extends DemoSession> boolean exists(Example<Sx> ex) { return false; }
    @Override public <Sx extends DemoSession, R> R findBy(Example<Sx> ex, Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<Sx>, R> fn) { return null; }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=SessionServiceTest test`
Expected: FAIL — `AbstractSessionService` does not exist.

- [ ] **Step 3: Implement the abstract session service**

`src/main/java/io/github/dev_abdulhay/telegramauth/service/AbstractSessionService.java`:

```java
package io.github.dev_abdulhay.telegramauth.service;

import io.github.dev_abdulhay.telegramauth.api.dto.AuthApproveResult;
import io.github.dev_abdulhay.telegramauth.api.dto.AuthContext;
import io.github.dev_abdulhay.telegramauth.api.dto.TelegramUserInfo;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession.Status;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.repository.BaseAuthSessionRepository;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Default session lifecycle, generic over the host's user/session subtypes.
 * Transport-agnostic: terminal transitions are published on the module's
 * {@link AuthEventBus}. Override any method to change behaviour.
 */
public abstract class AbstractSessionService<U extends BaseTelegramUser, S extends BaseAuthSession> {

    private static final Logger log = LoggerFactory.getLogger(AbstractSessionService.class);

    protected final BaseAuthSessionRepository<S> sessionRepo;
    private final Supplier<S> factory;
    private final TokenGenerator tokenGenerator;
    protected final TelegramBotModule module;

    protected AbstractSessionService(BaseAuthSessionRepository<S> sessionRepo,
                                     Supplier<S> factory,
                                     TokenGenerator tokenGenerator,
                                     TelegramBotModule module) {
        this.sessionRepo = sessionRepo;
        this.factory = factory;
        this.tokenGenerator = tokenGenerator;
        this.module = module;
    }

    public record CreatedSession(String rawToken, BaseAuthSession entity) {}

    @Transactional
    public CreatedSession create(String ipAddress, String userAgent) {
        String raw = tokenGenerator.newToken();
        S s = factory.get();
        s.setTokenHash(tokenGenerator.hash(raw));
        s.setIpAddress(ipAddress);
        s.setUserAgent(userAgent);
        s.setCreatedAt(OffsetDateTime.now());
        s.setExpiresAt(s.getCreatedAt().plus(module.getSessionTtl()));
        s.setStatus(Status.PENDING);
        sessionRepo.save(s);
        return new CreatedSession(raw, s);
    }

    @Transactional(readOnly = true)
    public Optional<S> findByRawToken(String rawToken) {
        return sessionRepo.findByTokenHash(tokenGenerator.hash(rawToken));
    }

    public String hash(String rawToken) {
        return tokenGenerator.hash(rawToken);
    }

    @Transactional
    public void approve(String tokenHash, U user) {
        S s = sessionRepo.findByTokenHash(tokenHash).orElse(null);
        if (s == null || s.getStatus() != Status.PENDING) {
            log.debug("approve: session not found or not pending");
            return;
        }
        if (s.getExpiresAt().isBefore(OffsetDateTime.now())) {
            s.setStatus(Status.EXPIRED);
            sessionRepo.save(s);
            module.getBus().publish(tokenHash, AuthEvent.expired());
            return;
        }

        AuthContext ctx = new AuthContext(s.getIpAddress(), s.getUserAgent());
        TelegramUserInfo info = new TelegramUserInfo(
                user.getTelegramId(), user.getPhone(), user.getFirstName(),
                user.getLastName(), user.getUsername(), user.getLanguageCode(),
                user.getExternalUserId());

        AuthApproveResult result;
        try {
            result = module.getApproveHandler().onApprove(info, ctx);
        } catch (RuntimeException e) {
            log.error("host approve handler threw", e);
            throw e;
        }

        s.setStatus(Status.APPROVED);
        s.setApprovedAt(OffsetDateTime.now());
        s.setTelegramUserId(user.getId());
        sessionRepo.save(s);
        module.getBus().publish(tokenHash, AuthEvent.approved(result.payload()));
    }

    @Transactional
    public void reject(String tokenHash) {
        S s = sessionRepo.findByTokenHash(tokenHash).orElse(null);
        if (s == null || s.getStatus() != Status.PENDING) return;
        s.setStatus(Status.REJECTED);
        sessionRepo.save(s);
        module.getBus().publish(tokenHash, AuthEvent.rejected());
    }

    @Scheduled(cron = "${telegram.auth.cleanup-cron:0 */5 * * * *}")
    @Transactional
    public void sweepExpired() {
        List<S> overdue = sessionRepo.findByStatusAndExpiresAtBefore(Status.PENDING, OffsetDateTime.now());
        if (overdue.isEmpty()) return;
        overdue.forEach(s -> s.setStatus(Status.EXPIRED));
        sessionRepo.saveAll(overdue);
        log.info("expired sessions swept: {}", overdue.size());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=SessionServiceTest test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: abstract session service with module-scoped bus and sweep"
```

---

### Task 6: Update dispatcher (command + fallback routing)

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/bot/BotUpdateDispatcher.java`
- Test: `src/test/java/io/github/dev_abdulhay/telegramauth/bot/BotUpdateDispatcherTest.java`

**Interfaces:**
- Consumes: `TelegramBotModule` (`getCommands`, `getFallback`).
- Produces: `BotUpdateDispatcher` with ctor `(TelegramBotModule module)` and `long dispatch(String json)` — returns the highest `update_id` seen (0 if none). Routes by the first whitespace-delimited token of `message.text` when it starts with `/` (stripping any `@botname` suffix); otherwise (and for unknown commands) invokes the module fallback if present. Passes the full update `JsonNode` to the handler.

- [ ] **Step 1: Write the failing test**

`src/test/java/io/github/dev_abdulhay/telegramauth/bot/BotUpdateDispatcherTest.java`:

```java
package io.github.dev_abdulhay.telegramauth.bot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BotUpdateDispatcherTest {

    private TelegramBotModule module() {
        return TelegramBotModule.builder("123:ABC", "demo_bot")
                .bot(new TelegramBot(java.net.http.HttpClient.newHttpClient(), "x") {
                    @Override public void sendMessage(long chatId, String text) { }
                })
                .build();
    }

    @Test
    void routesCommandStrippingArgsAndBotSuffix() {
        TelegramBotModule m = module();
        AtomicReference<JsonNode> seen = new AtomicReference<>();
        m.command("/start", seen::set);
        BotUpdateDispatcher d = new BotUpdateDispatcher(m);

        String json = "{\"ok\":true,\"result\":[{\"update_id\":10,"
                + "\"message\":{\"text\":\"/start@demo_bot TOKEN123\",\"chat\":{\"id\":5}}}]}";
        long maxId = d.dispatch(json);

        assertThat(maxId).isEqualTo(10);
        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().path("message").path("chat").path("id").asLong()).isEqualTo(5);
    }

    @Test
    void unknownAndNonCommandUpdatesGoToFallback() {
        TelegramBotModule m = module();
        AtomicReference<JsonNode> fb = new AtomicReference<>();
        m.fallback(fb::set);
        BotUpdateDispatcher d = new BotUpdateDispatcher(m);

        String json = "{\"ok\":true,\"result\":[{\"update_id\":3,"
                + "\"message\":{\"text\":\"hello there\",\"chat\":{\"id\":9}}}]}";
        long maxId = d.dispatch(json);

        assertThat(maxId).isEqualTo(3);
        assertThat(fb.get()).isNotNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=BotUpdateDispatcherTest test`
Expected: FAIL — `BotUpdateDispatcher` does not exist.

- [ ] **Step 3: Implement the dispatcher**

`src/main/java/io/github/dev_abdulhay/telegramauth/bot/BotUpdateDispatcher.java`:

```java
package io.github.dev_abdulhay.telegramauth.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Parses a Telegram {@code getUpdates} response and routes each update through
 * its module's command registry, falling back to the module fallback handler.
 * Handlers receive the full update {@link JsonNode}.
 */
public class BotUpdateDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BotUpdateDispatcher.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final TelegramBotModule module;

    public BotUpdateDispatcher(TelegramBotModule module) {
        this.module = module;
    }

    /** Returns the highest update_id seen in the batch, or 0 if empty/invalid. */
    public long dispatch(String json) {
        long maxId = 0;
        try {
            JsonNode root = mapper.readTree(json);
            if (!root.path("ok").asBoolean(false)) {
                log.debug("non-ok getUpdates response");
                return 0;
            }
            for (JsonNode update : root.path("result")) {
                maxId = Math.max(maxId, update.path("update_id").asLong());
                route(update);
            }
        } catch (Exception e) {
            log.warn("dispatch failed", e);
        }
        return maxId;
    }

    private void route(JsonNode update) {
        String text = update.path("message").path("text").asText("");
        if (text.startsWith("/")) {
            String command = parseCommand(text);
            Consumer<JsonNode> handler = module.getCommands().get(command);
            if (handler != null) {
                invoke(handler, update);
                return;
            }
        }
        Consumer<JsonNode> fallback = module.getFallback();
        if (fallback != null) {
            invoke(fallback, update);
        }
    }

    /** "/start@bot ARG" -> "/start". */
    private static String parseCommand(String text) {
        int space = text.indexOf(' ');
        String token = (space >= 0) ? text.substring(0, space) : text;
        int at = token.indexOf('@');
        return (at >= 0) ? token.substring(0, at) : token;
    }

    private void invoke(Consumer<JsonNode> handler, JsonNode update) {
        try {
            handler.accept(update);
        } catch (RuntimeException e) {
            log.warn("update handler threw", e);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=BotUpdateDispatcherTest test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: module-driven update dispatcher (command + fallback)"
```

---

### Task 7: Default auth flow

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/flow/DefaultAuthFlow.java`
- Test: `src/test/java/com/example/demo/DefaultAuthFlowTest.java`

**Interfaces:**
- Consumes: `AbstractTelegramUserService<U>`, `AbstractSessionService<U,S>`, `TelegramBotModule`, `BaseTelegramUser`.
- Produces: `DefaultAuthFlow<U,S>` with ctor `(AbstractTelegramUserService<U> userService, AbstractSessionService<U,S> sessionService, TelegramBotModule module)` that **self-registers** `module.command("/start", this::onStart)`. Method `void onStart(JsonNode update)` parses `/start <token>`, upserts the user from `message.from`, approves the session, and sends a confirmation via `module.getBot()`. Overridable.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/example/demo/DefaultAuthFlowTest.java`:

```java
package com.example.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBot;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.flow.DefaultAuthFlow;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAuthFlowTest {

    @Test
    void onStartRegistersUserAndApprovesSession() throws Exception {
        AtomicReference<String> sent = new AtomicReference<>();
        TelegramBot fake = new TelegramBot(HttpClient.newHttpClient(), "x") {
            @Override public void sendMessage(long chatId, String text) { sent.set(text); }
        };
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "demo_bot")
                .bot(fake)
                .approveHandler((info, ctx) -> new io.github.dev_abdulhay.telegramauth.api.dto.AuthApproveResult(
                        Map.of("tgId", info.telegramId())))
                .build();

        StubUserRepo userRepo = new StubUserRepo();
        StubSessionRepo sessionRepo = new StubSessionRepo();
        DemoUserService userService = new DemoUserService(userRepo);
        DemoSessionService sessionService = new DemoSessionService(sessionRepo, new TokenGenerator(), module);

        // self-registration happens in the ctor
        new DefaultAuthFlow<>(userService, sessionService, module);
        assertThat(module.getCommands()).containsKey("/start");

        var created = sessionService.create("ip", "ua");
        String raw = created.rawToken();

        String update = new ObjectMapper().writeValueAsString(Map.of(
                "message", Map.of(
                        "text", "/start " + raw,
                        "chat", Map.of("id", 555L),
                        "from", Map.of("id", 555L, "first_name", "Ali", "language_code", "uz"))));

        module.getCommands().get("/start").accept(new ObjectMapper().readTree(update));

        assertThat(userService.findByTelegramId(555L)).isPresent();
        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(BaseAuthSession.Status.APPROVED);
        assertThat(sent.get()).isNotBlank();
    }
}
```

`src/test/java/com/example/demo/StubUserRepo.java` (in-memory user repo, same pattern as `StubSessionRepo`):

```java
package com.example.demo;

import io.github.dev_abdulhay.telegramauth.repository.BaseTelegramUserRepository;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

@SuppressWarnings({"unchecked", "ConstantConditions", "NullableProblems"})
public class StubUserRepo implements BaseTelegramUserRepository<DemoUser> {

    private final Map<Long, DemoUser> store = new HashMap<>();
    private final AtomicLong seq = new AtomicLong(0);

    @Override public Optional<DemoUser> findByTelegramId(Long telegramId) {
        return store.values().stream().filter(u -> telegramId.equals(u.getTelegramId())).findFirst();
    }
    @Override public <Sx extends DemoUser> Sx save(Sx entity) {
        if (entity.getId() == null) entity.setId(seq.incrementAndGet());
        store.put(entity.getId(), entity);
        return entity;
    }
    @Override public Optional<DemoUser> findById(Long id) { return Optional.ofNullable(store.get(id)); }
    @Override public List<DemoUser> findAll() { return new ArrayList<>(store.values()); }
    @Override public <Sx extends DemoUser> List<Sx> saveAll(Iterable<Sx> entities) {
        List<Sx> out = new ArrayList<>(); entities.forEach(e -> out.add(save(e))); return out;
    }

    @Override public void flush() { }
    @Override public <Sx extends DemoUser> Sx saveAndFlush(Sx entity) { return save(entity); }
    @Override public <Sx extends DemoUser> List<Sx> saveAllAndFlush(Iterable<Sx> entities) { return saveAll(entities); }
    @Override public void deleteAllInBatch(Iterable<DemoUser> entities) { }
    @Override public void deleteAllByIdInBatch(Iterable<Long> ids) { }
    @Override public void deleteAllInBatch() { store.clear(); }
    @Override public DemoUser getOne(Long id) { return store.get(id); }
    @Override public DemoUser getById(Long id) { return store.get(id); }
    @Override public DemoUser getReferenceById(Long id) { return store.get(id); }
    @Override public List<DemoUser> findAll(Sort sort) { return findAll(); }
    @Override public Page<DemoUser> findAll(Pageable pageable) { return Page.empty(); }
    @Override public List<DemoUser> findAllById(Iterable<Long> ids) { return new ArrayList<>(); }
    @Override public long count() { return store.size(); }
    @Override public void deleteById(Long id) { store.remove(id); }
    @Override public void delete(DemoUser entity) { store.remove(entity.getId()); }
    @Override public void deleteAllById(Iterable<? extends Long> ids) { ids.forEach(store::remove); }
    @Override public void deleteAll(Iterable<? extends DemoUser> entities) { }
    @Override public void deleteAll() { store.clear(); }
    @Override public boolean existsById(Long id) { return store.containsKey(id); }
    @Override public <Sx extends DemoUser> Optional<Sx> findOne(Example<Sx> ex) { return Optional.empty(); }
    @Override public <Sx extends DemoUser> List<Sx> findAll(Example<Sx> ex) { return new ArrayList<>(); }
    @Override public <Sx extends DemoUser> List<Sx> findAll(Example<Sx> ex, Sort sort) { return new ArrayList<>(); }
    @Override public <Sx extends DemoUser> Page<Sx> findAll(Example<Sx> ex, Pageable p) { return Page.empty(); }
    @Override public <Sx extends DemoUser> long count(Example<Sx> ex) { return 0; }
    @Override public <Sx extends DemoUser> boolean exists(Example<Sx> ex) { return false; }
    @Override public <Sx extends DemoUser, R> R findBy(Example<Sx> ex, Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<Sx>, R> fn) { return null; }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=DefaultAuthFlowTest test`
Expected: FAIL — `DefaultAuthFlow` does not exist.

- [ ] **Step 3: Implement the default flow**

`src/main/java/io/github/dev_abdulhay/telegramauth/flow/DefaultAuthFlow.java`:

```java
package io.github.dev_abdulhay.telegramauth.flow;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.service.AbstractSessionService;
import io.github.dev_abdulhay.telegramauth.service.AbstractTelegramUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Default reg/auth flow. Self-registers its {@code /start} handler into the
 * module on construction, so declaring this bean is enough to get a working
 * login. Subclass and {@code @Override} {@link #onStart} to customise.
 *
 * <p>MVP: {@code /start <token>} auto-registers the user from message metadata
 * and approves the session. Contact-share and inline approve/reject are future
 * work; route them via additional commands or the module fallback.
 */
public class DefaultAuthFlow<U extends BaseTelegramUser, S extends BaseAuthSession> {

    private static final Logger log = LoggerFactory.getLogger(DefaultAuthFlow.class);
    private static final String START = "/start ";

    protected final AbstractTelegramUserService<U> userService;
    protected final AbstractSessionService<U, S> sessionService;
    protected final TelegramBotModule module;

    public DefaultAuthFlow(AbstractTelegramUserService<U> userService,
                           AbstractSessionService<U, S> sessionService,
                           TelegramBotModule module) {
        this.userService = userService;
        this.sessionService = sessionService;
        this.module = module;
        module.command("/start", this::onStart);
    }

    public void onStart(JsonNode update) {
        JsonNode message = update.path("message");
        long chatId = message.path("chat").path("id").asLong();
        String text = message.path("text").asText("");
        String rawToken = text.length() > START.length() ? text.substring(START.length()).trim() : "";

        Optional<S> session = rawToken.isEmpty() ? Optional.empty() : sessionService.findByRawToken(rawToken);
        if (session.isEmpty()) {
            module.getBot().sendMessage(chatId, "Havola yaroqsiz yoki muddati tugagan.");
            return;
        }

        U user = userService.findByTelegramId(chatId).orElse(null);
        if (user == null || user.getStatus() != BaseTelegramUser.Status.ACTIVE) {
            JsonNode from = message.path("from");
            user = userService.register(
                    chatId,
                    null,
                    from.path("first_name").asText(null),
                    from.path("last_name").asText(null),
                    from.path("username").asText(null),
                    from.path("language_code").asText("uz"));
        }

        sessionService.approve(sessionService.hash(rawToken), user);
        module.getBot().sendMessage(chatId, "Tasdiqlandi. Web saytga qayting.");
        log.debug("default flow approved chatId={}", chatId);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=DefaultAuthFlowTest test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: default /start auth flow with self-registration"
```

---

### Task 8: Abstract REST controller

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/web/AbstractTelegramAuthController.java`
- Test: `src/test/java/com/example/demo/DemoAuthController.java`, `DemoTgConfig.java`, `ControllerFlowTest.java`

**Interfaces:**
- Consumes: `AbstractSessionService<U,S>`, `TelegramBotModule` (`getBus`, `getUsername`), DTOs `CreateSessionRequest`, `CreateSessionResponse`, `WaitResponse`, `SessionStatusResponse`, `AuthEvent`.
- Produces: `AbstractTelegramAuthController<U,S>` (no `@RestController`) with ctor `(AbstractSessionService<U,S> sessionService, TelegramBotModule module)` and handler methods `@PostMapping("/session")`, `@GetMapping("/session/{token}/poll")`, `@GetMapping("/session/{token}/status")`, `@DeleteMapping("/session/{token}")`. Host subclass adds `@RestController @RequestMapping("/...")`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/example/demo/DemoAuthController.java`:

```java
package com.example.demo;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.web.AbstractTelegramAuthController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo-auth")
public class DemoAuthController extends AbstractTelegramAuthController<DemoUser, DemoSession> {
    public DemoAuthController(DemoSessionService service, TelegramBotModule module) {
        super(service, module);
    }
}
```

`src/test/java/com/example/demo/DemoTgConfig.java`:

```java
package com.example.demo;

import io.github.dev_abdulhay.telegramauth.api.dto.AuthApproveResult;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBot;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.flow.DefaultAuthFlow;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.util.Map;

@Configuration
public class DemoTgConfig {

    @Bean
    TelegramBotModule demoModule() {
        // Custom bot avoids real network during tests.
        TelegramBot fakeBot = new TelegramBot(HttpClient.newHttpClient(), "TEST") {
            @Override public void sendMessage(long chatId, String text) { }
        };
        return TelegramBotModule.builder("TEST", "demo_bot")
                .bot(fakeBot)
                .approveHandler((info, ctx) -> new AuthApproveResult(Map.of("tgId", info.telegramId())))
                .build();
    }

    @Bean
    DemoUserService demoUserService(DemoUserRepository repo) {
        return new DemoUserService(repo);
    }

    @Bean
    DemoSessionService demoSessionService(DemoSessionRepository repo, TokenGenerator tg, TelegramBotModule module) {
        return new DemoSessionService(repo, tg, module);
    }

    @Bean
    DefaultAuthFlow<DemoUser, DemoSession> demoFlow(DemoUserService us, DemoSessionService ss, TelegramBotModule module) {
        return new DefaultAuthFlow<>(us, ss, module);
    }
}
```

`src/test/java/com/example/demo/ControllerFlowTest.java`:

```java
package com.example.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = DemoApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "telegram.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:ctrlflow;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
class ControllerFlowTest {

    @Autowired MockMvc mvc;
    @Autowired DemoSessionService sessionService;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void createThenApproveReleasesPollWithPayload() throws Exception {
        MvcResult createRes = mvc.perform(post("/api/demo-auth/session"))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = json.readTree(createRes.getResponse().getContentAsString());
        String token = body.get("token").asText();
        assertThat(body.get("botDeepLink").asText()).contains("t.me/demo_bot?start=");

        // Start the long-poll (async), then approve out-of-band.
        MvcResult async = mvc.perform(get("/api/demo-auth/session/{t}/poll", token))
                .andExpect(request -> assertThat(request.getRequest().isAsyncStarted()).isTrue())
                .andReturn();

        DemoUser u = new DemoUser();
        u.setTelegramId(123L);
        sessionService.approve(sessionService.hash(token), u);

        MvcResult done = mvc.perform(asyncDispatch(async)).andExpect(status().isOk()).andReturn();
        JsonNode wait = json.readTree(done.getResponse().getContentAsString());
        assertThat(wait.get("status").asText()).isEqualTo("APPROVED");
        assertThat(wait.get("payload").get("tgId").asLong()).isEqualTo(123L);
    }

    @Test
    void statusOfMissingTokenIsGone() throws Exception {
        mvc.perform(get("/api/demo-auth/session/{t}/status", "nope")).andExpect(status().isGone());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=ControllerFlowTest test`
Expected: FAIL — `AbstractTelegramAuthController` does not exist.

- [ ] **Step 3: Implement the abstract controller**

`src/main/java/io/github/dev_abdulhay/telegramauth/web/AbstractTelegramAuthController.java`:

```java
package io.github.dev_abdulhay.telegramauth.web;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession.Status;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.service.AbstractSessionService;
import io.github.dev_abdulhay.telegramauth.service.AuthEvent;
import io.github.dev_abdulhay.telegramauth.web.dto.CreateSessionRequest;
import io.github.dev_abdulhay.telegramauth.web.dto.CreateSessionResponse;
import io.github.dev_abdulhay.telegramauth.web.dto.SessionStatusResponse;
import io.github.dev_abdulhay.telegramauth.web.dto.WaitResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Default REST surface, generic over the host's user/session subtypes. NOT a
 * bean itself — the host subclass adds {@code @RestController} and the routing
 * {@code @RequestMapping(prefix)}. Spring picks up these inherited handler
 * annotations. Override any method to change behaviour.
 */
public abstract class AbstractTelegramAuthController<U extends BaseTelegramUser, S extends BaseAuthSession> {

    protected final AbstractSessionService<U, S> sessionService;
    protected final TelegramBotModule module;

    protected AbstractTelegramAuthController(AbstractSessionService<U, S> sessionService, TelegramBotModule module) {
        this.sessionService = sessionService;
        this.module = module;
    }

    @PostMapping("/session")
    public CreateSessionResponse create(@RequestBody(required = false) CreateSessionRequest body,
                                        HttpServletRequest req) {
        String ip = clientIp(req);
        String ua = req.getHeader("User-Agent");
        AbstractSessionService.CreatedSession created = sessionService.create(ip, ua);
        String deepLink = "https://t.me/" + module.getUsername() + "?start=" + created.rawToken();
        return new CreateSessionResponse(
                created.rawToken(), deepLink, created.entity().getExpiresAt(), List.of("POLL"));
    }

    @GetMapping("/session/{token}/poll")
    public DeferredResult<ResponseEntity<WaitResponse>> poll(@PathVariable String token) {
        String hash = sessionService.hash(token);
        S s = sessionService.findByRawToken(token).orElse(null);
        if (s == null) {
            return immediate(ResponseEntity.status(HttpStatus.GONE).build());
        }
        if (s.getStatus() == Status.APPROVED) {
            return immediate(ResponseEntity.ok(new WaitResponse("APPROVED", Map.of())));
        }
        if (s.getStatus() == Status.REJECTED) {
            return immediate(ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new WaitResponse("REJECTED", Map.of())));
        }
        if (s.getStatus() == Status.EXPIRED || s.getExpiresAt().isBefore(OffsetDateTime.now())) {
            return immediate(ResponseEntity.status(HttpStatus.GONE).build());
        }

        long remainingMs = Duration.between(OffsetDateTime.now(), s.getExpiresAt()).toMillis();
        long timeoutMs = Math.min(module.getPollingTimeout().toMillis(), Math.max(remainingMs, 0));

        DeferredResult<ResponseEntity<WaitResponse>> result = new DeferredResult<>(timeoutMs);
        result.onTimeout(() -> result.setResult(ResponseEntity.noContent().build()));

        Consumer<AuthEvent> listener = ev -> {
            ResponseEntity<WaitResponse> resp = switch (ev.type()) {
                case APPROVED -> ResponseEntity.ok(new WaitResponse("APPROVED", ev.payload()));
                case REJECTED -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(new WaitResponse("REJECTED", Map.of()));
                case EXPIRED -> ResponseEntity.status(HttpStatus.GONE).build();
            };
            result.setResult(resp);
        };
        module.getBus().subscribe(hash, listener);
        result.onCompletion(() -> module.getBus().unsubscribe(hash, listener));
        return result;
    }

    @GetMapping("/session/{token}/status")
    public ResponseEntity<SessionStatusResponse> status(@PathVariable String token) {
        return sessionService.findByRawToken(token)
                .map(s -> ResponseEntity.ok(new SessionStatusResponse(s.getStatus().name(), s.getExpiresAt())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.GONE).build());
    }

    @DeleteMapping("/session/{token}")
    public ResponseEntity<Void> cancel(@PathVariable String token) {
        sessionService.findByRawToken(token).ifPresent(s -> {
            if (s.getStatus() == Status.PENDING) {
                sessionService.reject(s.getTokenHash());
            }
        });
        return ResponseEntity.noContent().build();
    }

    private static <T> DeferredResult<T> immediate(T value) {
        DeferredResult<T> dr = new DeferredResult<>();
        dr.setResult(value);
        return dr;
    }

    private static String clientIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            return (comma >= 0 ? fwd.substring(0, comma) : fwd).trim();
        }
        return req.getRemoteAddr();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=ControllerFlowTest test`
Expected: `BUILD SUCCESS`, both tests green.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: abstract telegram auth controller (host subclass adds prefix)"
```

---

### Task 9: Bot lifecycle, runner rework, and auto-configuration wiring

**Files:**
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotRunner.java`
- Create: `src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotLifecycle.java`
- Modify: `src/main/java/io/github/dev_abdulhay/telegramauth/config/TelegramAuthAutoConfiguration.java`
- Test: `src/test/java/com/example/demo/AutoConfigWiringTest.java`

**Interfaces:**
- Consumes: `TelegramBotModule`, `BotUpdateDispatcher`, `TelegramBot`.
- Produces:
  - `TelegramBotRunner` with ctor `(TelegramBotModule module)`, methods `void start()` (no-op on blank token) and `void stop()`.
  - `TelegramBotLifecycle` with ctor `(ObjectProvider<TelegramBotModule> modules)`, `@EventListener(ApplicationReadyEvent.class) void startAll()`, `@PreDestroy void stopAll()`.
  - `TelegramAuthAutoConfiguration` additionally declares `TelegramBotLifecycle` bean.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/example/demo/AutoConfigWiringTest.java`:

```java
package com.example.demo;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotLifecycle;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = DemoApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "telegram.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:wiring;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
class AutoConfigWiringTest {

    @Autowired(required = false) TokenGenerator tokenGenerator;
    @Autowired(required = false) TelegramBotLifecycle lifecycle;
    @Autowired DemoSessionService sessionService; // proves host beans wire via TokenGenerator

    @Test
    void starterInfraBeansArePresent() {
        assertThat(tokenGenerator).isNotNull();
        assertThat(lifecycle).isNotNull();
        assertThat(sessionService).isNotNull();
    }
}
```

(Note: `DemoTgConfig` supplies the module with a fake bot — token `"TEST"` is non-blank so the lifecycle attempts a poll thread; the runner's getUpdates uses the fake bot, no real network. The context must start cleanly and shut down without hanging.)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=AutoConfigWiringTest test`
Expected: FAIL — `TelegramBotLifecycle` does not exist.

- [ ] **Step 3: Implement the runner**

`src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotRunner.java`:

```java
package io.github.dev_abdulhay.telegramauth.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns one long-poll loop for a single {@link TelegramBotModule}. Pulls updates
 * and hands raw JSON to a {@link BotUpdateDispatcher}.
 */
public class TelegramBotRunner {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotRunner.class);

    private final TelegramBotModule module;
    private final BotUpdateDispatcher dispatcher;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong offset = new AtomicLong(0);
    private ExecutorService executor;

    public TelegramBotRunner(TelegramBotModule module) {
        this.module = module;
        this.dispatcher = new BotUpdateDispatcher(module);
    }

    public void start() {
        String token = module.getBotToken();
        if (token == null || token.isBlank()) {
            log.warn("bot token blank for @{} — polling disabled", module.getUsername());
            return;
        }
        if (!running.compareAndSet(false, true)) return;
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tg-auth-poll-" + module.getUsername());
            t.setDaemon(true);
            return t;
        });
        executor.submit(this::loop);
        log.info("Telegram polling started for @{}, token={}", module.getUsername(), module.getBot().maskedToken());
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (executor != null) executor.shutdownNow();
        log.info("Telegram polling stopped for @{}", module.getUsername());
    }

    private void loop() {
        int timeoutS = (int) module.getPollingTimeout().toSeconds();
        while (running.get()) {
            try {
                String json = module.getBot().getUpdates(offset.get(), timeoutS);
                long maxId = dispatcher.dispatch(json);
                if (maxId > 0) offset.set(maxId + 1);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("getUpdates failed for @{}; backing off", module.getUsername(), e);
                try {
                    Thread.sleep(module.getPollingInterval().toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}
```

- [ ] **Step 4: Implement the lifecycle**

`src/main/java/io/github/dev_abdulhay/telegramauth/bot/TelegramBotLifecycle.java`:

```java
package io.github.dev_abdulhay.telegramauth.bot;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Starts one {@link TelegramBotRunner} per registered {@link TelegramBotModule}
 * once the application is ready, and stops them all on shutdown.
 */
public class TelegramBotLifecycle {

    private final ObjectProvider<TelegramBotModule> modules;
    private final List<TelegramBotRunner> runners = new CopyOnWriteArrayList<>();

    public TelegramBotLifecycle(ObjectProvider<TelegramBotModule> modules) {
        this.modules = modules;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startAll() {
        modules.forEach(module -> {
            TelegramBotRunner runner = new TelegramBotRunner(module);
            runner.start();
            runners.add(runner);
        });
    }

    @PreDestroy
    public void stopAll() {
        runners.forEach(TelegramBotRunner::stop);
        runners.clear();
    }
}
```

- [ ] **Step 5: Register the lifecycle bean in auto-config**

In `config/TelegramAuthAutoConfiguration.java`, add imports and a bean. Add to imports:

```java
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotLifecycle;
import org.springframework.beans.factory.ObjectProvider;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
```

Add inside the class body, after the `telegramAuthTokenGenerator` bean:

```java
    @Bean
    @ConditionalOnMissingBean
    public TelegramBotLifecycle telegramBotLifecycle(ObjectProvider<TelegramBotModule> modules) {
        return new TelegramBotLifecycle(modules);
    }
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -q -Dtest=AutoConfigWiringTest test`
Expected: `BUILD SUCCESS`, context starts and shuts down cleanly.

- [ ] **Step 7: Run the full test suite**

Run: `mvn -q test`
Expected: `BUILD SUCCESS` — all tests from Tasks 2–9 green.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: per-module bot lifecycle + runner, wired in auto-config"
```

---

### Task 10: Documentation, example module, and release metadata

**Files:**
- Modify: `README.md`
- Modify: `PUBLISHING.md`
- Modify: `CHANGELOG.md`
- Modify: `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (verify only `TelegramAuthAutoConfiguration` is listed)

**Interfaces:**
- Consumes: the public API produced by Tasks 2–9.
- Produces: user-facing docs describing the v0.2.0 multi-instance model and a complete worked "admin" module.

- [ ] **Step 1: Verify the auto-config registration file**

Run: `mvn -q -DskipTests compile` (sanity) and confirm the imports file content:

```
io.github.dev_abdulhay.telegramauth.config.TelegramAuthAutoConfiguration
```

If it lists any deleted class, replace its contents with the single line above.

- [ ] **Step 2: Rewrite the README quickstart**

Replace the "Quickstart" and "REST API" sections of `README.md` with the v0.2.0 model. Include this complete worked example (host "admin" module):

````markdown
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
````

Also remove the README's old "Entity scanning" note and the Liquibase `<include>`
instruction (the starter no longer ships a changelog). Update the version badge/snippet to `0.2.0`.

- [ ] **Step 3: Update CHANGELOG**

Prepend to `CHANGELOG.md`:

```markdown
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
```

- [ ] **Step 4: Update PUBLISHING.md version references**

Update any `0.1.x` version strings in `PUBLISHING.md` to `0.2.0`.

- [ ] **Step 5: Final full build**

Run: `mvn -q clean test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "docs: v0.2.0 multi-instance toolkit guide, example module, changelog"
```

---

## Self-Review Notes

- **Spec coverage:** abstract base classes (Tasks 2,3,5,8) ✓; per-type config object with command registry + bot instance (Task 4) ✓; no tables / no controllers from starter (Tasks 1,9 — only `TokenGenerator` + `TelegramBotLifecycle` beans) ✓; `JsonNode` command handlers + fallback (Tasks 4,6) ✓; default flow that works on correct subclassing + override (Task 7) ✓; controller via `@RequestMapping` prefix on subclass (Task 8) ✓; table name via `@Table`, no `tablePrefix` (Tasks 2,10) ✓; clean breaking rewrite to 0.2.0 (Tasks 1,10) ✓; worked example as docs + tests (Tasks 2–9 fixtures, Task 10 README) ✓.
- **Type consistency:** `TelegramBotModule` getters/builder names are identical across Tasks 4–9; `AbstractSessionService.CreatedSession(rawToken, entity)` consumed identically in Task 8; repository derived-query names match between base interface (Task 2) and service usage (Task 5).
- **Open items deferred to implementation (from spec §6):** single shared cron via `@Scheduled` placeholder (each bean sweeps its own table — Task 5); poll-thread backoff via `module.getPollingInterval()` (Task 9); per-module event-bus isolation via module-owned `InMemoryAuthEventBus` (Task 4).
