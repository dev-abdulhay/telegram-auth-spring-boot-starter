# Login tasdiqlash kodi (number matching) — dizayn

- **Loyiha:** `telegram-auth-spring-boot-starter`
- **Branch:** `fix/auth/flow/security-hardening`
- **Reliz:** `0.3.0` → **`0.4.0`** (breaking)
- **Sana:** 2026-08-17

---

## 1. Muammo

Hozirgi `requireApproval` (inline ✅/❌) device-code phishing'ni yopmaydi. Hujumchi
o'z brauzeridan sessiya yaratadi, deep-link'ni qurbonga yuboradi, qurbon ✅ bosadi —
va hujumchi qurbonning akkauntiga kiradi. Yagona himoya — tasdiq xabaridagi IP va
User-Agent — butunlay odamning diqqatiga tayanadi.

Yechim: tasdiqni ikki bosqichli qilish va ikkinchi bosqichda **brauzer ekranida
turgan raqamni** talab qilish. Brauzerga qaramayotgan odam uni bilmaydi.

## 2. Xavf modeli — nima yopiladi, nima yopilmaydi

**Kod maxfiy emas va maxfiy bo'lishi ham shart emas.** U `tokenHash` dan
deterministik hosil qilinadi, `tokenHash` esa `rawToken` dan, `rawToken` esa
deep-link ichida turibdi. Ya'ni **linkga ega bo'lgan har kim kodni hisoblab
chiqadi** — jumladan sessiyani yaratgan hujumchi.

Kodning vazifasi sirlilik emas. Vazifasi — *"tasdiqlayotgan odam o'z brauzer
ekraniga qaramoqda"* faktini isbotlash. Shuning uchun:

- kodni saqlash shart emas (yangi ustun yo'q, migratsiya yo'q);
- kodni kech chiqarish xavfsizlik bermaydi, lekin UX foydasi bor (brauzer
  egasiga "kimdir ✅ bosdi" degan signal beradi), shuning uchun u baribir
  `AWAITING_CODE` bosqichida yetkaziladi.

