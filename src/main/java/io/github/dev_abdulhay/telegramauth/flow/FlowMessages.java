package io.github.dev_abdulhay.telegramauth.flow;

import java.util.Map;

/**
 * Built-in bot texts for {@link DefaultAuthFlow} in three languages
 * ({@code uz} default, {@code ru}, {@code en}), resolved from the Telegram
 * {@code from.language_code}. Hosts customise wording by overriding
 * {@code DefaultAuthFlow#msg(Key, String)}.
 */
public final class FlowMessages {

    public enum Key {
        INVALID_LINK,
        ACCESS_DENIED,
        ASK_CONTACT,
        BTN_SHARE_CONTACT,
        CONTACT_NOT_OWN,
        CONTACT_SAVED,
        CONTACT_SKIPPED,
        CONFIRM_PROMPT,
        /** Format string with two {@code %s} slots: session IP, then device/user-agent. */
        CONFIRM_DETAILS,
        BTN_APPROVE,
        BTN_REJECT,
        APPROVED,
        REJECTED,
        SESSION_EXPIRED
    }

    private static final Map<Key, Map<String, String>> TEXTS = Map.ofEntries(
            Map.entry(Key.INVALID_LINK, Map.of(
                    "uz", "Havola yaroqsiz yoki muddati tugagan.",
                    "ru", "Ссылка недействительна или устарела.",
                    "en", "The link is invalid or expired.")),
            Map.entry(Key.ACCESS_DENIED, Map.of(
                    "uz", "Kirish taqiqlangan.",
                    "ru", "Доступ запрещён.",
                    "en", "Access denied.")),
            Map.entry(Key.ASK_CONTACT, Map.of(
                    "uz", "Davom etish uchun telefon raqamingizni ulashing yoki /skip bosing.",
                    "ru", "Поделитесь номером телефона или нажмите /skip.",
                    "en", "Share your phone number or tap /skip to continue.")),
            Map.entry(Key.BTN_SHARE_CONTACT, Map.of(
                    "uz", "📱 Raqamni ulashish",
                    "ru", "📱 Поделиться номером",
                    "en", "📱 Share phone number")),
            Map.entry(Key.CONTACT_NOT_OWN, Map.of(
                    "uz", "Iltimos, faqat o'z raqamingizni ulashing.",
                    "ru", "Пожалуйста, поделитесь своим номером.",
                    "en", "Please share your own phone number.")),
            Map.entry(Key.CONTACT_SAVED, Map.of(
                    "uz", "Raqam qabul qilindi.",
                    "ru", "Номер получен.",
                    "en", "Phone number received.")),
            Map.entry(Key.CONTACT_SKIPPED, Map.of(
                    "uz", "Telefonsiz davom etamiz.",
                    "ru", "Продолжаем без номера.",
                    "en", "Continuing without a phone number.")),
            Map.entry(Key.CONFIRM_PROMPT, Map.of(
                    "uz", "Saytga kirishni tasdiqlaysizmi?",
                    "ru", "Подтвердить вход на сайт?",
                    "en", "Confirm sign-in to the website?")),
            Map.entry(Key.CONFIRM_DETAILS, Map.of(
                    "uz", "IP: %s\nQurilma: %s",
                    "ru", "IP: %s\nУстройство: %s",
                    "en", "IP: %s\nDevice: %s")),
            Map.entry(Key.BTN_APPROVE, Map.of(
                    "uz", "✅ Tasdiqlash",
                    "ru", "✅ Подтвердить",
                    "en", "✅ Approve")),
            Map.entry(Key.BTN_REJECT, Map.of(
                    "uz", "❌ Rad etish",
                    "ru", "❌ Отклонить",
                    "en", "❌ Reject")),
            Map.entry(Key.APPROVED, Map.of(
                    "uz", "Tasdiqlandi. Web saytga qayting.",
                    "ru", "Подтверждено. Вернитесь на сайт.",
                    "en", "Approved. Return to the website.")),
            Map.entry(Key.REJECTED, Map.of(
                    "uz", "Kirish rad etildi.",
                    "ru", "Вход отклонён.",
                    "en", "Sign-in rejected.")),
            Map.entry(Key.SESSION_EXPIRED, Map.of(
                    "uz", "Sessiya muddati tugagan.",
                    "ru", "Сессия истекла.",
                    "en", "The session has expired.")));

    private FlowMessages() {
    }

    /** {@code ru*} → ru, {@code en*} → en, anything else (or null) → uz. */
    public static String resolveLang(String telegramLangCode) {
        if (telegramLangCode == null) return "uz";
        String lc = telegramLangCode.toLowerCase();
        if (lc.startsWith("ru")) return "ru";
        if (lc.startsWith("en")) return "en";
        return "uz";
    }

    public static String text(Key key, String lang) {
        Map<String, String> byLang = TEXTS.get(key);
        String s = byLang.get(lang);
        return (s != null) ? s : byLang.get("uz");
    }
}
