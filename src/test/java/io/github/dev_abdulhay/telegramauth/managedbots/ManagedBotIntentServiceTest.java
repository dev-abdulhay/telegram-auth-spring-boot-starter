package io.github.dev_abdulhay.telegramauth.managedbots;

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
}