**Yopilmaydigan qism:** hujumchi kodni biladi, demak u qurbonga *"sizning
kodingiz 42"* deb ayta oladi. Bu number matching'ning tug'ma cheklovi
(Microsoft Authenticator'da ham shunday). Hujum endi bitta linkni bosishdan
emas, **jonli, interaktiv ijtimoiy muhandislikdan** iborat bo'ladi va har bir
noto'g'ri javob `log.warn` izini qoldiradi.

**Default profil uchun halol matematika** (BUTTON, `codeButtons=3`,
`codeCooldownThreshold=1`, `codeCooldown=5m`, ikkilanish bilan):

| Raund | Kutish | Kumulyativ hujum ehtimoli |
|---|---|---|
| 1 | — | 33% |
| 2 | 5 daq | 56% |
| 3 | +10 daq | 70% |
| 4 | +20 daq | 80% |

Cooldown *"70% ni 30 soniyada"* ni *"70% ni 15 daqiqada, uchta alohida ijtimoiy
muhandislik raundi bilan"* ga aylantiradi — bu vaqt yutuq va uchta `log.warn`,
lekin `codeButtons=3` ni matematik jihatdan kuchli qilmaydi.

Taqqoslash uchun: `codeButtons=10` → 10 / 19 / 27 / 34%; `TYPED` → 3 / 6 / 9%.
**Bu jadval README'ga kiritiladi** — kutubxona o'z himoyasini oshirib
ko'rsatmasligi kerak.

## 3. Flow — barcha kombinatsiyalar

`requireContact` o'zgarmaydi va har doim kod bosqichidan **oldin** bajariladi.

| `requireApproval` | `codeConfirmation` | Xulq |
|---|---|---|
| `false` | `OFF` | 0.3.0 xulqi: `/start` → darhol register + approve |
| `true` | `OFF` | 0.3.0 xulqi: ✅ → register + approve |
| `false` | `BUTTON` / `TYPED` | **1 touch**: `/start` → `PENDING→AWAITING_CODE`; kod so'rovi IP/qurilma tafsilotlari va ogohlantirish bilan → to'g'ri kod → register + approve |
| `true` | `BUTTON` / `TYPED` | **2 touch**: ✅ (tafsilotlar + ogohlantirish) → `AWAITING_CODE` → kod → register + approve |

```
QR / link
   ↓ /start
[requireContact bo'lsa: kontakt yoki /skip]
   ↓
[requireApproval bo'lsa: ✅ / ❌]          ← 1-touch, register QILINMAYDI
   ↓ PENDING → AWAITING_CODE
brauzerda 2 xonali son ko'rinadi
   ↓ tugma tanlash yoki matn yozish        ← 2-touch
to'g'ri → register + approve → APPROVED
noto'g'ri → (urinishlar tugasa) REJECTED + cooldown
```

**Registratsiya faqat yakuniy tasdiqda.** Hozir `DefaultAuthFlow:304` da ✅
bosilganda `registerFrom(...)` ishlaydi; u yakuniy kod tekshiruviga ko'chadi.
Aks holda kod bosqichida o'lgan har bir phishing urinishi bazada `ACTIVE`
akkaunt qoldiradi.

---

## 4. Public API o'zgarishlari (to'liq ro'yxat)

### 4.1 `DefaultAuthFlow.Options`

```java
public enum CodeConfirmation { BUTTON, TYPED, OFF }
```

| Maydon | Turi | Default | Validatsiya |
|---|---|---|---|
| `requireContact` | `boolean` | `false` | — |
| `requireApproval` | `boolean` | `false` | — |
| `codeConfirmation` | `CodeConfirmation` | **`BUTTON`** | non-null |
| `codeButtons` | `int` | `3` | `3 ≤ n ≤ 10`, rejimdan qat'i nazar |
| `maxCodeAttempts` | `int` | `0` = auto | `≥ 0` |
| `codeCooldown` | `Duration` | `5m` | non-null, `≥ 0`; `ZERO` = o'chirilgan |
| `codeCooldownMax` | `Duration` | `1h` | non-null, `≥ codeCooldown` |
| `codeCooldownThreshold` | `int` | `1` | `≥ 1` |

Yordamchi:

```java
public int effectiveMaxCodeAttempts() {
    return maxCodeAttempts > 0 ? maxCodeAttempts
         : (codeConfirmation == CodeConfirmation.TYPED ? 3 : 1);
}
```

Validatsiya `Builder.build()` da, `IllegalArgumentException` bilan — **yagona
joyda**, chunki config-binding ham shu yerdan o'tadi.

> **BREAKING:** `codeConfirmation` default `BUTTON`. `Options.defaults()` yoki
> 3-argumentli `DefaultAuthFlow(...)` konstruktoridan foydalanayotgan har bir
> mavjud host 0.4.0 da yangi tugma bosish bosqichini oladi. Opt-out:
> `.codeConfirmation(CodeConfirmation.OFF)`. `Options` javadoc'i qayta yoziladi
> ("both flags default to false" jumlasi endi noto'g'ri).

### 4.2 `ConfirmCodeGenerator` (yangi)

```java
package io.github.dev_abdulhay.telegramauth.security;

/**
 * Derives the browser-visible confirmation code from a session's token hash.
 * MUST be a pure function of tokenHash: the flow and the controller derive the
 * code independently and must always agree. Nothing is stored.
 */
@FunctionalInterface
public interface ConfirmCodeGenerator {
    int codeFor(String tokenHash);
}
```

Default implementatsiya:

```java
public final class ConfirmCode implements ConfirmCodeGenerator {
    @Override public int codeFor(String tokenHash) { return of(tokenHash); }
    public static int of(String tokenHash) {
        return Integer.parseInt(tokenHash.substring(0, 4), 16) % 100;
    }
}
```

`TelegramBotModule` ga `confirmCodeGenerator(...)` builder metodi va
`getConfirmCodeGenerator()` getteri qo'shiladi (`bot` / `eventBus` /
`approveHandler` bilan bir xil naqsh). **`DefaultAuthFlow` ham,
`AbstractTelegramAuthController` ham faqat shu orqali oladi** — override ikkala
tomonni birga o'zgartiradi, sinxrondan chiqish imkonsiz.

### 4.3 `TelegramBotModule`

- `onText(Consumer<JsonNode>)` — bir slotli, `claimSlot("text", …)` bilan;
  `getTextHandler()`.
- `confirmCodeGenerator(ConfirmCodeGenerator)` + `getConfirmCodeGenerator()`.
- `sessionTtl` default **`3m` → `5m`** (2 touch + kontakt qadami uchun 3 daqiqa
  juda tor).

`onText` javadoc'i quyidagini aniq yozadi: handler **ro'yxatda bo'lmagan
`/command`larni ham** oladi, chunki dispatcher ularni matn sifatida ko'radi.

### 4.4 `BaseAuthSession.Status`

```java
public enum Status { PENDING, AWAITING_CODE, APPROVED, REJECTED, EXPIRED }
```

