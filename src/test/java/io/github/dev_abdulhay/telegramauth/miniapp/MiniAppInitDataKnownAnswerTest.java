package io.github.dev_abdulhay.telegramauth.miniapp;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A fixed initData vector whose hash was computed outside this codebase, with Python's
 * stdlib following Telegram's documented algorithm, so a bug shared by the validator and
 * {@link MiniAppTestSigner} cannot hide here:
 * <pre>
 * python3 -I -c 'import hmac,hashlib,urllib.parse as u; raw="&lt;RAW&gt;";
 *   tok=b"7000000001:AAKnownAnswerVectorNotARealToken";
 *   secret=hmac.new(b"WebAppData",tok,hashlib.sha256).digest();
 *   dcs="\n".join(sorted(f"{k}={v}" for k,v in u.parse_qsl(raw,keep_blank_values=True,strict_parsing=True) if k!="hash"));
 *   print(hmac.new(secret,dcs.encode(),hashlib.sha256).hexdigest())'
 * </pre>
 * where {@code <RAW>} is {@link #RAW_WITHOUT_HASH}. The vector covers a {@code signature}
 * field, a Cyrillic {@code first_name}, a {@code +} that decodes to a space, {@code query_id}
 * and {@code start_param}.
 */
class MiniAppInitDataKnownAnswerTest {

    private static final String TOKEN = "7000000001:AAKnownAnswerVectorNotARealToken";
    /** 60 s after {@code auth_date} 1794563940 (2026-11-13T09:59:00Z). */
    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

    private static final String RAW_WITHOUT_HASH = "query_id=AAHdF6IQAAAAAN0XohDhrOrc"
            + "&user=%7B%22id%22%3A279058397%2C%22first_name%22%3A%22%D0%90%D0%BB%D0%B8%D1%88%D0%B5%D1%80%22"
            + "%2C%22last_name%22%3A%22Valiyev+Jr%22%2C%22username%22%3A%22alisher_v%22"
            + "%2C%22language_code%22%3A%22uz%22%2C%22allows_write_to_pm%22%3Atrue%7D"
            + "&auth_date=1794563940&start_param=order_7"
            + "&signature=6fbdaUMhX0K7tUa-gXUmhkvzFJZOdtbqr0mJxVjZqA0Mcq9Rv9VJZ8vA8b7UbbQ-pHtRj8eLz6pO7qaD7BZ6CA";
    private static final String HASH = "7af807c24008347b6df14fa217099cc57d6a9f7b4a2752a287d2bbef38398a68";

    private final MiniAppInitDataValidator validator = new MiniAppInitDataValidator();

    private MiniAppInitData validate(String raw) {
        return validator.validate(raw, TOKEN, MiniAppOptions.defaults(), NOW);
    }

    @Test
    void acceptsTheExternallyComputedVector() {
        MiniAppInitData data = validate(RAW_WITHOUT_HASH + "&hash=" + HASH);

        assertThat(data.hash()).isEqualTo(HASH);
        assertThat(data.authDate()).isEqualTo(Instant.ofEpochSecond(1794563940L));
        assertThat(data.startParam()).isEqualTo("order_7");
        assertThat(data.user().telegramId()).isEqualTo(279058397L);
        assertThat(data.user().firstName()).isEqualTo("Алишер");
        assertThat(data.user().lastName()).isEqualTo("Valiyev Jr");
        assertThat(data.user().username()).isEqualTo("alisher_v");
        assertThat(data.user().languageCode()).isEqualTo("uz");
    }

    @Test
    void oneFlippedHashCharIsInvalid() {
        String flipped = "8" + HASH.substring(1);

        assertThatThrownBy(() -> validate(RAW_WITHOUT_HASH + "&hash=" + flipped))
                .isInstanceOfSatisfying(MiniAppAuthException.class,
                        e -> assertThat(e.error()).isEqualTo(MiniAppError.INIT_DATA_INVALID));
    }
}
