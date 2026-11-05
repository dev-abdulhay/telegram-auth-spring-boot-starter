package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBot;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotEvents;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotIntent;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotIntentStatus;
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
        ManagedBotIntent lastClaimedIntent;
        ManagedBot lastMatchedBot;
        ManagedBotIntent lastMatchedIntent;
        ManagedBot lastUnmatchedBot;
        List<ManagedBotIntent> lastUnmatchedCandidates;
        ManagedBotIntent lastAmbiguousIntent;
        List<ManagedBot> lastAmbiguousCandidates;

        RecordingHostEvents(List<String> calls) { this.calls = calls; }

        @Override public void onCreated(ManagedBot bot) { record("onCreated:" + bot.botUserId()); }
        @Override public void onTokenRotated(ManagedBot bot) { record("onTokenRotated:" + bot.botUserId()); }
        @Override public void onDecommissioned(long id) { record("onDecommissioned:" + id); }
        @Override public void onTokenFetchFailed(long id, long ownerId, Exception cause) {
            record("onTokenFetchFailed:" + id + ":" + ownerId);
        }
        @Override public void onIntentClaimed(ManagedBotIntent intent) {
            lastClaimedIntent = intent;
            record("onIntentClaimed:" + intent.id());
        }
        @Override public void onIntentMatched(ManagedBot bot, ManagedBotIntent intent) {
            lastMatchedBot = bot;
            lastMatchedIntent = intent;
            record("onIntentMatched:" + bot.botUserId() + ":" + intent.id());
        }
        @Override public void onIntentUnmatched(ManagedBot bot, List<ManagedBotIntent> candidates) {
            lastUnmatchedBot = bot;
            lastUnmatchedCandidates = candidates;
            record("onIntentUnmatched:" + bot.botUserId() + ":" + candidates.size());
        }
        @Override public void onIntentAmbiguous(ManagedBotIntent intent, List<ManagedBot> candidates) {
            lastAmbiguousIntent = intent;
            lastAmbiguousCandidates = candidates;
            record("onIntentAmbiguous:" + intent.id() + ":" + candidates.size());
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

    private static ManagedBotIntent intent(String id) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ManagedBotIntent(id, "tenant_bot", "Tenant", "bot:1", 7L,
                ManagedBotIntentStatus.CLAIMED, null, now, now, null, now.plusMinutes(30));
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

    /**
     * Intent events carry no bot to start or stop, so unlike the 0.4.0 callbacks
     * there is no registry step to order against — only forwarding to verify. The
     * bridge itself sits among the candidates, exactly as Spring would hand it
     * back, so a broken identity filter would show up as extra or repeated calls
     * instead of the single one asserted here.
     */
    @Test
    void theFourIntentEventsReachTheHostExactlyOnceWithTheSameValues() {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingHostEvents host = new RecordingHostEvents(new ArrayList<>());
        List<ManagedBotEvents> candidates = new ArrayList<>(List.of(host));
        TenantBotEventBridge<DemoU, DemoS> bridge =
                new TenantBotEventBridge<>(registry, beans(candidates));
        candidates.add(bridge); // as Spring sees it: the bridge is one of the beans

        ManagedBot bot = bot(555L);
        ManagedBotIntent claimed = intent("i1");
        List<ManagedBotIntent> unmatchedCandidates = List.of(claimed);
        List<ManagedBot> ambiguousCandidates = List.of(bot);

        bridge.onIntentClaimed(claimed);
        bridge.onIntentMatched(bot, claimed);
        bridge.onIntentUnmatched(bot, unmatchedCandidates);
        bridge.onIntentAmbiguous(claimed, ambiguousCandidates);

        assertThat(host.calls).containsExactly(
                "host:onIntentClaimed:i1",
                "host:onIntentMatched:555:i1",
                "host:onIntentUnmatched:555:1",
                "host:onIntentAmbiguous:i1:1");
        assertThat(host.lastClaimedIntent).isSameAs(claimed);
        assertThat(host.lastMatchedBot).isSameAs(bot);
        assertThat(host.lastMatchedIntent).isSameAs(claimed);
        assertThat(host.lastUnmatchedBot).isSameAs(bot);
        assertThat(host.lastUnmatchedCandidates).isSameAs(unmatchedCandidates);
        assertThat(host.lastAmbiguousIntent).isSameAs(claimed);
        assertThat(host.lastAmbiguousCandidates).isSameAs(ambiguousCandidates);
        assertThat(registry.calls).isEmpty();
    }

    /**
     * A host hook is no more trusted here than on the 0.4.0 events: its failure
     * must not reach the manager bot's update worker thread, whether or not there
     * was registry work in front of it to protect.
     */
    @Test
    void aHostDelegateThrowingOnAnIntentEventDoesNotEscapeTheBridge() {
        RecordingRegistry registry = new RecordingRegistry();
        RecordingHostEvents host = new RecordingHostEvents(new ArrayList<>());
        host.fail = true;
        TenantBotEventBridge<DemoU, DemoS> bridge =
                new TenantBotEventBridge<>(registry, beans(new ArrayList<>(List.of(host))));
        ManagedBot bot = bot(555L);
        ManagedBotIntent claimed = intent("i1");

        assertThatCode(() -> {
            bridge.onIntentClaimed(claimed);
            bridge.onIntentMatched(bot, claimed);
            bridge.onIntentUnmatched(bot, List.of(claimed));
            bridge.onIntentAmbiguous(claimed, List.of(bot));
        }).doesNotThrowAnyException();

        assertThat(host.calls).containsExactly(
                "host:onIntentClaimed:i1",
                "host:onIntentMatched:555:i1",
                "host:onIntentUnmatched:555:1",
                "host:onIntentAmbiguous:i1:1");
        assertThat(registry.calls).isEmpty();
    }

    /**
     * Unlike {@code onCreated}/{@code onTokenRotated}/{@code onDecommissioned}, no
     * intent event starts, restarts or stops a tenant bot — a bot's polling
     * lifecycle is driven only by those three. Proved here with no host bean at
     * all, so nothing but the registry itself could produce a call.
     */
    @Test
    void intentEventsDriveNoRegistryWork() {
        RecordingRegistry registry = new RecordingRegistry();
        TenantBotEventBridge<DemoU, DemoS> bridge = new TenantBotEventBridge<>(registry);
        ManagedBot bot = bot(555L);
        ManagedBotIntent claimed = intent("i1");

        bridge.onIntentClaimed(claimed);
        bridge.onIntentMatched(bot, claimed);
        bridge.onIntentUnmatched(bot, List.of(claimed));
        bridge.onIntentAmbiguous(claimed, List.of(bot));

        assertThat(registry.calls).isEmpty();
    }
}