`@Enumerated(EnumType.STRING)`, ustun uzunligi 20, `AWAITING_CODE` 13 belgi —
**sxema o'zgarmaydi, migratsiya yo'q, yangi ustun yo'q.**

### 4.5 `AuthEvent` / `AuthEventBus`

- `AuthEvent.Type` += `AWAITING_CODE`; `AuthEvent.awaitingCode()` factory
  (payload bo'sh — kod event ichida yurmaydi, `tokenHash` dan hisoblanadi).
- `AuthEventBus` javadoc'i: *"Listener is invoked at most once (terminal events
  only)"* → *"Dispatch listener'ni ro'yxatdan olib tashlaydi. `AWAITING_CODE`
  noterminal: klient keyingi `poll` da qayta obuna bo'ladi."*
- `InMemoryAuthEventBus` **kodi o'zgarmaydi** — faqat javadoc.

### 4.6 `WaitResponse`

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WaitResponse(String status, Map<String, Object> payload, Integer confirmCode) {
    public WaitResponse(String status, Map<String, Object> payload) { this(status, payload, null); }
}
```

2-argumentli konstruktor saqlanadi — mavjud chaqiruv joylari va host kodi
buzilmaydi. `confirmCode` `null` bo'lganda JSON'da umuman chiqmaydi.

### 4.7 `GET /session/{token}/poll?since=`

```java
public DeferredResult<ResponseEntity<WaitResponse>> poll(@PathVariable String token,
                                                         @RequestParam(required = false) String since)
```

| `since` | `AWAITING_CODE` ga munosabat |
|---|---|
| **yo'q** | 0.3.0 xulqi — faqat terminal. Long-poll paytida `AWAITING_CODE` eventi kelsa → **`204 No Content`** (timeout bilan bir xil; eski klient qayta poll qiladi) |
| **`PENDING`** | **`202 Accepted`** + `{"status":"AWAITING_CODE","confirmCode":42}` — darhol yoki event kelganda |
| **`AWAITING_CODE`** | faqat terminal → **busy-loop yo'q** |

`GET /session/{token}/status` **o'zgarmaydi**: `AWAITING_CODE` nomi tabiiy
chiqadi, kod chiqmaydi (yetkazish yo'li bitta).

### 4.8 `BaseAuthSessionRepository` (signature o'zgarishi)

```java
// eski → yangi
List<S> findByStatusAndExpiresAtBefore(Status, OffsetDateTime)
  → List<S> findByStatusInAndExpiresAtBefore(Collection<Status>, OffsetDateTime)

long countByIpAddressAndStatusAndExpiresAtAfter(String, Status, OffsetDateTime)
  → long countByIpAddressAndStatusInAndExpiresAtAfter(String, Collection<Status>, OffsetDateTime)
```

Ikkalasi ham derived query bo'lib qoladi. `BaseAuthSession` javadoc'idagi
`ip_address,status` indeks tavsiyasi `IN` bilan ham o'z kuchida.

**Implementatsiya eslatmasi:** bu rename/signature o'zgarishi — matn asosidagi
find-replace emas, `jetbrains-refactor` skill orqali (`rename_refactoring` +
`build_project` bilan verify).

### 4.9 `TelegramAuthProperties`

```yaml
telegram:
  auth:
    enabled: true
    cleanup-cron: "0 */5 * * * *"
    flow:                          # barcha DefaultAuthFlow'lar uchun default
      require-contact: false
      require-approval: true
      code-confirmation: BUTTON    # BUTTON | TYPED | OFF
      code-buttons: 3              # 3..10
      max-code-attempts: 0         # 0 = auto (BUTTON→1, TYPED→3)
      code-cooldown: 5m            # ZERO = o'chirilgan
      code-cooldown-max: 1h
      code-cooldown-threshold: 1
    flows:                         # ixtiyoriy, user tipi bo'yicha ustunlik
      admin:
        code-confirmation: TYPED
