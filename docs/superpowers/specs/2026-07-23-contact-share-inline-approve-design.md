# Contact-share + inline approve/reject — dizayn spetsifikatsiyasi

**Sana:** 2026-07-23
**Loyiha:** telegram-auth-spring-boot-starter
**Versiya:** 0.2.0 → **0.3.0** (additive public API)
**Status:** dizayn tasdiqlangan (brainstorming), implementatsiyadan oldingi spec

## 1. Maqsad

`DefaultAuthFlow` klassining Javadoc'ida "future work" deb belgilangan ikki imkoniyatni
yakunlash:

1. **Contact-share** — foydalanuvchidan Telegram orqali telefon raqamini olish.
2. **Inline approve/reject** — `/start` da avtomatik tasdiqlash o'rniga, foydalanuvchi
   inline tugma bosib kirishni o'zi tasdiqlaydi (yoki rad etadi).

Har ikkalasi ham **opt-in** (yoqib qo'yiladi); yoqilmasa mavjud xulq **o'zgarmaydi**.

## 2. Qabul qilingan qarorlar (brainstorming Q&A)

| Qaror | Tanlov |
|-------|--------|
| Integratsiya | Opt-in flaglar `DefaultAuthFlow`da (module builder'da emas). Default xulq o'zgarmaydi → **non-breaking**. |
| Contact talab darajasi | **Soft** — foydalanuvchi `/skip` bilan telefonsiz davom eta oladi. |
| Contact qachon | **Faqat kerak bo'lganda** — user ro'yxatdan o'tmagan yoki `phone` bo'sh bo'lsa. |
| Xabar tillari | **3 til: uz / ru / en**, Telegram `from.language_code` bo'yicha, fallback = `uz`. |

## 3. Konfiguratsiya API (opt-in, non-breaking)

`DefaultAuthFlow`ga ikkita bayroq, `Options` builder orqali. Mavjud 3-argumentli
konstruktor saqlanadi va default `Options` (ikkalasi `false`) bilan delegat qiladi.

```java
// Eski (o'zgarmaydi): /start <token> → auto-register + auto-approve
new DefaultAuthFlow<>(userService, sessionService, module);

// Yangi: opt-in
new DefaultAuthFlow<>(userService, sessionService, module,
        DefaultAuthFlow.Options.builder()
                .requireContact(true)     // telefon so'rash (soft, faqat kerakda)
                .requireApproval(true)    // inline ✅/❌ tasdiq
                .build());
```

`Options` (immutable):
- `boolean requireContact` (default `false`)
- `boolean requireApproval` (default `false`)
- `Options.builder()` → `requireContact(boolean)`, `requireApproval(boolean)`, `build()`

## 4. Flow (bayroqlar `true` bo'lганда)

```
/start <token>
   │  token yaroqsiz/topilmadi → INVALID_LINK (STOP)
   │
   ├─ [requireContact && (user==null || phone bo'sh)]
   │     reply-keyboard: [BTN_SHARE_CONTACT (request_contact)]
   │     matn: ASK_CONTACT (tappable /skip bilan)
   │     pendingLogins.put(chatId → rawToken)         ← in-memory korrelyatsiya
   │           │
   │           ├─ contact keldi
   │           │     contact.user_id != from.id → CONTACT_NOT_OWN, pending saqlanadi (qayta so'rash)
   │           │     aks holda → register(phone) → remove keyboard → (approval qadami)
   │           └─ /skip → register(phone=null) → remove keyboard → (approval qadami)
   │
   └─ [aks holda] register(mavjud yoki metadata) → (approval qadami)

(approval qadami):
   ├─ [requireApproval]
   │     inline: [BTN_APPROVE | BTN_REJECT]   (callback_data: tgauth:approve:<token> / tgauth:reject:<token>)
   │     matn: CONFIRM_PROMPT
   │        ├─ callback approve → sessionService.approve → editMessageText(APPROVED) + answerCallbackQuery
   │        └─ callback reject  → sessionService.reject  → editMessageText(REJECTED) + answerCallbackQuery
   │
   └─ [aks holda] sessionService.approve → APPROVED
```

Bayroq kombinatsiyalari (ikkalasi ham izchil):

| requireContact | requireApproval | Natija |
|---|---|---|
| false | false | **Hozirgi xulq** — auto-register (metadata) + auto-approve |
| false | true | register(metadata) → inline approve/reject → approve/reject |
| true | false | (kerakda) contact/`/skip` → register → auto-approve |
| true | true | (kerakda) contact/`/skip` → register → inline approve/reject |

## 5. Komponent o'zgarishlari

### 5.1 `TelegramBot` — 3 yangi metod (thin wrapper, overridable qoladi)
- `void sendMessage(long chatId, String text, String replyMarkupJson)` — `reply_markup`
  bo'lsa URL-encode qilib qo'shadi. Mavjud `sendMessage(chatId, text)` shunga `null` markup
  bilan delegat qiladi (imzo o'zgarmaydi).
- `void answerCallbackQuery(String callbackQueryId, String text)` — tugma bosilganda toast.
- `void editMessageText(long chatId, long messageId, String text)` — qaror qabul qilingach
  matnni yangilab, inline klaviaturani olib tashlaydi (reply_markup yuborilmaydi).

### 5.2 `TelegramBotModule` — 2 additive hook (mavjud `command()`/`fallback()` uslubi)
- `void onCallbackQuery(Consumer<JsonNode> handler)` + `getCallbackHandler()`
- `void onContact(Consumer<JsonNode> handler)` + `getContactHandler()`
- Ikkala maydon `volatile`, `fallback` kabi.

### 5.3 `BotUpdateDispatcher.route()` — routing kengaytmasi
Tartib:
1. `update.has("callback_query")` → `callbackHandler` (bo'lmasa `fallback`)
2. `message.text` `/` bilan boshlansa → command registry (`/start`, `/skip`) — **o'zgarmaydi**
3. `message.has("contact")` → `contactHandler` (bo'lmasa `fallback`)
4. aks holda → `fallback`

### 5.4 `DefaultAuthFlow` — asosiy o'zgarish
- Yangi `Options` (nested, builder bilan) + yangi konstruktor; eski konstruktor default
  `Options` bilan delegat qiladi.
- Konstruktorda bayroqlarga qarab ro'yxatdan o'tkazadi:
  - doim: `module.command("/start", this::onStart)`
  - `requireContact` → `module.command("/skip", this::onSkip)` va `module.onContact(this::onContact)`
  - `requireApproval` → `module.onCallbackQuery(this::onCallback)`
- Metodlar (barchasi `protected`, override qilinadigan): `onStart`, `onContact`, `onSkip`,
  `onCallback`, hamda private yordamchilar: `requestContact(...)`, `proceedAfterIdentity(...)`,
  `approveSession(...)`, `registerFrom(from, phone)`.
- **`AbstractSessionService.approve` o'zgarmaydi** — u payload'ni **user entity**dan quradi;
  telefon approve'dan oldin `register` qilinsa, payload'ga o'z-o'zidan tushadi.

### 5.5 `FlowMessages` — yangi klass (localization)
- `enum Key { INVALID_LINK, ASK_CONTACT, BTN_SHARE_CONTACT, CONTACT_NOT_OWN,
  CONFIRM_PROMPT, BTN_APPROVE, BTN_REJECT, APPROVED, REJECTED, SESSION_EXPIRED }`
- Ichki jadval: har `Key` uchun `uz`/`ru`/`en` matnlari.
- `static String resolveLang(String telegramLangCode)` — `ru*`→`ru`, `en*`→`en`, boshqasi→`uz`.
- `static String text(Key key, String lang)`.
- `DefaultAuthFlow`da `protected String msg(FlowMessages.Key key, String lang)` — host to'liq
  moslashtirishi uchun override nuqtasi; default `FlowMessages.text(...)`ga delegat.

## 6. Localization (3 til)

- **Tillar:** `uz`, `ru`, `en`. Fallback = `uz` (kodbaza uzbekcha-birlamchi; mavjud matnlar uzbekcha).
- **Manba:** har handler'da Telegram `from.language_code`:
  - `onStart`/`onContact`/`onSkip` → `update.message.from.language_code`
  - `onCallback` → `update.callback_query.from.language_code`
- Barcha bot matnlari **va tugma yorliqlari** (share-contact, approve, reject) tarjima qilinadi.
- `language_code` bo'sh/noma'lum → `uz`.

Namunaviy matnlar (uz / ru / en):
- `INVALID_LINK`: "Havola yaroqsiz yoki muddati tugagan." / "Ссылка недействительна или устарела." / "The link is invalid or expired."
- `ASK_CONTACT`: "Davom etish uchun telefon raqamingizni ulashing yoki /skip bosing." / "Поделитесь номером телефона или нажмите /skip." / "Share your phone number or tap /skip to continue."
- `BTN_SHARE_CONTACT`: "📱 Raqamni ulashish" / "📱 Поделиться номером" / "📱 Share phone number"
- `CONTACT_NOT_OWN`: "Iltimos, faqat o'z raqamingizni ulashing." / "Пожалуйста, поделитесь своим номером." / "Please share your own phone number."
- `CONFIRM_PROMPT`: "Saytga kirishni tasdiqlaysizmi?" / "Подтвердить вход на сайт?" / "Confirm sign-in to the website?"
- `BTN_APPROVE` / `BTN_REJECT`: "✅ Tasdiqlash"/"❌ Rad etish" · "✅ Подтвердить"/"❌ Отклонить" · "✅ Approve"/"❌ Reject"
- `APPROVED`: "Tasdiqlandi. Web saytga qayting." / "Подтверждено. Вернитесь на сайт." / "Approved. Return to the website."
- `REJECTED`: "Kirish rad etildi." / "Вход отклонён." / "Sign-in rejected."
- `SESSION_EXPIRED`: "Sessiya muddati tugagan." / "Сессия истекла." / "The session has expired."

(Aniq so'zlashuv matnlari implementatsiyada yakunlanadi; kalit-tuzilma yuqoridagidek.)

## 7. Xavfsizlik (default'da yoqiladi)

- **Contact spoofing**: faqat `contact.user_id == message.from.id` bo'lgan raqam qabul
  qilinadi (Telegram boshqa odamning kontaktini ulashishga ruxsat beradi). Mos kelmasa
  `CONTACT_NOT_OWN` va pending saqlanib qoladi.
- **Telefon formati**: `contact.phone_number` dan boshidagi `+` olib tashlanadi (E.164
  without leading `+`, `TelegramUserInfo` hujjatiga mos).
- **callback_data namespace**: `tgauth:` prefiksi — host'ning boshqa callbacklariga
  xalaqit bermaydi. Prefiks mos kelmasa `onCallback` e'tiborsiz qoldiradi (log debug).
- **Callback token hajmi**: token = 43 belgi (32 bayt Base64URL, no-pad). `tgauth:approve:<token>`
  = 58 bayt, `tgauth:reject:<token>` = 57 bayt — 64-bayt limitidan past. Shu sabab approve/reject
  **stateless**, tokenni callback_data'da olib yuradi.
- **Sessiya holati**: `approve`/`reject` faqat `PENDING` sessiyada ishlaydi (mavjud servis
  logikasi); ikki marta bosish yoki muddati tugagan sessiya `SESSION_EXPIRED` beradi.

## 8. State boshqaruvi (`pendingLogins`)

- `ConcurrentHashMap<Long chatId, String rawToken>` (+ yaratilgan vaqt) — faqat **contact
  qadami** uchun (contact update'da token yo'q).
- Bot bitta instansda `getUpdates` qiladi (Telegram bir token uchun parallel polling'ga 409
  qaytaradi) → in-memory korrelyatsiya bitta jarayonda ishonchli.
- Tozalash: sessiya TTL'dan oshgan yozuvlar `onStart` da opportunistik o'chiriladi; `onContact`/
  `onSkip` da sessiya baribir qayta validatsiya qilinadi (`findByRawToken` + status/expiry).
- `approve`/`reject` (inline) state talab qilmaydi.

## 9. Testlar

Mavjud 14 testga qo'shiladi (soxta `TelegramBot` yuborilgan xabarlarni ushlaydi, soxta
servislar + `JsonNode` update'lar bilan):
- 4 bayroq kombinatsiyasining har biri to'g'ri harakatlanishi.
- Contact spoofing (`contact.user_id != from.id`) rad etilishi.
- `/skip` yo'li — telefonsiz register + davom.
- Inline approve va reject callback → tegishli servis chaqiruvi.
- Localization: `uz`/`ru`/`en` va noma'lum kod → `uz` fallback.
- `BotUpdateDispatcher` routing: `callback_query` va `contact` to'g'ri handlerga boradi.

## 10. Versiya + hujjatlar (MAJBURIY — loyiha CLAUDE.md qoidasi)

- Versiya: **0.2.0 → 0.3.0** (`pom.xml`, README install snippet).
- `README.md`: yangi builder sozlamalari (`Options`), yangilangan flow bayoni, 3-tilli xabarlar,
  yangi bot API metodlari — **haqiqiy koddan tekshirilgan** snippetlar bilan.
- `CHANGELOG.md`: Keep-a-Changelog `[0.3.0]` — Added (contact-share, inline approve/reject,
  3-tilli xabarlar, yangi `TelegramBot`/`TelegramBotModule` metodlari).

## 11. Ko'lamdan tashqari (YAGNI)

- 3 tildan boshqa tillar (uz/ru/en yetarli, `TelegramUserInfo` hujjatiga mos).
- Har login'da majburiy contact (soft + faqat-kerakda tanlangan).
- Sessiyada `chatId` persist qilish (in-memory korrelyatsiya yetarli).
- Webhook rejimi (loyiha long-polling'da).
- Contact/approve qadamlari uchun tashqi holat ombori (Redis) — bir instansli polling sabab shart emas.
