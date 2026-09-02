package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBot;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotEvents;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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

    /** A host's own {@code ManagedBotEvents} bean, recording into a shared log. */
    static class RecordingHostEvents implements ManagedBotEvents {
        final List<String> calls;
        boolean fail;

        RecordingHostEvents(List<String> calls) { this.calls = calls; }

        @Override public void onCreated(ManagedBot bot) { record("onCreated:" + bot.botUserId()); }
        @Override public void onTokenRotated(ManagedBot bot) { record("onTokenRotated:" + bot.botUserId()); }
        @Override public void onDecommissioned(long id) { record("onDecommissioned:" + id); }
        @Override public void onTokenFetchFailed(long id, long ownerId, Exception cause) {
            record("onTokenFetchFailed:" + id + ":" + ownerId);
        }

        private void record(String call) {
            calls.add("host:" + call);
            if (fail) throw new IllegalStateException("the host hook blew up");
        }
    }

    /**
     * The live view of the context's {@code ManagedBotEvents} beans, which is what
     * the auto-configuration hands the bridge. Backed by a mutable list so a test
     * can add the bridge itself — the shape Spring produces, since the bridge is
     * one of those beans — after constructing it. Only {@code orderedStream()} is
     * exercised; the rest exists to satisfy the interface.
     */
    private static ObjectProvider<ManagedBotEvents> beans(List<ManagedBotEvents> candidates) {
        return new ObjectProvider<>() {
            @Override public ManagedBotEvents getObject() { return candidates.get(0); }
            @Override public ManagedBotEvents getObject(Object... args) { return candidates.get(0); }
            @Override public ManagedBotEvents getIfAvailable() {
                return candidates.isEmpty() ? null : candidates.get(0);
            }
            @Override public ManagedBotEvents getIfUnique() {
                return candidates.size() == 1 ? candidates.get(0) : null;
            }
            @Override public Stream<ManagedBotEvents> stream() { return candidates.stream(); }
            @Override public Stream<ManagedBotEvents> orderedStream() { return stream(); }
        };
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

    /**
     * The bridge is the {@code ManagedBotEvents} bean the service is wired with, so
     * a host's own bean would be locked out of the lifecycle unless the bridge
     * hands the events on. All four callbacks, registry work first.
     */
    @Test
    void aHostsOwnEventsBeanReceivesEveryCallbackAfterTheRegistryWork() {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingHostEvents host = new RecordingHostEvents(registry.calls);
        TenantBotEventBridge<DemoU, DemoS> bridge =
                new TenantBotEventBridge<>(registry, beans(new ArrayList<>(List.of(host))));

        bridge.onCreated(bot(555L));
        bridge.onTokenRotated(bot(555L));
        bridge.onDecommissioned(555L);
        bridge.onTokenFetchFailed(555L, 7L, new IllegalStateException("boom"));

        assertThat(registry.calls).containsExactly(
                "start:555", "host:onCreated:555",
                "restart:555", "host:onTokenRotated:555",
                "stop:555", "host:onDecommissioned:555",
                "host:onTokenFetchFailed:555:7");
    }

    /**
     * A host hook is no more trusted than the registry: it runs after the tenant is
     * already up, and its failure is swallowed on the same grounds — this is the
     * manager bot's update worker thread.
     */
    @Test
    void aThrowingHostEventsBeanNeitherBlocksTheRegistryNorEscapes() {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingHostEvents host = new RecordingHostEvents(registry.calls);
        host.fail = true;
        TenantBotEventBridge<DemoU, DemoS> bridge =
                new TenantBotEventBridge<>(registry, beans(new ArrayList<>(List.of(host))));

        assertThatCode(() -> bridge.onCreated(bot(555L))).doesNotThrowAnyException();

        assertThat(registry.calls).containsExactly("start:555", "host:onCreated:555");
    }

    /**
     * The trap this guard exists for: the bridge is itself a {@code ManagedBotEvents}
     * bean, and being {@code @Primary} it is exactly what a by-type lookup returns.
     * Forwarding to it would re-enter {@code onCreated} — which is why the filter is
     * by identity and runs at call time, when the bean finally exists.
     */
    @Test
    void theBridgeNeverForwardsToItself() {
        RecordingRegistry registry = new RecordingRegistry();
        List<ManagedBotEvents> candidates = new ArrayList<>();
        TenantBotEventBridge<DemoU, DemoS> bridge =
                new TenantBotEventBridge<>(registry, beans(candidates));
        candidates.add(bridge); // as Spring sees it: the bridge is one of the beans

        bridge.onCreated(bot(555L));

        assertThat(registry.calls).containsExactly("start:555");
    }

    /**
     * The proxy-safety trap: nothing in a stock context proxies this bean, but a
     * host with a broad auto-proxy creator or aspect can end up with a JDK or
     * CGLIB proxy of the bridge among the candidates. Plain {@code != this} does
     * not recognise that proxy as the bridge, so it would forward into it — and
     * the proxy delegates straight back into {@code onCreated}, recursing until
     * {@code StackOverflowError} (caught by {@code guard}, but only after a log
     * storm and thousands of {@code registry.start} calls). Built with Spring's
     * own {@code ProxyFactory}, exactly what such a host's infrastructure would
     * hand back: a real JDK dynamic proxy around the bridge, not a hand-rolled
     * stand-in.
     */
    @Test
    void theBridgeUnwrapsAnAopProxyOfItselfInsteadOfRecursingIntoIt() {
        RecordingRegistry registry = new RecordingRegistry();
        List<ManagedBotEvents> candidates = new ArrayList<>();
        TenantBotEventBridge<DemoU, DemoS> bridge =
                new TenantBotEventBridge<>(registry, beans(candidates));
        ManagedBotEvents proxiedSelf = (ManagedBotEvents) new ProxyFactory(bridge).getProxy();
        candidates.add(proxiedSelf); // what a host's auto-proxy creator would put in the context

        bridge.onCreated(bot(555L));

        assertThat(registry.calls).containsExactly("start:555");
    }

    /** A host bean alongside the bridge is forwarded to; the bridge itself still is not. */
    @Test
    void selfFilteringDoesNotCostTheHostItsCallback() {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingHostEvents host = new RecordingHostEvents(registry.calls);
        List<ManagedBotEvents> candidates = new ArrayList<>(List.of(host));
        TenantBotEventBridge<DemoU, DemoS> bridge =
                new TenantBotEventBridge<>(registry, beans(candidates));
        candidates.add(bridge);

        bridge.onCreated(bot(555L));

        assertThat(registry.calls).containsExactly("start:555", "host:onCreated:555");
    }

    /** No host bean at all: the bridge is the only candidate and nothing is forwarded. */
    @Test
    void anEmptyCandidateListChangesNothing() {
        RecordingRegistry registry = new RecordingRegistry();
        TenantBotEventBridge<DemoU, DemoS> bridge =
                new TenantBotEventBridge<>(registry, beans(new ArrayList<>()));

        bridge.onCreated(bot(555L));
        bridge.onTokenFetchFailed(555L, 7L, new IllegalStateException("boom"));

        assertThat(registry.calls).containsExactly("start:555");
    }
}
