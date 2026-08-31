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
            calls.add("replace:" + botUserId);
            return "999:ROTATED";
        }
    }

    static class RecordingEvents implements ManagedBotEvents {
        final List<String> events = new ArrayList<>();
        @Override public void onCreated(ManagedBot bot) { events.add("created:" + bot.botUserId()); }
        @Override public void onTokenRotated(ManagedBot bot) { events.add("rotated:" + bot.botUserId()); }
        @Override public void onTokenFetchFailed(long botUserId, long ownerUserId, Exception cause) {
            events.add("failed:" + botUserId);
        }
        @Override public void onDecommissioned(long botUserId) { events.add("decommissioned:" + botUserId); }
    }

    record Env(FakeBot bot, InMemoryManagedBotStore store, RecordingEvents events,
               ManagedBotService service) {}

    private static Env env() {
        FakeBot bot = new FakeBot();
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "manager_bot").bot(bot).build();
        InMemoryManagedBotStore store = new InMemoryManagedBotStore();
        RecordingEvents events = new RecordingEvents();
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
        assertThat(e.events().events).containsExactly("created:555");
    }

    @Test
    void aSecondUpdateForAKnownBotCountsAsARotationAndOverwrites() throws Exception {
        Env e = env();
        e.service().handleUpdate(managedBotUpdate(555L, 7L));
        e.bot().token = "999:NEW";

        e.service().handleUpdate(managedBotUpdate(555L, 7L));

        assertThat(e.store().findAll()).hasSize(1);
        assertThat(e.service().findToken(555L)).contains("999:NEW");
        assertThat(e.events().events).containsExactly("created:555", "rotated:555");
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
        assertThat(e.events().events).containsExactly("created:555");
    }

    @Test
    void rotateTokenStoresTheNewTokenAndAnnouncesIt() throws Exception {
        Env e = env();
        e.service().handleUpdate(managedBotUpdate(555L, 7L));
        e.events().events.clear();

        assertThat(e.service().rotateToken(555L)).isEqualTo("999:ROTATED");
        assertThat(e.service().findToken(555L)).contains("999:ROTATED");
        assertThat(e.events().events).containsExactly("rotated:555");
    }

    @Test
    void decommissionRevokesBeforeForgettingTheBot() throws Exception {
        Env e = env();
        e.service().handleUpdate(managedBotUpdate(555L, 7L));
        e.bot().calls.clear();

        e.service().decommission(555L);

        // revoke must happen while we still hold the row; the new token is discarded
        assertThat(e.bot().calls).containsExactly("replace:555");
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
    void findTokenIsEmptyForAnUnknownBot() {
        assertThat(env().service().findToken(404L)).isEmpty();
    }
}
