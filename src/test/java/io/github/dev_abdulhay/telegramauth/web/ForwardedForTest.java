package io.github.dev_abdulhay.telegramauth.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each trusted proxy appends the peer it received from, so the client sits
 * {@code trustedHops} entries from the right; everything further left was
 * supplied by the caller and can be forged.
 */
class ForwardedForTest {

    @Test
    void singleProxyReadsTheEntryItAppended() {
        // client forged "6.6.6.6", nginx appended the real peer
        assertThat(AbstractTelegramAuthController.forwardedFor("6.6.6.6, 10.0.0.9", 1)).isEqualTo("10.0.0.9");
        assertThat(AbstractTelegramAuthController.forwardedFor("10.0.0.9", 1)).isEqualTo("10.0.0.9");
    }

    @Test
    void twoProxiesSkipTheInnerProxysOwnAddress() {
        // client -> CDN -> nginx -> app: "forged, CLIENT, CDN"
        String header = "6.6.6.6, 203.0.113.7, 198.51.100.2";
        assertThat(AbstractTelegramAuthController.forwardedFor(header, 2)).isEqualTo("203.0.113.7");

        // reading the last entry here would bucket every user under the CDN's IP
        assertThat(AbstractTelegramAuthController.forwardedFor(header, 1)).isEqualTo("198.51.100.2");
    }

    @Test
    void aHeaderShorterThanTheChainIsNotTrusted() {
        assertThat(AbstractTelegramAuthController.forwardedFor("203.0.113.7", 2)).isNull();
        assertThat(AbstractTelegramAuthController.forwardedFor("", 1)).isNull();
        assertThat(AbstractTelegramAuthController.forwardedFor(null, 1)).isNull();
        assertThat(AbstractTelegramAuthController.forwardedFor("  , 10.0.0.9", 2)).isNull();
    }
}
