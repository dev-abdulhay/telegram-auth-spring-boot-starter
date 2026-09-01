package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.managedbots.InMemoryManagedBotStore;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBot;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TenantBotLifecycleTest {

    static class RecordingRegistry extends TenantBotRegistry<DemoU, DemoS> {
        final List<Long> started = new ArrayList<>();
        final List<Long> stopped = new ArrayList<>();
        long failFor = -1;
        long failForWithError = -1;

        RecordingRegistry() { super(null, null, null, null, null); }

        @Override public void start(ManagedBot bot) {
            if (bot.botUserId() == failFor) throw new IllegalStateException("cannot decrypt");
            if (bot.botUserId() == failForWithError) throw new AssertionError("cannot decrypt");
            started.add(bot.botUserId());
        }
        @Override public void stopAll() { stopped.addAll(started); }
    }

    private static ManagedBot bot(long id) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ManagedBot(id, "tenant_bot", "Tenant", 7L, "enc", now, now);
    }

    private static InMemoryManagedBotStore storeWith(long... ids) {
        InMemoryManagedBotStore store = new InMemoryManagedBotStore();
        for (long id : ids) store.save(bot(id));
        return store;
    }

    @Test
    void everyStoredBotIsStartedWhenTheApplicationIsReady() {
        RecordingRegistry registry = new RecordingRegistry();
        new TenantBotLifecycle<>(storeWith(1L, 2L, 3L), registry, true).startAll();

        assertThat(registry.started).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void oneTenantThatCannotStartDoesNotStopTheRest() {
        RecordingRegistry registry = new RecordingRegistry();
        registry.failFor = 2L;

        new TenantBotLifecycle<>(storeWith(1L, 2L, 3L), registry, true).startAll();

        // 2 blew up; 1 and 3 must still be running
        assertThat(registry.started).containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    void oneTenantThrowingAnErrorDoesNotStopTheRest() {
        RecordingRegistry registry = new RecordingRegistry();
        registry.failForWithError = 2L;

        new TenantBotLifecycle<>(storeWith(1L, 2L, 3L), registry, true).startAll();

        // 2 blew up with an Error; 1 and 3 must still be running
        assertThat(registry.started).containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    void restoreCanBeTurnedOff() {
        RecordingRegistry registry = new RecordingRegistry();
        new TenantBotLifecycle<>(storeWith(1L, 2L), registry, false).startAll();

        assertThat(registry.started).isEmpty();
    }

    @Test
    void startAllIsIdempotent() {
        RecordingRegistry registry = new RecordingRegistry();
        TenantBotLifecycle<DemoU, DemoS> lifecycle =
                new TenantBotLifecycle<>(storeWith(1L), registry, true);

        lifecycle.startAll();
        lifecycle.startAll();

        assertThat(registry.started).containsExactly(1L);
    }

    @Test
    void shutdownStopsEverything() {
        RecordingRegistry registry = new RecordingRegistry();
        TenantBotLifecycle<DemoU, DemoS> lifecycle =
                new TenantBotLifecycle<>(storeWith(1L, 2L), registry, true);
        lifecycle.startAll();

        lifecycle.stopAll();

        assertThat(registry.stopped).containsExactlyInAnyOrder(1L, 2L);
    }
}
