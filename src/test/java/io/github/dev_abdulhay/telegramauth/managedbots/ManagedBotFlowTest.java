package io.github.dev_abdulhay.telegramauth.managedbots;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.dev_abdulhay.telegramauth.bot.BotUpdateDispatcher;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBot;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class ManagedBotFlowTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private WireMockServer server;
    private InMemoryManagedBotStore store;
    private List<String> events;
    private BotUpdateDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(0);
        server.start();

        TelegramBot bot = new TelegramBot(HttpClient.newHttpClient(), "123:ABC",
                "http://localhost:" + server.port());
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "manager_bot").bot(bot).build();
        store = new InMemoryManagedBotStore();
        events = new ArrayList<>();
        ManagedBotEvents listener = new ManagedBotEvents() {
            @Override public void onCreated(ManagedBot b) { events.add("created:" + b.botUserId()); }
            @Override public void onTokenRotated(ManagedBot b) { events.add("rotated:" + b.botUserId()); }
        };
        ManagedBotService service = new ManagedBotService(module, store,
                new AesGcmTokenEncryptor(KEY), listener, 3, Duration.ZERO);
        new ManagedBotUpdateHandler(module, service);
        dispatcher = new BotUpdateDispatcher(module);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private static String updatesJson() {
        return "{\"ok\":true,\"result\":[{\"update_id\":1,\"managed_bot\":{\"user\":{\"id\":7},"
                + "\"bot\":{\"id\":555,\"username\":\"tenant_bot\",\"first_name\":\"Tenant\"}}}]}";
    }

    @Test
    void aCreationUpdateEndsWithAnEncryptedTokenAndAnEvent() {
        server.stubFor(post(urlPathEqualTo("/bot123:ABC/getManagedBotToken"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"result\":\"555:CHILD-TOKEN\"}")));

        dispatcher.dispatch(updatesJson());

        assertThat(events).containsExactly("created:555");
        ManagedBot stored = store.findByBotUserId(555L).orElseThrow();
        assertThat(stored.encryptedToken()).doesNotContain("555:CHILD-TOKEN");
        assertThat(new AesGcmTokenEncryptor(KEY).decrypt(stored.encryptedToken()))
                .isEqualTo("555:CHILD-TOKEN");
    }

    @Test
    void aSecondUpdateRefetchesAndReportsARotation() {
        server.stubFor(post(urlPathEqualTo("/bot123:ABC/getManagedBotToken"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"result\":\"555:FIRST\"}")));
        dispatcher.dispatch(updatesJson());

        server.stubFor(post(urlPathEqualTo("/bot123:ABC/getManagedBotToken"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"result\":\"555:SECOND\"}")));
        dispatcher.dispatch(updatesJson());

        assertThat(events).containsExactly("created:555", "rotated:555");
        assertThat(store.findAll()).hasSize(1);
        assertThat(new AesGcmTokenEncryptor(KEY).decrypt(store.findByBotUserId(555L).orElseThrow().encryptedToken()))
                .isEqualTo("555:SECOND");
    }

    @Test
    void aPermanentApiFailureStoresNothing() {
        server.stubFor(post(urlPathEqualTo("/bot123:ABC/getManagedBotToken"))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":false,\"error_code\":400,\"description\":\"BOT_NOT_MANAGED\"}")));

        dispatcher.dispatch(updatesJson());

        assertThat(store.findAll()).isEmpty();
        assertThat(events).isEmpty();
    }
}
