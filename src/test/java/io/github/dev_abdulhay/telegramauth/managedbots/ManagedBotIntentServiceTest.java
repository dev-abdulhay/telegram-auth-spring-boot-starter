package io.github.dev_abdulhay.telegramauth.managedbots;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBot;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedBotIntentServiceTest {

    /** Counts purges so the once-a-minute throttle is observable. */
    static class CountingIntentStore extends InMemoryManagedBotIntentStore {
        int purges;
        @Override public int deleteClosedBefore(OffsetDateTime cutoff) {
            purges++;
            return super.deleteClosedBefore(cutoff);
        }
    }

    static class RecordingEvents implements ManagedBotEvents {
        final List<String> events = new ArrayList<>();
        @Override public void onIntentClaimed(ManagedBotIntent intent) { events.add("claimed:" + intent.id()); }
        @Override public void onIntentMatched(ManagedBot bot, ManagedBotIntent intent) {
            events.add("matched:" + bot.botUserId() + ":" + intent.id());
        }
        @Override public void onIntentUnmatched(ManagedBot bot, List<ManagedBotIntent> candidates) {
            events.add("unmatched:" + bot.botUserId() + ":" + candidates.size());
        }
        @Override public void onIntentAmbiguous(ManagedBotIntent intent, List<ManagedBot> candidates) {
            events.add("ambiguous:" + intent.id() + ":" + candidates.size());
        }
    }

    record Env(InMemoryManagedBotStore bots, CountingIntentStore intents,
               RecordingEvents events, ManagedBotService service) { }

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode managedBotUpdate(long botId, long ownerId, String username) throws Exception {
        return M.readTree("{\"managed_bot\":{\"user\":{\"id\":" + ownerId + "},"
                + "\"bot\":{\"id\":" + botId + ",\"username\":\"" + username + "\",\"first_name\":\"T\"}}}");
    }

    /** Claims an intent the way the flow does, so matching tests start from a CLAIMED row. */
    private static String claimedIntent(Env e, long ownerUserId, String suggestedUsername) {
        String id = e.service().createIntent(suggestedUsername, "Shop", "bot:1").intentId();
        ManagedBotIntent stored = e.intents().findById(id).orElseThrow();
        e.intents().save(stored.claimedBy(ownerUserId, OffsetDateTime.now()));
        return id;
    }

    static Env env() {
        TelegramBot fake = new TelegramBot(HttpClient.newHttpClient(), "123:ABC") {
            @Override public String getManagedBotToken(long botUserId) { return "999:CHILD"; }
            @Override public void sendMessage(long chatId, String text, String replyMarkupJson) { }
        };
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "manager_bot").bot(fake).build();
        InMemoryManagedBotStore bots = new InMemoryManagedBotStore();
        CountingIntentStore intents = new CountingIntentStore();
        RecordingEvents events = new RecordingEvents();
        TokenEncryptor enc = new TokenEncryptor() {
            @Override public String encrypt(String p) { return "ENC(" + p + ")"; }
            @Override public String decrypt(String c) { return c.substring(4, c.length() - 1); }
        };
        ManagedBotService service = new ManagedBotService(module, bots, enc, events, 1, Duration.ZERO,
                intents, Duration.ofMinutes(30), Duration.ofDays(7));
        return new Env(bots, intents, events, service);
    }

    @Test
    void createIntentReturnsAManagerBotLinkCarryingTheIntentId() {
        ManagedBotIntentLink link = env().service().createIntent("tenant_shop_bot", "Shop", "bot:1");

        assertThat(link.intentId()).hasSize(22).matches("[A-Za-z0-9_-]+");
        assertThat(link.url()).isEqualTo("https://t.me/manager_bot?start=mb_" + link.intentId());
        assertThat(link.expiresAt()).isAfter(OffsetDateTime.now().plusMinutes(29));
    }

    @Test
    void createIntentStoresAnOpenIntentWithTheHostRef() {
        Env e = env();
        ManagedBotIntentLink link = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1");

        assertThat(e.service().findIntent(link.intentId())).get()
                .satisfies(i -> {
                    assertThat(i.status()).isEqualTo(ManagedBotIntentStatus.OPEN);
                    assertThat(i.hostRef()).isEqualTo("bot:1");
                    assertThat(i.suggestedUsername()).isEqualTo("tenant_shop_bot");
                    assertThat(i.ownerUserId()).isNull();
                });
    }

    @Test
    void createIntentRejectsAUsernameTelegramCouldNeverAccept() {
        assertThatThrownBy(() -> env().service().createIntent("nope", "Shop", "bot:1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBlankSuggestedUsernameIsAllowed() {
        Env e = env();
        ManagedBotIntentLink link = e.service().createIntent(null, null, null);

        assertThat(e.service().findIntent(link.intentId())).get()
                .extracting(ManagedBotIntent::suggestedUsername).isNull();
    }

    @Test
    void intentsAreOffWithoutAStore() {
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "manager_bot").build();
        ManagedBotService noIntents = new ManagedBotService(module, new InMemoryManagedBotStore(),
                new TokenEncryptor() {
                    @Override public String encrypt(String p) { return p; }
                    @Override public String decrypt(String c) { return c; }
                }, new ManagedBotEvents() { }, 1, Duration.ZERO);

        assertThatThrownBy(() -> noIntents.createIntent("tenant_shop_bot", "Shop", "bot:1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ManagedBotIntentStore");
    }

    @Test
    void anOpenIntentExpiresOnReadAndIsPersistedExpired() {
        Env e = env();
        ManagedBotIntentLink link = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1");
        ManagedBotIntent stored = e.intents().findById(link.intentId()).orElseThrow();
        e.intents().save(new ManagedBotIntent(stored.id(), stored.suggestedUsername(), stored.suggestedName(),
                stored.hostRef(), null, ManagedBotIntentStatus.OPEN, null,
                stored.createdAt().minusHours(2), null, null, stored.expiresAt().minusHours(2)));

        assertThat(e.service().findIntent(link.intentId())).get()
                .extracting(ManagedBotIntent::status).isEqualTo(ManagedBotIntentStatus.EXPIRED);
        assertThat(e.intents().findById(link.intentId())).get()
                .extracting(ManagedBotIntent::status).isEqualTo(ManagedBotIntentStatus.EXPIRED);
    }

    @Test
    void cancelWorksFromOpenAndFromClaimedButNotAfterThat() {
        Env e = env();
        String open = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();
        e.service().cancelIntent(open);
        assertThat(e.service().findIntent(open)).get()
                .extracting(ManagedBotIntent::status).isEqualTo(ManagedBotIntentStatus.CANCELLED);

        assertThatThrownBy(() -> e.service().cancelIntent(open))
                .isInstanceOf(ManagedBotIntentException.class)
                .extracting(t -> ((ManagedBotIntentException) t).reason())
                .isEqualTo(ManagedBotIntentException.Reason.INTENT_CLOSED);

        assertThatThrownBy(() -> e.service().cancelIntent("nope"))
                .isInstanceOf(ManagedBotIntentException.class)
                .extracting(t -> ((ManagedBotIntentException) t).reason())
                .isEqualTo(ManagedBotIntentException.Reason.INTENT_NOT_FOUND);
    }

    @Test
    void theRetentionPurgeRunsAtMostOnceAMinute() {
        Env e = env();
        e.service().createIntent("tenant_shop_bot", "Shop", "bot:1");
        e.service().createIntent("tenant_shop_bot", "Shop", "bot:2");
        e.service().createIntent("tenant_shop_bot", "Shop", "bot:3");

        assertThat(e.intents().purges).isEqualTo(1);
    }

    @Test
    void aCreatedBotCompletesTheOnlyClaimedIntentOfItsOwner() throws Exception {
        Env e = env();
        String id = claimedIntent(e, 7L, "tenant_shop_bot");

        e.service().handleUpdate(managedBotUpdate(555L, 7L, "edited_name_bot"));

        assertThat(e.intents().findById(id)).get().satisfies(i -> {
            assertThat(i.status()).isEqualTo(ManagedBotIntentStatus.COMPLETED);
            assertThat(i.botUserId()).isEqualTo(555L);
            assertThat(i.completedAt()).isNotNull();
        });
        assertThat(e.events().events).containsExactly("matched:555:" + id);
    }

    @Test
    void theUsernameBreaksATieBetweenTwoClaimedIntents() throws Exception {
        Env e = env();
        String wanted = claimedIntent(e, 7L, "tenant_shop_bot");
        claimedIntent(e, 7L, "tenant_cafe_bot");

        e.service().handleUpdate(managedBotUpdate(555L, 7L, "TENANT_SHOP_BOT"));

        assertThat(e.events().events).containsExactly("matched:555:" + wanted);
    }

    @Test
    void twoIndistinguishableIntentsAreHandedToTheHostInsteadOfGuessed() throws Exception {
        Env e = env();
        claimedIntent(e, 7L, "tenant_shop_bot");
        claimedIntent(e, 7L, "tenant_cafe_bot");

        e.service().handleUpdate(managedBotUpdate(555L, 7L, "something_else_bot"));

        assertThat(e.events().events).containsExactly("unmatched:555:2");
        assertThat(e.intents().findByBotUserId(555L)).isEmpty();
    }

    /**
     * On the creation path the newly stored bot's {@code createdAt} is always "now",
     * so the age filter in {@code candidatesFor} can never exclude anything here —
     * this proves something else: a stray unassigned bot that happens to share the
     * creator's account never crowds out the real match. The age filter itself is
     * exercised by the claim path in a later task.
     */
    @Test
    void aNewBotMatchesEvenWhenItsOwnerAlsoHoldsAnOlderUnassignedBot() throws Exception {
        Env e = env();
        OffsetDateTime longAgo = OffsetDateTime.now().minusDays(3);
        e.bots().save(new ManagedBot(555L, "old_bot", "Old", 7L, "ENC(x)", longAgo, longAgo));
        String id = claimedIntent(e, 7L, "tenant_shop_bot");

        e.service().handleUpdate(managedBotUpdate(556L, 7L, "tenant_shop_bot"));   // a different, new bot
        assertThat(e.intents().findById(id)).get()
                .extracting(ManagedBotIntent::botUserId).isEqualTo(556L);
    }

    @Test
    void anotherOwnersIntentIsNeverMatched() throws Exception {
        Env e = env();
        String id = claimedIntent(e, 8L, "tenant_shop_bot");

        e.service().handleUpdate(managedBotUpdate(555L, 7L, "tenant_shop_bot"));

        assertThat(e.intents().findById(id)).get()
                .extracting(ManagedBotIntent::status).isEqualTo(ManagedBotIntentStatus.CLAIMED);
        assertThat(e.events().events).isEmpty();
    }

    @Test
    void aRotationNeverMatchesAndAReDeliveredUpdateNeverMatchesTwice() throws Exception {
        Env e = env();
        String id = claimedIntent(e, 7L, "tenant_shop_bot");

        e.service().handleUpdate(managedBotUpdate(555L, 7L, "tenant_shop_bot"));
        e.service().handleUpdate(managedBotUpdate(555L, 7L, "tenant_shop_bot"));   // re-delivery = rotation

        assertThat(e.events().events).containsExactly("matched:555:" + id);
    }

    @Test
    void withNoCandidatesNothingHappensAtAll() throws Exception {
        Env e = env();

        e.service().handleUpdate(managedBotUpdate(555L, 7L, "tenant_shop_bot"));

        assertThat(e.events().events).isEmpty();
        assertThat(e.bots().findByBotUserId(555L)).isPresent();
    }

    @Test
    void recoveryThroughFetchAndStoreAlsoMatches() {
        Env e = env();
        String id = claimedIntent(e, 7L, "tenant_shop_bot");

        e.service().fetchAndStore(555L, 7L);

        assertThat(e.intents().findById(id)).get()
                .extracting(ManagedBotIntent::status).isEqualTo(ManagedBotIntentStatus.COMPLETED);
    }

    @Test
    void aListenerThrowingInOnCreatedDoesNotCostTheIntentEvent() throws Exception {
        Env base = env();
        List<String> seen = new ArrayList<>();
        ManagedBotEvents throwing = new ManagedBotEvents() {
            @Override public void onCreated(ManagedBot bot) { throw new IllegalStateException("host bug"); }
            @Override public void onIntentMatched(ManagedBot bot, ManagedBotIntent intent) {
                seen.add("matched:" + intent.id());
            }
        };
        ManagedBotService service = new ManagedBotService(
                TelegramBotModule.builder("123:ABC", "manager_bot").bot(new TelegramBot(
                        HttpClient.newHttpClient(), "123:ABC") {
                    @Override public String getManagedBotToken(long botUserId) { return "999:CHILD"; }
                }).build(),
                base.bots(), new TokenEncryptor() {
                    @Override public String encrypt(String p) { return p; }
                    @Override public String decrypt(String c) { return c; }
                }, throwing, 1, Duration.ZERO, base.intents(), Duration.ofMinutes(30), Duration.ofDays(7));
        String id = base.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();
        base.intents().save(base.intents().findById(id).orElseThrow().claimedBy(7L, OffsetDateTime.now()));

        service.handleUpdate(managedBotUpdate(555L, 7L, "tenant_shop_bot"));

        assertThat(seen).containsExactly("matched:" + id);
        assertThat(base.intents().findById(id)).get()
                .extracting(ManagedBotIntent::status).isEqualTo(ManagedBotIntentStatus.COMPLETED);
    }

    @Test
    void theFirstClaimRecordsTheOwnerAndAnnouncesIt() {
        Env e = env();
        String id = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();

        IntentClaimResult r = e.service().claimIntent(id, 7L);

        assertThat(r.outcome()).isEqualTo(IntentClaim.CLAIMED);
        assertThat(r.intent().ownerUserId()).isEqualTo(7L);
        assertThat(r.intent().claimedAt()).isNotNull();
        assertThat(e.events().events).containsExactly("claimed:" + id);
    }

    @Test
    void theSameUserTappingAgainIsIdempotent() {
        Env e = env();
        String id = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();
        e.service().claimIntent(id, 7L);

        assertThat(e.service().claimIntent(id, 7L).outcome()).isEqualTo(IntentClaim.RECLAIMED);
        assertThat(e.events().events).containsExactly("claimed:" + id);
    }

    @Test
    void aForwardedLinkCannotBeStolenBySomeoneElse() {
        Env e = env();
        String id = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();
        e.service().claimIntent(id, 7L);

        IntentClaimResult r = e.service().claimIntent(id, 8L);

        assertThat(r.outcome()).isEqualTo(IntentClaim.OTHER_OWNER);
        assertThat(e.service().findIntent(id)).get()
                .extracting(ManagedBotIntent::ownerUserId).isEqualTo(7L);
    }

    @Test
    void anUnknownPayloadIsNotOursAndAClosedOneIs() {
        Env e = env();
        String id = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();
        e.service().cancelIntent(id);

        assertThat(e.service().claimIntent("not-an-intent", 7L).outcome()).isEqualTo(IntentClaim.UNKNOWN);
        assertThat(e.service().claimIntent(id, 7L).outcome()).isEqualTo(IntentClaim.CLOSED);
    }

    @Test
    void claimingCompletesTheIntentWhenTheBotWasCreatedFirst() {
        Env e = env();
        String id = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();
        OffsetDateTime now = OffsetDateTime.now();
        e.bots().save(new ManagedBot(555L, "tenant_shop_bot", "Shop", 7L, "ENC(x)", now, now));

        IntentClaimResult r = e.service().claimIntent(id, 7L);

        assertThat(r.intent().status()).isEqualTo(ManagedBotIntentStatus.COMPLETED);
        assertThat(r.intent().botUserId()).isEqualTo(555L);
        assertThat(e.events().events).containsExactly("claimed:" + id, "matched:555:" + id);
    }

    @Test
    void claimingWithTwoUnassignedBotsAsksTheHostToDecide() {
        Env e = env();
        String id = e.service().createIntent(null, "Shop", "bot:1").intentId();
        OffsetDateTime now = OffsetDateTime.now();
        e.bots().save(new ManagedBot(555L, "one_bot", "One", 7L, "ENC(x)", now, now));
        e.bots().save(new ManagedBot(556L, "two_bot", "Two", 7L, "ENC(x)", now, now));

        IntentClaimResult r = e.service().claimIntent(id, 7L);

        assertThat(r.intent().status()).isEqualTo(ManagedBotIntentStatus.CLAIMED);
        assertThat(e.events().events).containsExactly("claimed:" + id, "ambiguous:" + id + ":2");
    }

    @Test
    void claimingIgnoresBotsOlderThanTheIntent() {
        Env e = env();
        String id = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();
        OffsetDateTime longAgo = OffsetDateTime.now().minusDays(3);
        e.bots().save(new ManagedBot(555L, "tenant_shop_bot", "Shop", 7L, "ENC(x)", longAgo, longAgo));

        IntentClaimResult r = e.service().claimIntent(id, 7L);

        assertThat(r.intent().status()).isEqualTo(ManagedBotIntentStatus.CLAIMED);
        assertThat(e.events().events).containsExactly("claimed:" + id);
    }
}
