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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantBotRegistryTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private InMemoryManagedBotStore store;
    private TokenEncryptor encryptor;
    private ManagedBotService managedBots;
    private List<String> built;
    /** Every {@code QuietBot} the test factory has created, keyed by its token. */
    private Map<String, QuietBot> quietBots;

    /** Never touches the network: both getUpdates overloads block briefly and return empty. */
    static class QuietBot extends TelegramBot {
        final AtomicInteger polls = new AtomicInteger();
        QuietBot(String token) { super(HttpClient.newHttpClient(), token); }
        @Override public String getUpdates(long offset, int timeoutSeconds) throws Exception {
            polls.incrementAndGet();
            Thread.sleep(100);
            return "{\"ok\":true,\"result\":[]}";
        }
        @Override public String getUpdates(long offset, int timeoutSeconds, List<String> allowed) throws Exception {
            return getUpdates(offset, timeoutSeconds);
        }
    }

    /** Always answers non-ok, so a runner polling it trips its failure budget quickly. */
    static class FailingBot extends TelegramBot {
        FailingBot(String token) { super(HttpClient.newHttpClient(), token); }
        @Override public String getUpdates(long offset, int timeoutSeconds) throws Exception {
            Thread.sleep(15);
            return "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}";
        }
        @Override public String getUpdates(long offset, int timeoutSeconds, List<String> allowed) throws Exception {
            return getUpdates(offset, timeoutSeconds);
        }
    }

    /** Polls {@code condition} until it is true or {@code timeout} elapses, without pulling in a new dependency. */
    private static void waitUntil(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("condition not met within " + timeout);
            }
            Thread.sleep(20);
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
        quietBots = new HashMap<>();
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
            QuietBot quietBot = new QuietBot(token);
            quietBots.put(token, quietBot);
            TelegramBotModule m = TelegramBotModule.builder(token, b.username())
                    .bot(quietBot)
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
        try {
            registry.start(b);

            registry.stop(555L);

            assertThat(registry.running()).isEmpty();
            assertThat(registry.sessionServiceFor(555L)).isEmpty();
        } finally {
            registry.stopAll();
        }
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

    /**
     * A restart that dropped its {@code stop()} call, or a {@code stop()} that
     * deregistered without actually stopping the runner, would still pass every
     * other assertion in this class: the map would show one id, and the factory
     * would have run twice. The only way to catch a second poller left running on
     * the old token is to watch that old bot's poll count go silent.
     */
    @Test
    void restartStopsTheOldRunnerBeforeStartingTheNew() throws InterruptedException {
        ManagedBot b = storedBot(555L, "555:FIRST");
        TenantBotRegistry<DemoU, DemoS> registry = registry(null);
        try {
            registry.start(b);
            QuietBot firstBot = quietBots.get("555:FIRST");
            waitUntil(Duration.ofSeconds(2), () -> firstBot.polls.get() > 0);
            ManagedBot rotated = storedBot(555L, "555:SECOND");

            registry.restart(rotated);

            int countRightAfterRestart = firstBot.polls.get();
            // Long enough for the old bot to have polled again at least once more
            // if its runner were still alive; QuietBot polls roughly every 100ms.
            Thread.sleep(400);
            assertThat(firstBot.polls.get())
                    .as("the old runner's poll count must go silent after restart")
                    .isEqualTo(countRightAfterRestart);
        } finally {
            registry.stopAll();
        }
    }

    /**
     * A {@code stop()} that removes a reservation before checking whether it has
     * been published leaves the starting thread finishing into a slot the map no
     * longer holds: the runner it starts then polls forever, invisible to every
     * accessor and to future {@code stop}/{@code stopAll} calls. This drives
     * {@code start()} through its reservation window on a background thread —
     * parked deterministically on a latch rather than by sleeping — and lands
     * {@code stop()} while the reservation is still unpublished. Either outcome
     * (the bot ends up running and stoppable, or ends up absent) is acceptable;
     * what is never acceptable is polling while {@link TenantBotRegistry#running()}
     * reports it absent.
     */
    @Test
    void stopDuringAReservationDoesNotOrphanTheStartingRunner() throws InterruptedException {
        ManagedBot b = storedBot(555L, "555:LATCH");
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TenantBotRegistry<DemoU, DemoS> registry = new TenantBotRegistry<>(managedBots, (mb, token) -> {
            reserved.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            QuietBot quietBot = new QuietBot(token);
            quietBots.put(token, quietBot);
            TelegramBotModule m = TelegramBotModule.builder(token, mb.username())
                    .bot(quietBot)
                    .botUserId(mb.botUserId())
                    .build();
            return new RunningBot<>(m, new StubTenantSessionService(m));
        }, null, null, null);

        Thread starter = new Thread(() -> registry.start(b), "registry-start-race");
        starter.start();
        try {
            assertThat(reserved.await(2, TimeUnit.SECONDS))
                    .as("start() must have reserved the slot before the race begins")
                    .isTrue();

            // Lands while start() is parked inside the reservation window: entry is
            // still null at this point, so a correct stop() must leave the
            // reservation alone rather than evict it out from under the starter.
            registry.stop(555L);
        } finally {
            release.countDown();
            starter.join(2_000);
        }

        QuietBot startedBot = quietBots.get("555:LATCH");
        assertThat(startedBot).as("the factory must have run").isNotNull();
        try {
            if (registry.running().contains(555L)) {
                assertThat(registry.sessionServiceFor(555L)).isPresent();
                waitUntil(Duration.ofSeconds(2), () -> startedBot.polls.get() > 0);

                registry.stop(555L);
                int countAfterStop = startedBot.polls.get();
                Thread.sleep(300);
                assertThat(startedBot.polls.get())
                        .as("a bot the registry reports as running must actually be stoppable")
                        .isEqualTo(countAfterStop);
            } else {
                Thread.sleep(200);
                int count = startedBot.polls.get();
                Thread.sleep(300);
                assertThat(startedBot.polls.get())
                        .as("a bot absent from running() must not still be polling in the background")
                        .isEqualTo(count);
            }
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

    @Test
    void pollFailureBudgetDeregistersTheBot() throws InterruptedException {
        ManagedBot b = storedBot(555L, "555:CHILD");
        TenantBotRegistry<DemoU, DemoS> registry = new TenantBotRegistry<>(managedBots, (mb, token) -> {
            TelegramBotModule m = TelegramBotModule.builder(token, mb.username())
                    .bot(new FailingBot(token))
                    .botUserId(mb.botUserId())
                    .pollingInterval(Duration.ofMillis(10))
                    .build();
            return new RunningBot<>(m, new StubTenantSessionService(m));
        }, null, null, Duration.ofMillis(100));
        try {
            registry.start(b);

            waitUntil(Duration.ofSeconds(5), () -> registry.running().isEmpty());

            assertThat(registry.sessionServiceFor(555L)).isEmpty();
        } finally {
            registry.stopAll();
        }
    }
}
