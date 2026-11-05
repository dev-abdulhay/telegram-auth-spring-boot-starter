package io.github.dev_abdulhay.telegramauth.bot;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the {@code allowed_updates} query parameter is wired correctly on {@link TelegramBot#getUpdates}. */
class GetUpdatesAllowedUpdatesTest {

    private WireMockServer server;
    private TelegramBot bot;

    @BeforeEach
    void start() {
        server = new WireMockServer(0);
        server.start();
        bot = new TelegramBot(HttpClient.newHttpClient(), "123:ABC", "http://localhost:" + server.port());
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void theTwoArgOverloadSendsNoAllowedUpdatesParameter() throws Exception {
        server.stubFor(get(urlPathEqualTo("/bot123:ABC/getUpdates"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"result\":[]}")));

        String response = bot.getUpdates(0, 1);

        assertThat(response).contains("\"ok\":true");
        assertThat(server.getAllServeEvents()).hasSize(1);
        assertThat(server.getAllServeEvents().get(0).getRequest().getUrl()).doesNotContain("allowed_updates");
    }

    @Test
    void theThreeArgOverloadWithANullListSendsNoAllowedUpdatesParameter() throws Exception {
        server.stubFor(get(urlPathEqualTo("/bot123:ABC/getUpdates"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"result\":[]}")));

        bot.getUpdates(0, 1, null);

        assertThat(server.getAllServeEvents().get(0).getRequest().getUrl()).doesNotContain("allowed_updates");
    }

    @Test
    void theThreeArgOverloadWithAListEncodesItAsAJsonArray() throws Exception {
        server.stubFor(get(urlPathEqualTo("/bot123:ABC/getUpdates"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"result\":[]}")));

        bot.getUpdates(0, 1, List.of("message", "callback_query", "managed_bot"));

        String url = server.getAllServeEvents().get(0).getRequest().getUrl();
        assertThat(url).contains("allowed_updates=");
        assertThat(java.net.URLDecoder.decode(url, java.nio.charset.StandardCharsets.UTF_8))
                .contains("[\"message\",\"callback_query\",\"managed_bot\"]");
    }
}
