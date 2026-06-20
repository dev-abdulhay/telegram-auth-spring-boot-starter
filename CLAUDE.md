# telegram-auth-spring-boot-starter — project instructions

## README ni doimo yangilab borish (MAJBURIY)

Har bir **funksional o'zgarish**dan keyin [`README.md`](README.md) ni shu
o'zgarishga moslab yangilab bor. Bu opsional emas.

**Funksional o'zgarish** = foydalanuvchi (host ilova) ko'radigan yoki ishlatadigan
narsaning o'zgarishi, masalan:
- yangi yoki o'zgargan public API / signature (abstract klasslar, `TelegramBotModule`
  builder, servis/kontroller metodlari);
- yangi yoki o'zgargan REST endpoint, javob kodi yoki payload kontrakti;
- yangi yoki o'zgargan config property (`telegram.auth.*`) yoki builder sozlamasi;
- DB sxema talabi (entity ustunlari) o'zgarishi;
- flow / default xulq (`DefaultAuthFlow`, `/start`, approve/reject) o'zgarishi;
- versiya ko'tarilishi (install snippet'dagi `<version>`).

**Qoida:**
- README'dagi har bir kod snippet va signatura **haqiqiy koddan tekshirilgan**
  bo'lishi shart — taxmin qilma, mos klassni o'qib aniqla.
- Sof ichki refactor (public API'ga ta'sir qilmaydigan) README'ni o'zgartirishni
  talab qilmaydi.
- README + CHANGELOG.md ni birga yangila (CHANGELOG Keep-a-Changelog formatida).
- Imkon bo'lsa README o'zgarishini o'sha funksional o'zgarish bilan bitta commit/PR'da
  yubor.
