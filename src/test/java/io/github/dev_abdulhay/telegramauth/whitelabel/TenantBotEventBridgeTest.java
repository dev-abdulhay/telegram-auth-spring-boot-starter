package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBot;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class TenantBotEventBridgeTest {

    /** Records what the bridge asked for, and can be told to fail. */
    static class RecordingRegistry extends TenantBotRegistry<DemoU, DemoS> {
        final List<String> calls = new ArrayList<>();
        boolean failOnStart;
        boolean failOnStartWithError;

        RecordingRegistry() { super(null, null, null, null, null); }

        @Override public void start(ManagedBot bot) {
            calls.add("start:" + bot.botUserId());
            if (failOnStart) throw new IllegalStateException("no token");
            if (failOnStartWithError) throw new AssertionError("no token");
        }
        @Override public void stop(long botUserId) { calls.add("stop:" + botUserId); }
        @Override public void restart(ManagedBot bot) { calls.add("restart:" + bot.botUserId()); }
    }

    private static ManagedBot bot(long id) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ManagedBot(id, "tenant_bot", "Tenant", 7L, "enc", now, now);
    }

    @Test
    void lifecycleEventsDriveTheRegistry() {
        RecordingRegistry registry = new RecordingRegistry();
        TenantBotEventBridge<DemoU, DemoS> bridge = new TenantBotEventBridge<>(registry);

        bridge.onCreated(bot(555L));
        bridge.onTokenRotated(bot(555L));
        bridge.onDecommissioned(555L);

        assertThat(registry.calls).containsExactly("start:555", "restart:555", "stop:555");
    }

    @Test
    void aFailedTenantNeverEscapesIntoTheManagerBot() {
        RecordingRegistry registry = new RecordingRegistry();
        registry.failOnStart = true;
        TenantBotEventBridge<DemoU, DemoS> bridge = new TenantBotEventBridge<>(registry);

        assertThatCode(() -> bridge.onCreated(bot(555L))).doesNotThrowAnyException();
        assertThat(registry.calls).containsExactly("start:555");
    }

    @Test
    void anErrorFromStartNeverEscapesEither() {
        RecordingRegistry registry = new RecordingRegistry();
        registry.failOnStartWithError = true;
        TenantBotEventBridge<DemoU, DemoS> bridge = new TenantBotEventBridge<>(registry);

        assertThatCode(() -> bridge.onCreated(bot(555L))).doesNotThrowAnyException();
        assertThat(registry.calls).containsExactly("start:555");
    }

    @Test
    void aFailedTokenFetchStartsNothing() {
        RecordingRegistry registry = new RecordingRegistry();
        TenantBotEventBridge<DemoU, DemoS> bridge = new TenantBotEventBridge<>(registry);

        bridge.onTokenFetchFailed(555L, 7L, new IllegalStateException("boom"));

        assertThat(registry.calls).isEmpty();
    }
}
