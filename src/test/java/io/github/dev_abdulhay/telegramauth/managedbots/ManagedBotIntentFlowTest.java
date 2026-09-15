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

class ManagedBotIntentFlowTest {

    private static final ObjectMapper M = new ObjectMapper();

    static class RecordingBot extends TelegramBot {
        final List<String> sent = new ArrayList<>();
        RecordingBot() { super(HttpClient.newHttpClient(), "123:ABC"); }
        @Override public void sendMessage(long chatId, String text) { sent.add(chatId + ":" + text); }
        @Override public void sendMessage(long chatId, String text, String markup) {
            sent.add(chatId + ":" + text + ":" + markup);
        }
    }

    record Env(RecordingBot bot, InMemoryManagedBotIntentStore intents, InMemoryManagedBotStore bots,
               ManagedBotService service, ManagedBotIntentFlow flow, TelegramBotModule module) { }

    private static Env env() {
        RecordingBot bot = new RecordingBot();
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "manager_bot").bot(bot).build();
        InMemoryManagedBotIntentStore intents = new InMemoryManagedBotIntentStore();
        InMemoryManagedBotStore bots = new InMemoryManagedBotStore();
        ManagedBotService service = new ManagedBotService(module, bots,
                new TokenEncryptor() {
                    @Override public String encrypt(String p) { return p; }
                    @Override public String decrypt(String c) { return c; }
                }, new ManagedBotEvents() { }, 1, Duration.ZERO,
                intents, Duration.ofMinutes(30), Duration.ofDays(7));
        return new Env(bot, intents, bots, service, new ManagedBotIntentFlow(module, service), module);
    }

    private static JsonNode start(String payload, long userId, long chatId) throws Exception {
        return M.readTree("{\"message\":{\"text\":\"/start " + payload + "\","
                + "\"from\":{\"id\":" + userId + ",\"language_code\":\"uz\"},"
                + "\"chat\":{\"id\":" + chatId + "}}}");
    }

    @Test
    void theFlowClaimsTheStartPayloadPrefixOnConstruction() {
        assertThat(env().module().getStartPayloadRoutes()).containsOnlyKeys("mb_");
    }

    @Test
    void claimingSendsThePromptWithABotCreationButton() throws Exception {
        Env e = env();
        String id = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();

        assertThat(e.flow().onStart(start("mb_" + id, 7L, 7L))).isTrue();

        assertThat(e.intents().findById(id)).get()
                .extracting(ManagedBotIntent::ownerUserId).isEqualTo(7L);
        assertThat(e.bot().sent).hasSize(1);
        assertThat(e.bot().sent.get(0))
                .contains("https://t.me/newbot/manager_bot/tenant_shop_bot?name=Shop")
                .contains("inline_keyboard");
    }

    @Test
    void anUnknownPayloadIsNotOursSoNothingIsSaidAndTheUpdateFallsThrough() throws Exception {
        Env e = env();

        assertThat(e.flow().onStart(start("mb_notAnIntentId", 7L, 7L))).isFalse();
        assertThat(e.bot().sent).isEmpty();
    }

    @Test
    void aCancelledIntentAnswersWithTheInvalidLinkText() throws Exception {
        Env e = env();
        String id = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();
        e.service().cancelIntent(id);

        assertThat(e.flow().onStart(start("mb_" + id, 7L, 7L))).isTrue();
        assertThat(e.bot().sent.get(0)).contains("yaroqsiz");
    }

    @Test
    void aSecondUserIsToldTheLinkIsNotTheirs() throws Exception {
        Env e = env();
        String id = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();
        e.flow().onStart(start("mb_" + id, 7L, 7L));
        e.bot().sent.clear();

        assertThat(e.flow().onStart(start("mb_" + id, 8L, 8L))).isTrue();
        assertThat(e.bot().sent.get(0)).contains("boshqa foydalanuvchiga");
    }

    @Test
    void aCompletedIntentSaysTheBotAlreadyExists() throws Exception {
        Env e = env();
        String id = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();
        OffsetDateTime now = OffsetDateTime.now();
        e.intents().save(e.intents().findById(id).orElseThrow()
                .claimedBy(7L, now).completedWith(555L, now));

        assertThat(e.flow().onStart(start("mb_" + id, 7L, 7L))).isTrue();
        assertThat(e.bot().sent.get(0)).contains("allaqachon");
    }

    @Test
    void anIntentPayloadInAGroupChatIsSwallowedAndNeverAnswered() throws Exception {
        Env e = env();
        String id = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();

        assertThat(e.flow().onStart(start("mb_" + id, 7L, -100500L))).isTrue();
        assertThat(e.bot().sent).isEmpty();
        assertThat(e.intents().findById(id)).get()
                .extracting(ManagedBotIntent::status).isEqualTo(ManagedBotIntentStatus.OPEN);
    }

    @Test
    void aStartWithTheBotUsernameSuffixStillClaimsTheIntent() throws Exception {
        Env e = env();
        String id = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();

        // The dispatcher strips "@manager_bot" before matching the command but keeps
        // it in the text handed to the route, exactly as it appears here; the flow
        // must parse the payload the same way the dispatcher finds it.
        JsonNode update = M.readTree("{\"message\":{\"text\":\"/start@manager_bot mb_" + id + "\","
                + "\"from\":{\"id\":7,\"language_code\":\"uz\"},"
                + "\"chat\":{\"id\":7}}}");

        assertThat(e.flow().onStart(update)).isTrue();
        assertThat(e.bot().sent).hasSize(1);
        assertThat(e.bot().sent.get(0)).contains("inline_keyboard");
    }

    @Test
    void aClaimThatItselfCompletesTheIntentSendsAlreadyDoneNotThePrompt() throws Exception {
        Env e = env();
        String id = e.service().createIntent("tenant_shop_bot", "Shop", "bot:1").intentId();
        OffsetDateTime afterIntent = e.intents().findById(id).orElseThrow().createdAt().plusSeconds(1);
        // A bot already sitting in the store, for the same owner and username, created
        // after the intent: exactly what claim-time matching in ManagedBotService picks
        // up, so this first claim completes the intent instead of leaving it CLAIMED.
        e.bots().save(new ManagedBot(555L, "tenant_shop_bot", "Shop", 7L, "cipher",
                afterIntent, afterIntent));

        assertThat(e.flow().onStart(start("mb_" + id, 7L, 7L))).isTrue();

        assertThat(e.bot().sent).hasSize(1);
        assertThat(e.bot().sent.get(0)).contains("allaqachon");
    }
}
