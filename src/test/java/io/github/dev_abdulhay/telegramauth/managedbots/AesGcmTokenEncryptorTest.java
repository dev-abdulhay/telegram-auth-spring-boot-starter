package io.github.dev_abdulhay.telegramauth.managedbots;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmTokenEncryptorTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String TOKEN = "123456789:AAHfake-token-value_for-tests";

    @Test
    void roundTripsAToken() {
        AesGcmTokenEncryptor enc = new AesGcmTokenEncryptor(KEY);
        assertThat(enc.decrypt(enc.encrypt(TOKEN))).isEqualTo(TOKEN);
    }

    @Test
    void producesADifferentCiphertextEachTime() {
        AesGcmTokenEncryptor enc = new AesGcmTokenEncryptor(KEY);
        // a fixed IV would leak that two rows hold the same token
        assertThat(enc.encrypt(TOKEN)).isNotEqualTo(enc.encrypt(TOKEN));
    }

    @Test
    void refusesAKeyThatIsNotThirtyTwoBytes() {
        assertThatThrownBy(() -> new AesGcmTokenEncryptor(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
    }

    @Test
    void refusesTamperedCiphertext() {
        AesGcmTokenEncryptor enc = new AesGcmTokenEncryptor(KEY);
        String encrypted = enc.encrypt(TOKEN);
        String tampered = encrypted.substring(0, encrypted.length() - 2)
                + (encrypted.endsWith("A") ? "B=" : "A=");
        assertThatThrownBy(() -> enc.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }
}
