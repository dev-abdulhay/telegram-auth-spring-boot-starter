# telegram-auth v0.2.0 — Multi-instance abstract toolkit

**Sana:** 2026-06-20
**Holat:** Dizayn tasdiqlandi, implementatsiya rejasi kutilmoqda
**Tur:** Breaking rewrite (v0.1.x → v0.2.0)

## 1. Maqsad

Hozirgi `telegram-auth-spring-boot-starter` bitta qat'iy (hardcoded) flow beradi:
bitta bot, bitta `m_telegram_user` jadval, bitta session jadval, bitta concrete
controller. Bu N ta turdagi userni (masalan `admin`, `customer`, `driver`) Telegram
orqali ro'yxatdan o'tkazish/auth qilish uchun yaramaydi.

Yangi maqsad: starter **abstract multi-instance toolkit**ga aylansin. Starter o'zi
**hech qanday jadval yaratmaydi, hech qanday entity/controller/repository ro'yxatdan
o'tkazmaydi**. U faqat generic abstract baza beradi. Host ilova har bir user turi
uchun bitta "modul" yozadi (6 subclass + 1 `@Configuration`). To'g'ri extend qilinsa
default reg/auth flow ishlab ketadi; kerakli method override qilinsa xulq o'zgaradi.

### Foydalanish ssenariysi
Hostga 3 ta user turi kerak → 3 ta modul yozadi. Har modul mustaqil: o'z jadvallari,
o'z boti (o'z token'i), o'z REST prefiksi, o'z command registry'si. Modullar bir-biriga
bog'liq emas (umumiy user jadval yo'q).

## 2. Asosiy qarorlar (brainstorm natijasi)

| # | Qaror | Tanlov |
|---|-------|--------|
| 1 | Wiring uslubi | **To'liq subclass (A).** Host har modul uchun entity/repo/service/controller subclass'larini va bitta `@Configuration` yozadi. |
| 2 | Config manbai | **Kod (builder) + yaml sirlari.** Config ob'ekti kodda `@Bean` sifatida quriladi; bot token kabi sirlar `@Value("${...}")` orqali yaml/env'dan keladi. |
| 3 | Update turi | **Xom `JsonNode`.** Command handler signaturasi `Consumer<JsonNode>`. Keyin signatura o'zgartirilishi mumkin. |
| 4 | Migratsiya | **Toza breaking rewrite (v0.2.0).** Concrete classlar olib tashlanadi; faqat abstract baza qoladi. Namuna README + test'da. |
| 5 | Dispatch | **Command + fallback.** Registry `/command`lar bo'yicha kalitlanadi; qolgan hammasi (callback_query, contact, matn) bitta ixtiyoriy `fallback` handler'ga boradi. |

### JPA/Spring tabiatidan kelib chiqqan tuzatishlar
- **Controller prefix** config ob'ektida saqlanmaydi — u subclass'dagi `@RequestMapping`da
  turadi (Spring routing prefiksining yagona manbasi).
- **Table prefix** umuman yo'q — jadval nomini host bevosita subclass'dagi `@Table(name=...)`da
  to'liq yozadi.
- **Entity va repository'ni host albatta o'zi yozadi** — JPA `@Entity`/`@Table` class-load
  vaqtida qotadi, Spring Data esa konkret repository interfeysini talab qiladi. Bularni
  aylanib o'tib bo'lmaydi.

## 3. Arxitektura

### 3.1 Generic abstract baza (starter beradi)

#### Entity (`@MappedSuperclass`, jadvalsiz)
```java
@MappedSuperclass
public abstract class BaseTelegramUser {
    @Id @GeneratedValue(strategy = IDENTITY) private Long id;
    private Long telegramId;
    private String phone;
    private String firstName;
    private String lastName;
    private String username;
    private String languageCode;
    private String externalUserId;
    @Enumerated(STRING) private Status status;   // PENDING, ACTIVE, BLOCKED
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    // getter/setter, enum Status
}

@MappedSuperclass
public abstract class BaseAuthSession {
    @Id @GeneratedValue(strategy = IDENTITY) private Long id;
    private String tokenHash;
    private String ipAddress;
    private String userAgent;
    @Enumerated(STRING) private Status status;    // PENDING, APPROVED, REJECTED, EXPIRED
    private OffsetDateTime createdAt;
    private OffsetDateTime expiresAt;
    private OffsetDateTime approvedAt;
    private Long telegramUserId;
}
```
Host:
```java
@Entity @Table(name = "admin_tg_user")
public class AdminUser extends BaseTelegramUser {}

@Entity @Table(name = "admin_tg_session")
public class AdminSession extends BaseAuthSession {}
```