```

- `TelegramAuthProperties.Flow` maydonlari **nullable wrapper**
  (`Boolean` / `Integer` / `Duration` / `CodeConfirmation`) — shuning uchun
  `flows.admin` da berilmagan qiymat `flow` ga, u ham berilmasa hard-default'ga
  tushadi. "Berilgan" va "default bilan teng" farqlanadi.
- `Flow.toOptions()` (yuqori guruh uchun) va `Flow.toOptions(Flow base)`
  (nomlangan guruh uchun) → `Options.builder()…build()`, ya'ni validatsiya bitta
  joyda qoladi va `code-buttons: 11` startup'da tushunarli xato bilan yiqiladi.
- Auto-config `@Bean @ConditionalOnMissingBean DefaultAuthFlow.Options`
  beradi (`telegram.auth.flow` dan bog'langan). Host uni inject qiladi:

  ```java
  @Bean
  DefaultAuthFlow<DemoUser, DemoSession> demoFlow(DemoUserService us, DemoSessionService ss,
                                                  TelegramBotModule m, DefaultAuthFlow.Options opts) {
      return new DefaultAuthFlow<>(us, ss, m, opts);
  }
  ```
- **Kod har doim ustun**: builder yo'li o'z kuchida qoladi, config faqat default beradi.

Modul darajasidagi qiymatlar (`sessionTtl`, `maxPendingPerIp`,
`trustedProxyHops`, `pollingTimeout` …) bu relizda builder'da qoladi — ular bot
tokeni bilan bir xil "per-type, kodda" guruhida. **Scope'dan tashqarida.**

---

## 5. Komponentlar bo'yicha dizayn

### 5.1 `AbstractSessionService`

| Metod | O'zgarish |
|---|---|
| **`awaitCode(String tokenHash)`** *(yangi)* | `PENDING → AWAITING_CODE`. `@Transactional` + `findWithLockByTokenHash` + muddat tekshiruvi + `publishAfterCommit(tokenHash, AuthEvent.awaitingCode())`. **Host `approveHandler` CHAQIRILMAYDI** — u faqat yakuniy `approve()` da chaqiriladi. `boolean` qaytaradi (`approve`/`reject` naqshi). |
| `approve(tokenHash, user)` | Guard: `s.getStatus() != PENDING` → `s.getStatus() != PENDING && s.getStatus() != AWAITING_CODE` |
| `reject(tokenHash)` | Xuddi shunday |
| `sweepExpired()` | `findByStatusInAndExpiresAtBefore(List.of(PENDING, AWAITING_CODE), now)` — aks holda yarim yo'lda qolgan sessiyalar abadiy yashaydi |
| `create(...)` | `countByIpAddressAndStatusInAndExpiresAtAfter(ip, List.of(PENDING, AWAITING_CODE), now)` — aks holda rate-limit teshiladi |
| `TERMINAL_STATUSES` | **O'ZGARMAYDI.** `AWAITING_CODE` terminal emas; u faqat retention-purge uchun ishlatiladi. Qo'shilsa, sweeper hali tirik sessiyalarni o'chira boshlaydi. Ketma-ketlik to'g'ri ishlaydi: `sweepExpired` `AWAITING_CODE` → `EXPIRED`, keyin purge oladi. |

`approve()` endi `PENDING` dan ham ishlashi — bu host o'z kodidan to'g'ridan-
to'g'ri `approve()` chaqirsa kod bosqichini chetlab o'tishi mumkinligini
bildiradi. Bu **ataylab**: bosqichlar tartibi flow'ning mas'uliyati, servis
qatlami esa transport-agnostik bo'lib qoladi. Javadoc'da yoziladi.

### 5.2 `AbstractTelegramAuthController`

```java
boolean wantsCode = "PENDING".equalsIgnoreCase(since);
int code = module.getConfirmCodeGenerator().codeFor(hash);
```

`terminalResponse(S)` → `immediateResponse(S s, boolean wantsCode)`, tekshiruv
tartibi (tartib muhim):

1. `APPROVED` → `200` + payload
2. `REJECTED` → `403`
3. `EXPIRED` **yoki** `expiresAt < now` → `410` — muddati o'tgan `AWAITING_CODE` shu yerda tutiladi
4. `AWAITING_CODE` **va** `wantsCode` → `202` + `confirmCode`
5. aks holda `null` (long-poll davom etadi)

Listener'dagi `switch`:

```java
case AWAITING_CODE -> wantsCode
        ? ResponseEntity.status(HttpStatus.ACCEPTED).body(new WaitResponse("AWAITING_CODE", Map.of(), code))
        : ResponseEntity.noContent().build();
