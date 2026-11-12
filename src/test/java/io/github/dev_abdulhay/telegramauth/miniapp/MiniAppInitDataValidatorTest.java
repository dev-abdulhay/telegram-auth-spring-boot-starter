package io.github.dev_abdulhay.telegramauth.miniapp;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MiniAppInitDataValidatorTest {

    private static final String TOKEN = "123456:TEST";
    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final String USER_JSON =
            "{\"id\":42,\"first_name\":\"Ali\",\"last_name\":\"V\",\"username\":\"ali\",\"language_code\":\"uz\"}";

    private final MiniAppInitDataValidator validator = new MiniAppInitDataValidator();

    private static Map<String, String> fields(Instant authDate) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("query_id", "AAH1");
        f.put("user", USER_JSON);
        f.put("auth_date", String.valueOf(authDate.getEpochSecond()));
        return f;
    }

    private MiniAppInitData validate(String raw) {
        return validator.validate(raw, TOKEN, MiniAppOptions.defaults(), NOW);
    }

    private void assertError(String raw, MiniAppError expected) {
        assertThatThrownBy(() -> validate(raw))
                .isExactlyInstanceOf(MiniAppAuthException.class)
                .satisfies(e -> assertThat(((MiniAppAuthException) e).error()).isEqualTo(expected));
    }

    @Test
    void validSignedInitDataParsesUser() {
        Map<String, String> f = fields(NOW.minusSeconds(10));
        f.put("start_param", "order_7");

        MiniAppInitData data = validate(MiniAppTestSigner.sign(TOKEN, f));

        assertThat(data.user().telegramId()).isEqualTo(42L);
        assertThat(data.user().username()).isEqualTo("ali");
        assertThat(data.user().firstName()).isEqualTo("Ali");
        assertThat(data.user().lastName()).isEqualTo("V");
        assertThat(data.user().languageCode()).isEqualTo("uz");
        assertThat(data.user().phone()).isNull();
        assertThat(data.startParam()).isEqualTo("order_7");
        assertThat(data.authDate()).isEqualTo(NOW.minusSeconds(10));
        assertThat(data.hash()).hasSize(64);
    }

    @Test
    void startParamIsNullWhenAbsent() {
        assertThat(validate(MiniAppTestSigner.sign(TOKEN, fields(NOW))).startParam()).isNull();
    }

    @Test
    void tamperedFieldIsInvalid() {
        Instant signed = NOW.minusSeconds(10);
        String raw = MiniAppTestSigner.sign(TOKEN, fields(signed))
                .replace("auth_date=" + signed.getEpochSecond(), "auth_date=" + NOW.getEpochSecond());

        assertError(raw, MiniAppError.INIT_DATA_INVALID);
    }

    @Test
    void wrongBotTokenIsInvalid() {
        assertError(MiniAppTestSigner.sign("999:OTHER", fields(NOW)), MiniAppError.INIT_DATA_INVALID);
    }

    @Test
    void signatureFieldIsCoveredByHash() {
        Map<String, String> f = fields(NOW);
        f.put("signature", "abc");
        String signed = MiniAppTestSigner.sign(TOKEN, f);

        assertThat(validate(signed).user().telegramId()).isEqualTo(42L);
        assertError(MiniAppTestSigner.sign(TOKEN, fields(NOW)) + "&signature=abc", MiniAppError.INIT_DATA_INVALID);
        assertError(signed.replace("signature=abc", "signature=xyz"), MiniAppError.INIT_DATA_INVALID);
    }

    @Test
    void valueContainingEqualsSign() {
        Map<String, String> f = fields(NOW);
        f.put("start_param", "a=b");

        assertThat(validate(MiniAppTestSigner.sign(TOKEN, f)).startParam()).isEqualTo("a=b");
    }

    @Test
    void olderThanMaxAgeIsExpired() {
        Duration hour = Duration.ofHours(1);
        assertError(MiniAppTestSigner.sign(TOKEN, fields(NOW.minus(hour).minusSeconds(1))),
                MiniAppError.INIT_DATA_EXPIRED);
        assertThat(validate(MiniAppTestSigner.sign(TOKEN, fields(NOW.minus(hour).plusSeconds(1))))).isNotNull();
    }

    @Test
    void futureBeyondSkewIsExpired() {
        assertError(MiniAppTestSigner.sign(TOKEN, fields(NOW.plusSeconds(61))), MiniAppError.INIT_DATA_EXPIRED);
        assertThat(validate(MiniAppTestSigner.sign(TOKEN, fields(NOW.plusSeconds(59))))).isNotNull();
    }

    @Test
    void missingHashIsInvalid() {
        assertError("auth_date=" + NOW.getEpochSecond() + "&user=" + USER_JSON, MiniAppError.INIT_DATA_INVALID);
    }

    @Test
    void missingUserIsInvalid() {
        Map<String, String> f = fields(NOW);
        f.remove("user");

        assertError(MiniAppTestSigner.sign(TOKEN, f), MiniAppError.INIT_DATA_INVALID);
    }

    @Test
    void malformedUserJsonIsInvalid() {
        Map<String, String> f = fields(NOW);
        f.put("user", "{not json");

        assertError(MiniAppTestSigner.sign(TOKEN, f), MiniAppError.INIT_DATA_INVALID);
    }

    @Test
    void nonNumericAuthDateIsInvalid() {
        Map<String, String> f = fields(NOW);
        f.put("auth_date", "yesterday");

        assertError(MiniAppTestSigner.sign(TOKEN, f), MiniAppError.INIT_DATA_INVALID);
    }

    @Test
    void missingAuthDateIsInvalid() {
        Map<String, String> f = fields(NOW);
        f.remove("auth_date");

        assertError(MiniAppTestSigner.sign(TOKEN, f), MiniAppError.INIT_DATA_INVALID);
    }

    @Test
    void userWithoutIdIsInvalid() {
        Map<String, String> f = fields(NOW);
        f.put("user", "{\"first_name\":\"Ali\"}");

        assertError(MiniAppTestSigner.sign(TOKEN, f), MiniAppError.INIT_DATA_INVALID);
    }

    @Test
    void pairWithoutEqualsIsInvalid() {
        assertError(MiniAppTestSigner.sign(TOKEN, fields(NOW)) + "&orphan", MiniAppError.INIT_DATA_INVALID);
    }

    @Test
    void badPercentEscapeIsInvalid() {
        assertError("start_param=%zz&" + MiniAppTestSigner.sign(TOKEN, fields(NOW)), MiniAppError.INIT_DATA_INVALID);
    }

    @Test
    void optionsRejectNullAndNegativeDurations() {
        assertThatThrownBy(() -> new MiniAppOptions(null, Duration.ZERO)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MiniAppOptions(Duration.ZERO, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MiniAppOptions(Duration.ofSeconds(-1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MiniAppOptions(Duration.ZERO, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new MiniAppOptions(Duration.ZERO, Duration.ZERO).maxAge()).isZero();
    }

    @Test
    void blankInitDataIsMissing() {
        assertError(null, MiniAppError.INIT_DATA_MISSING);
        assertError("  ", MiniAppError.INIT_DATA_MISSING);
    }

    @Test
    void errorsCarryWireCodesAndStatuses() {
        assertThat(MiniAppError.INIT_DATA_MISSING.httpStatus()).isEqualTo(400);
        assertThat(MiniAppError.INIT_DATA_MISSING.code()).isEqualTo("init_data_missing");
        assertThat(MiniAppError.INIT_DATA_INVALID.httpStatus()).isEqualTo(401);
        assertThat(MiniAppError.INIT_DATA_INVALID.code()).isEqualTo("init_data_invalid");
        assertThat(MiniAppError.INIT_DATA_EXPIRED.httpStatus()).isEqualTo(401);
        assertThat(MiniAppError.INIT_DATA_EXPIRED.code()).isEqualTo("init_data_expired");
        assertThat(MiniAppError.USER_BLOCKED.httpStatus()).isEqualTo(403);
        assertThat(MiniAppError.USER_BLOCKED.code()).isEqualTo("user_blocked");
    }

    @Test
    void defaultOptions() {
        assertThat(MiniAppOptions.defaults().maxAge()).isEqualTo(Duration.ofHours(1));
        assertThat(MiniAppOptions.defaults().maxFutureSkew()).isEqualTo(Duration.ofSeconds(60));
    }
}