#### Repository (`@NoRepositoryBean` generic interfeys)
```java
@NoRepositoryBean
public interface BaseTelegramUserRepository<U extends BaseTelegramUser>
        extends JpaRepository<U, Long> {
    Optional<U> findByTelegramId(Long telegramId);
}

@NoRepositoryBean
public interface BaseAuthSessionRepository<S extends BaseAuthSession>
        extends JpaRepository<S, Long> {
    Optional<S> findByTokenHash(String tokenHash);

    @Modifying @Query(...)
    int markExpired(Status expired, Status pending, OffsetDateTime now);
}
```
Host (Spring Data impl'ni o'zi generatsiya qiladi):
```java
public interface AdminUserRepository extends BaseTelegramUserRepository<AdminUser> {}
public interface AdminSessionRepository extends BaseAuthSessionRepository<AdminSession> {}
```

#### Service (abstract, generic)
Generic'da `new U()` mumkin emas → konstruktor `Supplier<U>`/`Supplier<S>` oladi.
```java
public abstract class AbstractTelegramUserService<U extends BaseTelegramUser> {
    private final BaseTelegramUserRepository<U> repo;
    private final Supplier<U> newUser;
    // register(...), findByTelegramId(...) — default impl
}

public abstract class AbstractSessionService<U extends BaseTelegramUser,
                                              S extends BaseAuthSession> {
    private final BaseAuthSessionRepository<S> sessionRepo;
    private final Supplier<S> newSession;
    private final TokenGenerator tokenGenerator;
    private final AuthEventBus bus;
    private final TelegramAuthApproveHandler approveHandler;
    // create, findByRawToken, approve, reject
    @Scheduled(cron = "...") public void sweepExpired() { ... }  // har bean o'z jadvalini sweep qiladi
}
```
Host:
```java
@Service
public class AdminUserService extends AbstractTelegramUserService<AdminUser> {
    public AdminUserService(AdminUserRepository repo) { super(repo, AdminUser::new); }
}
```

#### Controller (abstract)
Handler-method annotatsiyalari bazada; `@RestController` + prefiks subclass'da.
Spring meros olingan handler annotatsiyalarini o'qiydi.
```java
public abstract class AbstractTelegramAuthController<U extends BaseTelegramUser,
                                                     S extends BaseAuthSession> {
    protected final AbstractSessionService<U, S> sessionService;
    protected final AuthEventBus bus;

    @PostMapping("/session")            public CreateSessionResponse create(...) { ... }
    @GetMapping("/session/{token}/poll") public DeferredResult<...> poll(@PathVariable String token) { ... }
    @GetMapping("/session/{token}/status") public ResponseEntity<...> status(...) { ... }
    @DeleteMapping("/session/{token}")  public ResponseEntity<Void> cancel(...) { ... }
}
```
Host:
```java
@RestController
@RequestMapping("/api/admin-auth")
public class AdminAuthController extends AbstractTelegramAuthController<AdminUser, AdminSession> {
    public AdminAuthController(AdminSessionService service, AuthEventBus bus) { super(service, bus); }
    // hech narsa yozmasa 4 endpoint ishlaydi; xohlasa @Override qiladi
}
```
**Qoidalar:**
- `@RestController` faqat subclass'da (abstract bazaga emas — bean sifatida instansiya
  qilinmasligi uchun).
- Prefiks faqat subclass `@RequestMapping`da → har modul o'z path'iga ega.
- Generic qaytish turlari Spring tomonidan subclass type-argumentlari orqali yechiladi.

### 3.2 Modul config ob'ekti (har tur uchun 1 ta `@Bean`)

Kodda quriladigan `TelegramBotModule` ob'ekti:
```java
@Bean
TelegramBotModule adminBotModule(@Value("${admin.bot.token}") String token,
                                 AdminAuthFlow flow) {
    return TelegramBotModule.builder(token, "admin_bot")   // (token, username)
        .command("/start", flow::onStart)                  // Consumer<JsonNode>
        .command("/help",  flow::onHelp)
        .fallback(flow::onAnyUpdate)                        // ixtiyoriy, bitta
        .sessionTtl(Duration.ofMinutes(3))
        .pollingTimeout(Duration.ofSeconds(30))
        .build();
}
```
- **Command registry:** `Map<String, Consumer<JsonNode>>`. `/command` bilan boshlanmagan
  yoki registry'da yo'q update'lar (callback_query, contact, oddiy matn) → `fallback`.
- **Default flow handler'lari** (`onStart`, va keyinroq `onContact`, approve/reject)
  starter'da `DefaultAuthFlow<U, S>` sifatida tayyor turadi. Host registry'ga ulaydi;
  override = boshqa handler ulash yoki method override.

### 3.3 Bot instance & polling
- `TelegramBot` (hozirgi `TelegramBotClient`ning kengaytmasi) token'dan yaratiladi:
  `sendMessage(chatId, text)` va generic `call(method, params)` beradi. Host service
  method'larida inject qilib botga xabar yuboradi.
