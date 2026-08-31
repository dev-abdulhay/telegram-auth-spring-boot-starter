package io.github.dev_abdulhay.telegramauth.bot;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedBotApiTest {

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
    void getManagedBotTokenReturnsTheTokenFromTheResult() {
        server.stubFor(post(urlPathEqualTo("/bot123:ABC/getManagedBotToken"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"result\":\"999:CHILD-TOKEN\"}")));

        assertThat(bot.getManagedBotToken(555L)).isEqualTo("999:CHILD-TOKEN");
        server.verify(postRequestedFor(urlPathEqualTo("/bot123:ABC/getManagedBotToken"))
                .withRequestBody(WireMock.containing("user_id=555")));
    }

    @Test
    void replaceManagedBotTokenReturnsTheNewToken() {
        server.stubFor(post(urlPathEqualTo("/bot123:ABC/replaceManagedBotToken"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"result\":\"999:ROTATED\"}")));

        assertThat(bot.replaceManagedBotToken(555L)).isEqualTo("999:ROTATED");
    }

    @Test
    void accessSettingsAreReadAndWritten() {
        server.stubFor(post(urlPathEqualTo("/bot123:ABC/getManagedBotAccessSettings"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"result\":{\"is_access_restricted\":true,"
                                + "\"added_users\":[{\"id\":42,\"username\":\"ann\",\"first_name\":\"Ann\"}]}}")));
        server.stubFor(post(urlPathEqualTo("/bot123:ABC/setManagedBotAccessSettings"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"result\":true}")));

        assertThat(bot.getManagedBotAccessSettings(555L).path("is_access_restricted").asBoolean()).isTrue();

        bot.setManagedBotAccessSettings(555L, true, List.of(42L, 43L));
        server.verify(postRequestedFor(urlPathEqualTo("/bot123:ABC/setManagedBotAccessSettings"))
                .withRequestBody(WireMock.containing("is_access_restricted=true"))
                .withRequestBody(WireMock.containing("added_user_ids=%5B42%2C43%5D")));
    }

    @Test
    void aTooManyRequestsResponseIsWaitedOutAndRetriedOnce() {
        server.stubFor(post(urlPathEqualTo("/bot123:ABC/getManagedBotToken"))
                .inScenario("429").whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(429).withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":1}}"))
                .willSetStateTo("second"));
        server.stubFor(post(urlPathEqualTo("/bot123:ABC/getManagedBotToken"))
                .inScenario("429").whenScenarioStateIs("second")
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"result\":\"999:AFTER-WAIT\"}")));

        assertThat(bot.getManagedBotToken(555L)).isEqualTo("999:AFTER-WAIT");
        assertThat(server.getAllServeEvents()).hasSize(2);
    }

    @Test
    void anOkFalseResponseBecomesAnException() {
        server.stubFor(post(urlPathEqualTo("/bot123:ABC/getManagedBotToken"))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":false,\"error_code\":400,\"description\":\"BOT_NOT_MANAGED\"}")));

        assertThatThrownBy(() -> bot.getManagedBotToken(555L))
                .isInstanceOf(TelegramApiException.class)
                .hasMessageContaining("BOT_NOT_MANAGED");
    }
}