```

`subscribe → keyin DB'ni qayta o'qish` naqshi **o'zgarmaydi** — race'ni ushlab
qoladi va endi `AWAITING_CODE` o'tishi uchun ham ishlaydi.

`DELETE /session/{token}` → `PENDING || AWAITING_CODE` ni `reject()` qiladi.

### 5.3 `BotUpdateDispatcher` + routing

```
callback_query            → callbackHandler ?: fallback
/command (ro'yxatda bor)  → command handler
message.contact           → contactHandler ?: fallback
message.text              → textHandler ?: fallback      ← YANGI
qolgani                   → fallback
```

Ro'yxatda **yo'q** `/command` ham `textHandler` ga tushadi. Siyosat flow'da:
`DefaultAuthFlow.onText` `/` bilan boshlanadigan matnni darhol
`delegateToFallback()` ga uzatadi — begona buyruq hech qachon urinish
sarflamaydi. Modul kontrakti umumiy bo'lib qoladi, host o'z matn handler'ini
yo'qotmaydi.

Dispatcher klass javadoc'idagi routing tartibi yangilanadi.

### 5.4 `DefaultAuthFlow`

**Konstruktorda ro'yxatdan o'tish:**

```java
module.command("/start", this::onStart);
if (options.requireContact()) { module.command("/skip", this::onSkip); module.onContact(this::onContact); }
if (options.requireApproval() || options.codeConfirmation() != OFF) module.onCallbackQuery(this::onCallback);
if (options.codeConfirmation() == TYPED) module.onText(this::onText);
```

`requireApproval=false` + kod yoqiq bo'lganda ham callback handler kerak —
BUTTON'ning raqam tugmalari va ikkala rejimdagi ❌ uchun.

**In-memory holat.** Ikkita map, ikkalasi ham `pendingLogins` bilan bir xil
intizomda: TTL bo'yicha purge, `MAX_PENDING_LOGINS` ceiling, to'lganda eng
eskisini evict + `log.warn`.

```java
private record Pending(String rawToken, OffsetDateTime createdAt, String phone, int codeAttempts) {}
private record CodeStrikes(int strikes, OffsetDateTime until, OffsetDateTime touchedAt) {}

private final ConcurrentHashMap<Long, Pending> pendingLogins;      // mavjud, +codeAttempts
private final ConcurrentHashMap<Long, CodeStrikes> codeStrikes;    // yangi
```

Ikkalasi ham **JVM-local** va replikatsiya qilinmaydi — README'da allaqachon
hujjatlangan cheklov, ammo 2-touch bilan **oyna kengaydi**: telefon endi kontakt
qadamidan yakuniy kod tasdig'igacha yashashi kerak. Restart yoki polling'ning
boshqa instansiyaga o'tishi bu oynani uzadi. README shu jumla bilan yangilanadi.
`phoneOf(userId, rawToken)` naqshi o'zgarmaydi.

**`proceedAfterIdentity`:**

```
requireApproval ?
    → validPendingSession tekshir → parkPending → sendMessage(confirmPrompt, approveKeyboard)   [1-touch]
codeConfirmation != OFF ?
    → enterCodeStage(...)                                                                        [1 touch, to'g'ridan]
aks holda
    → registerFrom + approve   (0.3.0 xulqi)
```

**`enterCodeStage(userId, rawToken, phone, lang, withDetails)`:**

1. `sessionService.awaitCode(hash(rawToken))` — `false` bo'lsa `SESSION_EXPIRED`
2. `parkPending(userId, new Pending(rawToken, now, phone, 0))`
3. `sendMessage(userId, codePrompt(lang, withDetails, session), codeKeyboard(...))`
   - `BUTTON` → inline klaviatura
   - `TYPED` → faqat ❌ tugmasi bo'lgan inline klaviatura
   - `withDetails=true` (ya'ni `requireApproval=false`) bo'lganda matn:
     `CODE_PROMPT_* + "\n\n" + CONFIRM_DETAILS + "\n\n" + CONFIRM_WARNING`

**`onCallback` — `approve` action** (semantikasi o'zgardi: endi *kod berish*):

```
codeConfirmation == OFF ?
    → 0.3.0 xulqi: registerFrom + approve + finishCallback
aks holda
    → cooldown tekshir
    → awaitCode(hash)
        false qaytsa: sessiya ALLAQACHON AWAITING_CODE va muddati o'tmaganmi?
            ha  → idempotent muvaffaqiyat sifatida qaraladi (✅ ikki marta bosilgan)
            yo'q→ SESSION_EXPIRED
    → answerCallbackQuery + editMessageText(CONFIRM_STEP_DONE)   // 1-bosqich tugmalari o'chadi
    → sendMessage(codePrompt, codeKeyboard)                       // yangi xabar
    → pendingLogins saqlanadi (telefon + urinishlar hisobi)
    → REGISTER QILINMAYDI
```

**Idempotentlik.** Telegram'da ✅ ni ikki marta bosish odatiy hol. Ikkinchi bosishda
`awaitCode` `false` qaytaradi (status endi `PENDING` emas), lekin bu xato emas —
kod so'rovi **yangi aralashtirilgan klaviatura bilan qayta yuboriladi** va
`Pending.codeAttempts` **nolga qaytarilmaydi**, aks holda hujumchi ✅ ni qayta
bosib urinishlar hisobini tiklab olardi.

`editMessageText` `reply_markup` olmaydi, ya'ni chaqirilganda inline klaviatura
o'chadi (`finishCallback` allaqachon shunga tayanadi) — **yangi bot API metodi
kerak emas**.

**`onCallback` — `c<NN>` action** (BUTTON, 2-bosqich):

```
action.charAt(0) == 'c' && qolgani raqam
→ cooldown tekshir
→ sessiya AWAITING_CODE va muddati o'tmaganini tekshir (aks holda SESSION_EXPIRED)
→ expected = module.getConfirmCodeGenerator().codeFor(hash)
→ to'g'ri  : registerFrom + approve + clearPending + clearStrikes → APPROVED
→ noto'g'ri: log.warn + urinish +1
             urinish >= effectiveMaxCodeAttempts ?
                 → reject(hash) + registerStrike + clearPending → CODE_ATTEMPTS_EXHAUSTED / TOO_MANY_ATTEMPTS
                 → aks holda: YANGI aralashtirilgan klaviatura + CODE_WRONG(qolgan)
```

Qayta so'rovda decoy'lar **majburiy qayta generatsiya qilinadi** — aks holda
hujumchi variantlarni birma-bir chiqarib tashlaydi.

**`onText`** (faqat TYPED):

```
private chat emas          → return
text "/" bilan boshlanadi  → delegateToFallback
pendingLogins da yozuv yo'q→ delegateToFallback
sessiya AWAITING_CODE emas → delegateToFallback     (masalan kontakt kutilayotgan PENDING)
cooldown ichida            → TOO_MANY_ATTEMPTS
text.trim() \d{1,2} ga mos emas → CODE_NOT_A_NUMBER, URINISH SARFLANMAYDI
aks holda                  → `c<NN>` bilan bir xil to'g'ri/noto'g'ri mantiq
```

Raqam bo'lmagan matn urinish sarflamasligi **ataylab**: hujumchining taxminlari
baribir raqamli, chin foydalanuvchi esa bitta "salom" bilan sessiyasini
yo'qotmasligi kerak.

**Decoy'lar:**

```java
protected List<Integer> codeChoices(int realCode, int count) {
    LinkedHashSet<Integer> set = new LinkedHashSet<>();
    set.add(realCode);
    ThreadLocalRandom rnd = ThreadLocalRandom.current();
    while (set.size() < count) set.add(rnd.nextInt(100));
    List<Integer> list = new ArrayList<>(set);
    Collections.shuffle(list, rnd);
    return list;
}
```

`LinkedHashSet` tufayli takrorlanish ham, haqiqiy kodga tenglik ham
**strukturaviy jihatdan imkonsiz**. Kriptografik random shart emas — hujumchi
haqiqiy kodni baribir biladi. `protected`, ya'ni override qilinadi; javadoc
invariantni yozadi: natijada `realCode` roppa-rosa bir marta, takror yo'q,
hajmi `count`.

`protected String formatCode(int code)` — default `"%02d"`. Override qilinadi
(masalan 3 xonali generator uchun `%03d`).

**`callback_data` budjeti:**

```
tgauth:approve:<43>  = 58   1-bosqich, mavjud
tgauth:c07:<43>      = 54   2-bosqich, yangi (kod har doim formatlangan, fixed width)
tgauth:reject:<43>   = 58   o'zgarmaydi, ikkala bosqichda ham ishlaydi
```

`callbackData(action, rawToken)` dagi mavjud fail-fast validatsiya **saqlanadi**
va 3 xonali custom generator (`tgauth:c123:<43>` = 55) uchun ham yetarli
zaxira qoldiradi.

**`validPendingSession` refaktori:**

```java
private Optional<S> liveSession(String rawToken, Set<Status> allowed)
private Optional<S> validPendingSession(String rawToken)   // = liveSession(raw, EnumSet.of(PENDING))
private Optional<S> awaitingSession(String rawToken)       // = liveSession(raw, EnumSet.of(AWAITING_CODE))
```

### 5.5 Cooldown siyosati (2-daraja)

**1-daraja — sessiya ichida.** `effectiveMaxCodeAttempts()` ta noto'g'ri taxmin
→ sessiya `REJECTED`.

**2-daraja — foydalanuvchi bo'yicha.** Sessiya shu sabab bilan o'lganda strike
hisobi +1. **Har bir o'lgan sessiya = 1 strike, har bir taxmin emas** — ya'ni
TYPED'dagi 3 ta xato bitta strike, aks holda TYPED foydalanuvchisi bir login
uchun uch marta jazolanardi.

```java
private OffsetDateTime registerStrike(long userId) {
    int n = strikesOf(userId) + 1;
    OffsetDateTime until = null;
    if (!options.codeCooldown().isZero() && n >= options.codeCooldownThreshold()) {
        int exp = Math.min(n - options.codeCooldownThreshold(), 16);   // shift overflow guard
        Duration d = options.codeCooldown().multipliedBy(1L << exp);
        if (d.compareTo(options.codeCooldownMax()) > 0) d = options.codeCooldownMax();
        until = OffsetDateTime.now().plus(d);
    }
    codeStrikes.put(userId, new CodeStrikes(n, until, OffsetDateTime.now()));
    return until;
}
```

Defaultlar bilan ketma-ketlik: **5 → 10 → 20 → 40 → 60 → 60 …** daqiqa.

- **Nolga qaytish:** muvaffaqiyatli login yozuvni butunlay o'chiradi. Aks holda
  yozuv `touchedAt` dan `codeCooldownMax` o'tgach `pendingLogins` bilan bir xil
  sweep'da tozalanadi.
- **Cooldown paytida** `/start`, ✅ va kod kiritish — uchalasi ham
  `TOO_MANY_ATTEMPTS` (qolgan daqiqa bilan) va `log.warn` beradi.
- Foydalanuvchi kelgan sessiya **rad etilmaydi**, o'z muddati bilan expire
  bo'ladi — eng kam kutilmagan xulq.
- **O'q — telegram user id**, IP emas: hujumchining qayta urinishlarida
  o'zgarmas narsa qurbon. IP bo'yicha sessiya yaratish `maxPendingPerIp` bilan
  allaqachon yopilgan.

### 5.6 `FlowMessages` — yangi kalitlar (uz / ru / en)

| Kalit | Izoh |
|---|---|
| `CODE_PROMPT_BUTTON` | "Brauzeringiz ekranidagi raqamni tanlang." |
| `CODE_PROMPT_TYPED` | "Brauzeringiz ekranidagi 2 xonali raqamni yuboring." |
| `CODE_WRONG` | `%d` — qolgan urinish soni |
| `CODE_NOT_A_NUMBER` | "00 dan 99 gacha bo'lgan raqam yuboring." |
| `CODE_ATTEMPTS_EXHAUSTED` | "Urinishlar tugadi. Kirish rad etildi." |
| `TOO_MANY_ATTEMPTS` | `%d` — qolgan daqiqa |
| `CONFIRM_STEP_DONE` | 1-bosqich xabarini almashtiradi: "Tasdiqlandi. Endi raqamni tanlang." |
| **`CONFIRM_WARNING`** | "⚠️ Agar siz hozir saytga kirmayotgan bo'lsangiz — ❌ bosing. Hech kim sizdan bu tugmani bosishni so'ramasligi kerak." |

`CONFIRM_PROMPT` **matni o'zgarmaydi**; ogohlantirish alohida `CONFIRM_WARNING`
kaliti sifatida qo'shiladi va `confirmPrompt()` da IP/qurilma tafsilotlaridan
**keyin** joylashadi:

```
CONFIRM_PROMPT + "\n\n" + CONFIRM_DETAILS + "\n\n" + CONFIRM_WARNING
```

`BTN_REJECT` mavjud va ikkala bosqichda ham ishlatiladi.

---

## 6. Chekka holatlar

| Holat | Xulq |
|---|---|
| `AWAITING_CODE` da muddat tugadi | `poll` → `410`; sweeper `EXPIRED` qiladi |
| ✅ ikki marta bosildi | Idempotent: kod so'rovi qayta yuboriladi, `codeAttempts` nolga qaytmaydi |
| Foydalanuvchi ikkinchi loginni boshladi | `pendingLogins` `userId` bo'yicha kalitlangan → eskisi almashadi; eski sessiya `AWAITING_CODE` da qolib, sweeper bilan `EXPIRED` bo'ladi |
| `BLOCKED` user | Har bir kirish nuqtasida avvalgidek `ACCESS_DENIED` |
| Restart / instansiya almashuvi | `pendingLogins` va `codeStrikes` yo'qoladi: telefon unutiladi, strike hisobi nolga tushadi. Sessiya DB'da yashaydi, ✅ va kod tugmalari ishlaydi (token callback ichida) |
| Guruh chati | `isPrivateChat` tekshiruvi barcha yangi handler'larda ham qo'llanadi |
| Begona `callback_data` | Avvalgidek `fallback` ga |
| `since` noma'lum qiymat | `PENDING` emas → 0.3.0 xulqi (xato emas) |

---

## 7. Test rejasi

Mavjud **38 test yashil qolishi shart**; `DefaultAuthFlowOptionsTest` yangilanadi.

**Spec'dan (10):**

1. BUTTON: birinchi noto'g'ri tanlov sessiyani `REJECTED` qiladi
2. TYPED: 3-chi xatodan keyin `REJECTED`
3. Decoy'lar hech qachon haqiqiy kodga teng emas va o'zaro takrorlanmaydi
4. `codeButtons` validatsiyasi: 2 va 11 rad, 3 va 10 qabul
5. Kod faqat `AWAITING_CODE` statusida chiqadi, `PENDING` da yo'q
6. `AWAITING_CODE` sessiyasi rate-limit hisobiga kiradi
7. Sweeper `AWAITING_CODE` sessiyasini `EXPIRED` qiladi
8. 2-bosqichda rad etilgan login user qatori qoldirmaydi
9. TYPED: login jarayonda bo'lmagan matn `fallback` ga ketadi
10. `OFF` rejimida 0.3.0 xulqi buzilmaydi (regressiya)

**Qarorlardan kelib chiqqan (15):**

11. Cooldown noto'g'ri koddan keyin yangi `/start` ni bloklaydi
12. Cooldown takroriy strike'da ikkilanadi va `codeCooldownMax` da to'xtaydi
13. Muvaffaqiyatli login strike hisobini nolga qaytaradi
14. TYPED'dagi 3 ta noto'g'ri taxmin **bitta** strike beradi, uchta emas
15. `?since=PENDING` → `202` + `confirmCode`
16. `since` yo'q + `AWAITING_CODE` eventi → `204`
17. `?since=AWAITING_CODE` → darhol javob yo'q (busy-loop yo'q)
18. `approve()` va `reject()` `AWAITING_CODE` dan ishlaydi
19. `DELETE /session/{token}` `AWAITING_CODE` sessiyasini bekor qiladi
20. BUTTON `maxCodeAttempts=2` bilan qayta so'rovda decoy'lar qayta aralashtiriladi
21. `onText` ro'yxatda yo'q `/command` ni `fallback` ga uzatadi va urinish sarflamaydi
22. TYPED: raqam bo'lmagan matn urinish sarflamaydi
23. `flows.<name>` berilmagan qiymatlar uchun `flow` ga fallback qiladi
24. Custom `ConfirmCodeGenerator` bilan flow va kontroller bir xil kod beradi
25. ✅ ni ikki marta bosish kod so'rovini qayta yuboradi va `codeAttempts` ni nolga qaytarmaydi

---

## 8. Hujjat va versiya

- **`pom.xml`** → `0.4.0`
- **`README.md`** (CLAUDE.md majburiy qoidasi — har bir snippet haqiqiy koddan
  tekshirilgan bo'lishi shart):
  - kombinatsiya matritsasi (§3)
  - xavf modeli va **halol hujum-ehtimoli jadvali** (§2)
  - `?since` kontrakti va yangi HTTP kodlari (`202`, `204`)
  - `telegram.auth.flow` / `telegram.auth.flows` YAML namunasi
  - `ConfirmCodeGenerator`, `codeChoices`, `formatCode` override nuqtalari
  - `pendingLogins` / `codeStrikes` ning JVM-local ekani va 2-touch bilan oyna
    kengaygani
  - **migratsiya bo'limi**: `codeConfirmation` default `BUTTON`, opt-out yo'li
- **`CHANGELOG.md`** — Keep-a-Changelog, aniq `BREAKING` bo'limi bilan:
  `codeConfirmation` default, `WaitResponse` 3-komponent, repository signature
  o'zgarishlari, `sessionTtl` default `3m→5m`, `AuthEvent.Type` kengayishi.
- **Commit:** Conventional Commits, **hech qanday AI fingerprint yo'q**
  (`Co-Authored-By`, "Generated with" va h.k. — global CLAUDE.md qoidasi).

---

## 9. Scope'dan tashqarida

- Modul darajasidagi qiymatlarni config'ga chiqarish
  (`telegram.auth.modules.<name>.*`) — keyingi reliz.
- `pendingLogins` / `codeStrikes` uchun taqsimlangan (Redis va h.k.) saqlash —
  hozirgi JVM-local cheklov saqlanadi va hujjatlanadi.
- Ko'p instansiyali `AuthEventBus` implementatsiyasi.
- `codeButtons` defaultini oshirish — 3 da qoladi, qoldiq xavf README'da
  ochiq yoziladi.
