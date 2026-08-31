package io.github.dev_abdulhay.telegramauth.managedbots;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dev_abdulhay.telegramauth.bot.TelegramApiException;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBot;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedBotServiceTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** Records calls and returns canned answers; no HTTP anywhere. */
    static class FakeBot extends TelegramBot {
        final List<String> calls = new ArrayList<>();
        String token = "999:CHILD";
        int failFetches;
        /** Set by env() so ordering-sensitive fakes can observe the store's state. */
        ManagedBotTokenStore store;
        JsonNode accessSettings;

        FakeBot() {
            super(HttpClient.newHttpClient(), "123:ABC");
        }

        @Override public String getManagedBotToken(long botUserId) {
            calls.add("get:" + botUserId);
            if (failFetches > 0) {
                failFetches--;
                throw new TelegramApiException(500, "transient");
            }
            return token;
        }

        @Override public String replaceManagedBotToken(long botUserId) {
            // Recording whether the row is still there is what lets a test catch a
            // reversed decommission() that deletes before revoking.
            boolean stillStored = store != null && store.findByBotUserId(botUserId).isPresent();
            calls.add("replace:" + botUserId + ":stillStored=" + stillStored);
            return "999:ROTATED";
        }

        @Override public JsonNode getManagedBotAccessSettings(long botUserId) {
            calls.add("getAccess:" + botUserId);
            return accessSettings;
        }

        @Override public void setManagedBotAccessSettings(long botUserId, boolean restricted, List<Long> addedUserIds) {
            calls.add("setAccess:" + botUserId + ":restricted=" + restricted + ":ids=" + addedUserIds);
        }
    }

    static class RecordingEvents implements ManagedBotEvents {
        final List<String> events = new ArrayList<>();
        private final ManagedBotTokenStore store;

        RecordingEvents(ManagedBotTokenStore store) {
            this.store = store;
        }

        @Override public void onCreated(ManagedBot bot) {
            // Recording whether the store already has the row is what lets a test
            // catch a reversed handleUpdate() that publishes before persisting.
            events.add("created:" + bot.botUserId() + ":stored=" + isStored(bot));
        }

        @Override public void onTokenRotated(ManagedBot bot) {
            events.add("rotated:" + bot.botUserId() + ":stored=" + isStored(bot));
        }

        @Override public void onTokenFetchFailed(long botUserId, long ownerUserId, Exception cause) {
            events.add("failed:" + botUserId);
        }

        @Override public void onDecommissioned(long botUserId) { events.add("decommissioned:" + botUserId); }

        private boolean isStored(ManagedBot bot) {
            return store.findByBotUserId(bot.botUserId())
                    .map(b -> b.encryptedToken().equals(bot.encryptedToken()))
                    .orElse(false);
        }
    }

    record Env(FakeBot bot, InMemoryManagedBotStore store, RecordingEvents events,
               ManagedBotService service) {}

    private static Env env() {
        FakeBot bot = new FakeBot();
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "manager_bot").bot(bot).build();
        InMemoryManagedBotStore store = new InMemoryManagedBotStore();
        bot.store = store;
        RecordingEvents events = new RecordingEvents(store);
        TokenEncryptor enc = new TokenEncryptor() {
            @Override public String encrypt(String p) { return "ENC(" + p + ")"; }
            @Override public String decrypt(String c) { return c.substring(4, c.length() - 1); }
        };
        ManagedBotService service = new ManagedBotService(module, store, enc, events, 3, Duration.ZERO);
        return new Env(bot, store, events, service);
    }

    private static JsonNode managedBotUpdate(long botId, long ownerId) throws Exception {
        return M.readTree("{\"managed_bot\":{\"user\":{\"id\":" + ownerId + "},"
                + "\"bot\":{\"id\":" + botId + ",\"username\":\"tenant_bot\",\"first_name\":\"Tenant\"}}}");
    }

    @Test
    void createLinkUsesTheModuleUsername() {
        assertThat(env().service().createLink("tenant_shop_bot", "Shop"))
                .isEqualTo("https://t.me/newbot/manager_bot/tenant_shop_bot?name=Shop");
    }

    @Test
    void aNewManagedBotIsStoredEncryptedAndThenAnnounced() throws Exception {
        Env e = env();

        e.service().handleUpdate(managedBotUpdate(555L, 7L));

        assertThat(e.store().findByBotUserId(555L)).get()
                .extracting(ManagedBot::encryptedToken).isEqualTo("ENC(999:CHILD)");
        assertThat(e.service().findToken(555L)).contains("999:CHILD");
        assertThat(e.events().events).containsExactly("created:555:stored=true");
    }

    @Test
    void aSecondUpdateForAKnownBotCountsAsARotationAndOverwrites() throws Exception {
        Env e = env();
        e.service().handleUpdate(managedBotUpdate(555L, 7L));
        e.bot().token = "999:NEW";

        e.service().handleUpdate(managedBotUpdate(555L, 7L));

        assertThat(e.store().findAll()).hasSize(1);
        assertThat(e.service().findToken(555L)).contains("999:NEW");
        assertThat(e.events().events).containsExactly("created:555:stored=true", "rotated:555:stored=true");
    }

    @Test
    void aFetchThatKeepsFailingStoresNothingAndReportsFailure() throws Exception {
        Env e = env();
        e.bot().failFetches = 5; // more than the 3 configured attempts

        e.service().handleUpdate(managedBotUpdate(555L, 7L));

        assertThat(e.store().findByBotUserId(555L)).isEmpty();
        assertThat(e.events().events).containsExactly("failed:555");
        assertThat(e.bot().calls).hasSize(3);
    }

    @Test
    void aTransientFailureIsRetriedAndThenSucceeds() throws Exception {
        Env e = env();
        e.bot().failFetches = 2;

        e.service().handleUpdate(managedBotUpdate(555L, 7L));

        assertThat(e.store().findByBotUserId(555L)).isPresent();
        assertThat(e.events().events).containsExactly("created:555:stored=true");
    }

    @Test
    void rotateTokenStoresTheNewTokenAndAnnouncesIt() throws Exception {
        Env e = env();
        e.service().handleUpdate(managedBotUpdate(555L, 7L));
        e.events().events.clear();

        assertThat(e.service().rotateToken(555L)).isEqualTo("999:ROTATED");
        assertThat(e.service().findToken(555L)).contains("999:ROTATED");
        assertThat(e.events().events).containsExactly("rotated:555:stored=true");
    }

    @Test
    void decommissionRevokesBeforeForgettingTheBot() throws Exception {
        Env e = env();
        e.service().handleUpdate(managedBotUpdate(555L, 7L));
        e.bot().calls.clear();

        e.service().decommission(555L);

        // revoke must happen while we still hold the row; the new token is discarded
        assertThat(e.bot().calls).containsExactly("replace:555:stillStored=true");
        assertThat(e.store().findByBotUserId(555L)).isEmpty();
        assertThat(e.events().events).contains("decommissioned:555");
    }

    @Test
    void decommissionStillForgetsTheBotWhenRevocationFails() throws Exception {
        Env e = env();
        e.service().handleUpdate(managedBotUpdate(555L, 7L));
        FakeBot failing = new FakeBot() {
            @Override public String replaceManagedBotToken(long botUserId) {
                throw new TelegramApiException(400, "BOT_NOT_FOUND");
            }
        };
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "manager_bot").bot(failing).build();
        ManagedBotService service = new ManagedBotService(module, e.store(),
                new TokenEncryptor() {
                    @Override public String encrypt(String p) { return "ENC(" + p + ")"; }
                    @Override public String decrypt(String c) { return c.substring(4, c.length() - 1); }
                }, e.events(), 3, Duration.ZERO);

        service.decommission(555L);

        assertThat(e.store().findByBotUserId(555L)).isEmpty();
    }

    @Test
    void moreThanTenAddedUsersIsRejectedBeforeCallingTelegram() {
        Env e = env();
        List<Long> eleven = new ArrayList<>();
        for (long i = 1; i <= 11; i++) eleven.add(i);

        assertThatThrownBy(() -> e.service().setAccessSettings(555L, true, eleven))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10");
        assertThat(e.bot().calls).isEmpty();
    }

    @Test
    void getAccessSettingsMapsARestrictedResponseWithAddedUsers() throws Exception {
        Env e = env();
        e.bot().accessSettings = M.readTree("{\"is_access_restricted\":true,\"added_users\":"
                + "[{\"id\":10,\"username\":\"alice\",\"first_name\":\"Alice\"}]}");

        BotAccess access = e.service().getAccessSettings(555L);

        assertThat(access.restricted()).isTrue();
        assertThat(access.addedUsers()).containsExactly(new ManagedBotUser(10L, "alice", "Alice"));
    }

    @Test
    void getAccessSettingsTreatsAMissingAddedUsersKeyAsAnOpenEmptyList() throws Exception {
        Env e = env();
        e.bot().accessSettings = M.readTree("{\"is_access_restricted\":false}");

        BotAccess access = e.service().getAccessSettings(555L);

        assertThat(access.restricted()).isFalse();
        assertThat(access.addedUsers()).isEmpty();
    }

    @Test
    void setAccessSettingsWithAValidListReachesTheClientWithIdsIntact() {
        Env e = env();
        List<Long> ids = List.of(1L, 2L, 3L);

        e.service().setAccessSettings(555L, true, ids);

        assertThat(e.bot().calls).containsExactly("setAccess:555:restricted=true:ids=" + ids);
    }

    @Test
    void findTokenIsEmptyForAnUnknownBot() {
        assertThat(env().service().findToken(404L)).isEmpty();
    }

    /**
     * {@code decommission} revokes by calling {@code replaceManagedBotToken}, which
     * IS a token change — so Telegram delivers a {@code managed_bot} update for it.
     * Without the echo guard that update finds an unknown bot, fetches the fresh
     * working token and re-creates the row we just deleted, making the documented
     * "left unreachable by us" promise false.
     */
    @Test
    void theEchoOfOurOwnDecommissionDoesNotResurrectTheBot() throws Exception {
        Env e = env();
        e.service().handleUpdate(managedBotUpdate(555L, 7L));
        e.service().decommission(555L);
        e.events().events.clear();

        e.service().handleUpdate(managedBotUpdate(555L, 7L));

        assertThat(e.store().findAll()).isEmpty();
        assertThat(e.service().findToken(555L)).isEmpty();
        assertThat(e.events().events).isEmpty();
    }

    @Test
    void aRotationAndItsEchoAnnounceExactlyOneRotation() throws Exception {
        Env e = env();
        e.service().handleUpdate(managedBotUpdate(555L, 7L));
        e.events().events.clear();

        e.service().rotateToken(555L);
        e.service().handleUpdate(managedBotUpdate(555L, 7L));

        assertThat(e.events().events).containsExactly("rotated:555:stored=true");
    }

    /** The rotation guard is one-shot: the owner's own later rotation still counts. */
    @Test
    void anOwnerRotationAfterOursIsStillAnnounced() throws Exception {
        Env e = env();
        e.service().handleUpdate(managedBotUpdate(555L, 7L));
        e.service().rotateToken(555L);
        e.service().handleUpdate(managedBotUpdate(555L, 7L)); // our echo, swallowed
        e.events().events.clear();

        e.service().handleUpdate(managedBotUpdate(555L, 7L)); // the owner's doing

        assertThat(e.events().events).containsExactly("rotated:555:stored=true");
    }

    @Test
    void fetchAndStoreRecoversABotWhoseUpdateHandlingGaveUp() throws Exception {
        Env e = env();
        e.bot().failFetches = 5; // more than the 3 configured attempts
        e.service().handleUpdate(managedBotUpdate(555L, 7L));
        assertThat(e.store().findByBotUserId(555L)).isEmpty();

        ManagedBot recovered = e.service().fetchAndStore(555L, 7L);

        assertThat(recovered.botUserId()).isEqualTo(555L);
        assertThat(e.service().findToken(555L)).contains("999:CHILD");
        assertThat(e.events().events).containsExactly("failed:555", "created:555:stored=true");
    }

    /** It throws instead of re-publishing onTokenFetchFailed, so recovering from
     *  inside that callback cannot loop. */
    @Test
    void fetchAndStorePropagatesAFailureInsteadOfRepublishingIt() {
        Env e = env();
        e.bot().failFetches = 5;

        assertThatThrownBy(() -> e.service().fetchAndStore(555L, 7L))
                .isInstanceOf(TelegramApiException.class);
        assertThat(e.store().findByBotUserId(555L)).isEmpty();
        assertThat(e.events().events).isEmpty();
    }
}
