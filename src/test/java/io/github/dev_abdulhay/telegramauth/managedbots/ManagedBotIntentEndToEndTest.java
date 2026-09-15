package io.github.dev_abdulhay.telegramauth.managedbots;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dev_abdulhay.telegramauth.bot.BotUpdateDispatcher;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBot;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the managed-bot intent pieces compose the way a deployment actually runs
 * them: a {@code getUpdates} JSON batch, routed by {@link BotUpdateDispatcher},
 * landing in {@link ManagedBotIntentFlow} for {@code /start}, and in
 * {@link ManagedBotService#handleUpdate} for {@code managed_bot} — with a plain
 * {@code /start} command handler standing in for the login flow it must never
 * steal from. A failure here means the wiring is wrong, not the test.
 */
class ManagedBotIntentEndToEndTest {

    /**
     * Records {@code sendMessage} calls the way {@code ManagedBotIntentFlowTest}'s
     * does, and additionally stubs {@code getManagedBotToken} the way
     * {@code ManagedBotServiceTest}'s {@code FakeBot} does — this test drives both
     * the {@code /start} prompt and a real {@code managed_bot} update through one bot.
     */
    static class RecordingBot extends TelegramBot {
        final List<String> sent = new ArrayList<>();
        RecordingBot() { super(HttpClient.newHttpClient(), "123:ABC"); }
        @Override public void sendMessage(long chatId, String text) { sent.add(chatId + ":" + text); }
        @Override public void sendMessage(long chatId, String text, String markup) {
            sent.add(chatId + ":" + text + ":" + markup);
        }
        @Override public String getManagedBotToken(long botUserId) { return "999:CHILD"; }
    }

    static class RecordingEvents implements ManagedBotEvents {
        final List<String> events = new ArrayList<>();
        @Override public void onIntentClaimed(ManagedBotIntent intent) { events.add("claimed:" + intent.id()); }
        @Override public void onIntentMatched(ManagedBot bot, ManagedBotIntent intent) {
            events.add("matched:" + bot.botUserId() + ":" + intent.id());
        }
    }

    record Env(RecordingBot bot, InMemoryManagedBotIntentStore intents, RecordingEvents events,
               ManagedBotService service, TelegramBotModule module, BotUpdateDispatcher dispatcher,
               List<JsonNode> loginUpdates) { }

    /**
     * Wires one {@link TelegramBotModule} the way a manager bot is actually wired:
     * the {@code managed_bot} slot to {@link ManagedBotService#handleUpdate}, a
     * {@link ManagedBotIntentFlow} constructed over that same service, and a plain
     * {@code /start} command handler standing in for the login flow.
     */
    private static Env env() {
        RecordingBot bot = new RecordingBot();
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "manager_bot").bot(bot).build();
        InMemoryManagedBotIntentStore intents = new InMemoryManagedBotIntentStore();
        InMemoryManagedBotStore bots = new InMemoryManagedBotStore();
        RecordingEvents events = new RecordingEvents();
        ManagedBotService service = new ManagedBotService(module, bots,
                new TokenEncryptor() {
                    @Override public String encrypt(String p) { return p; }
                    @Override public String decrypt(String c) { return c; }
                }, events, 1, Duration.ZERO,
                intents, Duration.ofMinutes(30), Duration.ofDays(7));

        module.onManagedBot(service::handleUpdate);
        new ManagedBotIntentFlow(module, service);
        List<JsonNode> loginUpdates = new ArrayList<>();
        module.command("/start", loginUpdates::add);

        BotUpdateDispatcher dispatcher = new BotUpdateDispatcher(module);
        return new Env(bot, intents, events, service, module, dispatcher, loginUpdates);
    }

    /** A {@code getUpdates} batch carrying one {@code /start <payload>} from a private chat. */
    private static String startBatch(long updateId, String payload, long userId, long chatId) {
        return "{\"ok\":true,\"result\":[{\"update_id\":" + updateId + ","
                + "\"message\":{\"text\":\"/start " + payload + "\","
                + "\"from\":{\"id\":" + userId + ",\"language_code\":\"uz\"},"
                + "\"chat\":{\"id\":" + chatId + "}}}]}";
    }

    /** A {@code getUpdates} batch carrying one {@code managed_bot} update, shaped as in {@code ManagedBotServiceTest}. */
    private static String managedBotBatch(long updateId, long botId, long ownerId, String username) {
        return "{\"ok\":true,\"result\":[{\"update_id\":" + updateId + ","
                + "\"managed_bot\":{\"user\":{\"id\":" + ownerId + "},"
                + "\"bot\":{\"id\":" + botId + ",\"username\":\"" + username + "\",\"first_name\":\"Tenant\"}}}]}";
    }

    @Test
    void fromIntentLinkToAMatchedBotThroughTheDispatcher() throws Exception {
        Env e = env();
        String intentId = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();

        // /start mb_<intentId>, from a private chat, through the real dispatcher.
        long firstMaxId = e.dispatcher().dispatch(startBatch(1, "mb_" + intentId, 7L, 7L));

        assertThat(firstMaxId).isEqualTo(1);
        assertThat(e.bot().sent).hasSize(1);
        assertThat(e.bot().sent.get(0))
                .contains("https://t.me/newbot/manager_bot/tenant_shop_bot?name=Shop")
                .contains("inline_keyboard");
        assertThat(e.loginUpdates()).as("the login /start handler must not fire for our own payload").isEmpty();

        // The bot that gets created has an EDITED username — different from the suggestion
        // above — which is exactly the case this whole feature exists for.
        long secondMaxId = e.dispatcher().dispatch(managedBotBatch(2, 555L, 7L, "edited_username_bot"));

        assertThat(secondMaxId).isEqualTo(2);
        assertThat(e.intents().findById(intentId)).get().satisfies(intent -> {
            assertThat(intent.status()).isEqualTo(ManagedBotIntentStatus.COMPLETED);
            assertThat(intent.botUserId()).isEqualTo(555L);
        });
        assertThat(e.events().events).containsExactly("claimed:" + intentId, "matched:555:" + intentId);
        assertThat(e.loginUpdates()).as("the login /start handler never ran").isEmpty();
    }

    @Test
    void aLoginTokenBeginningWithTheIntentPrefixStillLogsIn() throws Exception {
        Env e = env();

        e.dispatcher().dispatch(startBatch(1, "mb_ThisIsALoginTokenNotAnIntent", 7L, 7L));

        assertThat(e.loginUpdates()).hasSize(1);
        assertThat(e.loginUpdates().get(0).path("message").path("text").asText())
                .isEqualTo("/start mb_ThisIsALoginTokenNotAnIntent");
        assertThat(e.bot().sent).isEmpty();
    }
}