- Har modul → **o'z bot instance + o'z long-poll runner thread** (o'z `getUpdates`
  offset'i). N tur = N bot = N poll loop. Lifecycle: app ready'da start, shutdown'da stop.
- `BotUpdateDispatcher` har modul uchun: `getUpdates` JSON'ini parse qiladi, command'ni
  topib registry handler'ini `JsonNode update` bilan chaqiradi, aks holda `fallback`.

### 3.4 Starter o'zi beradigan bean'lar (type-agnostik)
Faqat quyidagilar avtomatik:
- `TokenGenerator` (token generatsiya + hash)
- `ObjectMapper`
- Har modul uchun `AuthEventBus` (modul-darajada izolyatsiya), bot instance, runner,
  dispatcher — config ob'ekti asosida.

**Yo'q narsalar:** concrete entity, concrete controller, concrete repository, jadval,
Liquibase changelog. Jadval va migratsiyani **host yozadi**. `@AutoConfigurationPackage`
entity-scan hiylasi olib tashlanadi (concrete entity yo'q).

### 3.5 Default reg/auth flow (saqlanadi, generiklashtiriladi)
Hozirgi flow o'zgarmaydi, faqat generic bo'ladi:
```
client → POST /session            → { token, t.me/<bot>?start=… }
client → GET  /session/{token}/poll (ushlab turiladi)
user   → t.me/<bot>?start=<token>
bot    → /start <token>           → DefaultAuthFlow.onStart → user register + approve
client ← 200 { status: APPROVED, payload }
```
`DefaultAuthFlow<U, S>` ichida. Host approve natijasini `TelegramAuthApproveHandler`
bean orqali belgilaydi (JWT, cookie — host qaroriga ko'ra).

## 4. Misol moduli (README + integratsiya-test)
Bitta to'liq "admin" moduli ham hujjat, ham integratsiya-test sifatida:
`AdminUser`, `AdminSession`, `AdminUserRepository`, `AdminSessionRepository`,
`AdminUserService`, `AdminSessionService`, `AdminAuthController`, `AdminTgConfig`
(`@Configuration` + `TelegramBotModule` bean + `/start` registratsiyasi + jadval DDL/migratsiya).

## 5. v0.1.x dan o'chiriladigan/o'zgaradigan narsalar
- **O'chiriladi:** concrete `MTelegramUser`, `MTelegramAuthSession`, ularning repo'lari,
  concrete `SessionService`/`TelegramUserService`, `TelegramAuthController`,
  `db/changelog/telegram-auth-changelog.xml`, `TelegramAuthEntityScanConfig`,
  scantest paketi.
- **Generiklashtiriladi:** `SessionService` → `AbstractSessionService<U,S>`,
  `TelegramUserService` → `AbstractTelegramUserService<U>`, controller → abstract baza.
- **Saqlanadi/kengayadi:** `TokenGenerator`, `AuthEventBus`/`InMemoryAuthEventBus`,
  `AuthEvent`, `TelegramBotClient` → `TelegramBot`, DTO'lar (`CreateSessionRequest`,
  `CreateSessionResponse`, `WaitResponse`, `SessionStatusResponse`, `AuthApproveResult`,
  `AuthContext`, `TelegramUserInfo`).
- **`TelegramAuthProperties`:** faqat global/umumiy default'lar (masalan `enabled`).
  Per-modul sozlamalar config ob'ektiga ko'chadi.
- **Versiya:** `0.1.2` → `0.2.0` (breaking). README va PUBLISHING yangilanadi.

## 6. Ochiq texnik nuqtalar (implementatsiya rejasida hal qilinadi)
- `@Scheduled` cron qiymatini per-modul berish (annotatsiya placeholder vs dasturiy
  scheduler). Default: bitta umumiy cron yetarli, har bean o'z jadvalini sweep qiladi.
- Bir nechta poll-loop thread lifecycle va xato/qayta urinish (backoff) siyosati.
- `AuthEventBus` modul-darajada izolyatsiya: token hash kaliti modul ichida unikal.

## 7. Muvaffaqiyat mezoni
- Starter'da bironta `@Entity`, `@Table`, concrete `@RestController` yoki jadval yo'q.
- Host 6 subclass + 1 `@Configuration` yozib, hech qanday flow kodisiz ishlaydigan
  reg/auth oladi.
- 2+ mustaqil modul (har xil bot, jadval, prefiks) bir vaqtda ishlaydi.
- Bitta method'ni `@Override` qilib flow xulqini o'zgartirish mumkin.
- Integratsiya-test "admin" modulini uchidan-uchiga (session yaratish → /start → approve →
  poll release) tekshiradi.
