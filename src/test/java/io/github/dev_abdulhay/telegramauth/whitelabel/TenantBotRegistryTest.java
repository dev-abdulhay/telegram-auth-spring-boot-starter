package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBot;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.managedbots.AesGcmTokenEncryptor;
import io.github.dev_abdulhay.telegramauth.managedbots.InMemoryManagedBotStore;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBot;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotEvents;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotService;
import io.github.dev_abdulhay.telegramauth.managedbots.TokenEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantBotRegistryTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private InMemoryManagedBotStore store;
    private TokenEncryptor encryptor;
    private ManagedBotService managedBots;
    private List<String> built;

    /** Never touches the network: both getUpdates overloads block briefly and return empty. */
    static class QuietBot extends TelegramBot {
        QuietBot(String token) { super(HttpClient.newHttpClient(), token); }
        @Override public String getUpdates(long offset, int timeoutSeconds) throws Exception {
            Thread.sleep(100);
            return "{\"ok\":true,\"result\":[]}";
        }
        @Override public String getUpdates(long offset, int timeoutSeconds, List<String> allowed) throws Exception {
            return getUpdates(offset, timeoutSeconds);
        }
    }

    private static ManagedBot bot(long id) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ManagedBot(id, "tenant_" + id + "_bot", "Tenant", 7L, null, now, now);
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryManagedBotStore();
        encryptor = new AesGcmTokenEncryptor(KEY);
        built = new ArrayList<>();
        TelegramBotModule manager = TelegramBotModule.builder("999:MANAGER", "manager_bot")
                .bot(new QuietBot("999:MANAGER")).build();
        managedBots = new ManagedBotService(manager, store, encryptor, new ManagedBotEvents() { },
                1, Duration.ZERO);
    }

    /** Stores a bot with a known token so findToken can decrypt it. */
    private ManagedBot storedBot(long id, String token) {
        OffsetDateTime now = OffsetDateTime.now();
        ManagedBot b = new ManagedBot(id, "tenant_" + id + "_bot", "Tenant", 7L,
                encryptor.encrypt(token), now, now);
        store.save(b);
        return b;
    }

    private TenantBotRegistry<DemoU, DemoS> registry(ManagedBotCustomizer customizer) {
        return new TenantBotRegistry<>(managedBots, (b, token) -> {
            built.add(b.botUserId() + ":" + token);
            TelegramBotModule m = TelegramBotModule.builder(token, b.username())
                    .bot(new QuietBot(token))
                    .botUserId(b.botUserId())
                    .build();
            return new RunningBot<>(m, new StubTenantSessionService(m));
        }, customizer, null, null);
    }

    @Test
    void startBuildsTheModuleWithTheDecryptedTokenAndRegistersIt() {
        ManagedBot b = storedBot(555L, "555:CHILD");
        TenantBotRegistry<DemoU, DemoS> registry = registry(null);
        try {
            registry.start(b);

            assertThat(built).containsExactly("555:555:CHILD");
            assertThat(registry.running()).containsExactly(555L);
            assertThat(registry.sessionServiceFor(555L)).isPresent();
        } finally {
            registry.stopAll();
        }
    }

    @Test
    void startingAnAlreadyRunningBotIsANoOp() {
        ManagedBot b = storedBot(555L, "555:CHILD");
        TenantBotRegistry<DemoU, DemoS> registry = registry(null);
        try {
            registry.start(b);
            registry.start(b);

            assertThat(built).hasSize(1);
            assertThat(registry.running()).containsExactly(555L);
        } finally {
            registry.stopAll();
        }
    }

    @Test
    void stopDeregistersTheBot() {
        ManagedBot b = storedBot(555L, "555:CHILD");
        TenantBotRegistry<DemoU, DemoS> registry = registry(null);
        registry.start(b);

        registry.stop(555L);

        assertThat(registry.running()).isEmpty();
        assertThat(registry.sessionServiceFor(555L)).isEmpty();
    }

    @Test
    void restartRebuildsTheModuleWithTheNewToken() {
        ManagedBot b = storedBot(555L, "555:FIRST");
        TenantBotRegistry<DemoU, DemoS> registry = registry(null);
        try {
            registry.start(b);
            ManagedBot rotated = storedBot(555L, "555:SECOND");

            registry.restart(rotated);

            assertThat(built).containsExactly("555:555:FIRST", "555:555:SECOND");
            assertThat(registry.running()).containsExactly(555L);
        } finally {
            registry.stopAll();
        }
    }

    @Test
    void theCustomizerRunsForEveryBotThatStarts() {
        ManagedBot b = storedBot(555L, "555:CHILD");
        List<Long> customized = new ArrayList<>();
        TenantBotRegistry<DemoU, DemoS> registry = registry((m, mb) -> customized.add(mb.botUserId()));
        try {
            registry.start(b);
            assertThat(customized).containsExactly(555L);
        } finally {
            registry.stopAll();
        }
    }

    @Test
    void startingABotWithNoStoredTokenFailsLoudly() {
        TenantBotRegistry<DemoU, DemoS> registry = registry(null);

        assertThatThrownBy(() -> registry.start(bot(999L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("999");
        assertThat(registry.running()).isEmpty();
    }

    @Test
    void sessionServiceForAnUnknownBotIsEmpty() {
        assertThat(registry(null).sessionServiceFor(404L)).isEmpty();
    }
}
