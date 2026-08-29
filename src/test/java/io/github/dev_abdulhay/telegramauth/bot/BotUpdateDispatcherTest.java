package io.github.dev_abdulhay.telegramauth.bot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BotUpdateDispatcherTest {

    private TelegramBotModule module() {
        return TelegramBotModule.builder("123:ABC", "demo_bot")
                .bot(new TelegramBot(java.net.http.HttpClient.newHttpClient(), "x") {
                    @Override public void sendMessage(long chatId, String text) { }
                })
                .build();
    }

    @Test
    void routesCommandStrippingArgsAndBotSuffix() {
        TelegramBotModule m = module();
        AtomicReference<JsonNode> seen = new AtomicReference<>();
        m.command("/start", seen::set);
        BotUpdateDispatcher d = new BotUpdateDispatcher(m);

        String json = "{\"ok\":true,\"result\":[{\"update_id\":10,"
                + "\"message\":{\"text\":\"/start@demo_bot TOKEN123\",\"chat\":{\"id\":5}}}]}";
        long maxId = d.dispatch(json);

        assertThat(maxId).isEqualTo(10);
        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().path("message").path("chat").path("id").asLong()).isEqualTo(5);
    }

    @Test
    void callbackQueryRoutesToCallbackHandler() {
        TelegramBotModule m = module();
        AtomicReference<JsonNode> cb = new AtomicReference<>();
        AtomicReference<JsonNode> fb = new AtomicReference<>();
        m.onCallbackQuery(cb::set);
        m.fallback(fb::set);
        BotUpdateDispatcher d = new BotUpdateDispatcher(m);

        String json = "{\"ok\":true,\"result\":[{\"update_id\":7,"
                + "\"callback_query\":{\"id\":\"c1\",\"data\":\"tgauth:approve:T\"}}]}";
        assertThat(d.dispatch(json)).isEqualTo(7);
        assertThat(cb.get()).isNotNull();
        assertThat(fb.get()).isNull();
    }

    @Test
    void contactRoutesToContactHandler() {
        TelegramBotModule m = module();
        AtomicReference<JsonNode> contact = new AtomicReference<>();
        m.onContact(contact::set);
        BotUpdateDispatcher d = new BotUpdateDispatcher(m);

        String json = "{\"ok\":true,\"result\":[{\"update_id\":8,"
                + "\"message\":{\"chat\":{\"id\":5},\"contact\":{\"user_id\":5,\"phone_number\":\"+998\"}}}]}";
        assertThat(d.dispatch(json)).isEqualTo(8);
        assertThat(contact.get()).isNotNull();
    }

    @Test
    void nonOkAndUnparseableResponsesSignalBackoff() {
        BotUpdateDispatcher d = new BotUpdateDispatcher(module());
        assertThat(d.dispatch("{\"ok\":false,\"error_code\":401}")).isEqualTo(-1);
        assertThat(d.dispatch("garbage")).isEqualTo(-1);
        assertThat(d.dispatch("{\"ok\":true,\"result\":[]}")).isEqualTo(0);
    }

    @Test
    void routingFailureStillAdvancesTheOffset() {
        TelegramBotModule m = module();
        m.command("/start", u -> { });
        // an executor that refuses work (worker shut down mid-batch) makes route() itself throw
        BotUpdateDispatcher d = new BotUpdateDispatcher(m, r -> { throw new RejectedExecutionException("down"); });

        String json = "{\"ok\":true,\"result\":["
                + "{\"update_id\":41,\"message\":{\"text\":\"/start A\",\"chat\":{\"id\":5}}},"
                + "{\"update_id\":42,\"message\":{\"text\":\"/start B\",\"chat\":{\"id\":5}}}]}";

        // -1 would rewind the offset and make Telegram redeliver the whole batch
        assertThat(d.dispatch(json)).isEqualTo(42);
    }

    @Test
    void plainTextRoutesToTheTextHandlerInsteadOfTheFallback() {
        TelegramBotModule m = module();
        AtomicReference<JsonNode> text = new AtomicReference<>();
        AtomicReference<JsonNode> fb = new AtomicReference<>();
        m.onText(text::set);
        m.fallback(fb::set);
        BotUpdateDispatcher d = new BotUpdateDispatcher(m);

        String json = "{\"ok\":true,\"result\":[{\"update_id\":11,"
                + "\"message\":{\"text\":\"42\",\"chat\":{\"id\":5}}}]}";
        assertThat(d.dispatch(json)).isEqualTo(11);
        assertThat(text.get()).isNotNull();
        assertThat(fb.get()).isNull();
    }

    @Test
    void unregisteredCommandsAlsoReachTheTextHandler() {
        TelegramBotModule m = module();
        AtomicReference<JsonNode> text = new AtomicReference<>();
        m.command("/start", u -> { });
        m.onText(text::set);
        BotUpdateDispatcher d = new BotUpdateDispatcher(m);

        String json = "{\"ok\":true,\"result\":[{\"update_id\":12,"
                + "\"message\":{\"text\":\"/nope\",\"chat\":{\"id\":5}}}]}";
        assertThat(d.dispatch(json)).isEqualTo(12);
        assertThat(text.get()).isNotNull();
    }

    @Test
    void textHandlerSlotRefusesASecondRegistration() {
        TelegramBotModule m = module();
        m.onText(u -> { });
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> m.onText(u -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("text handler is already registered");
    }

    @Test
    void sessionTtlDefaultsToFiveMinutes() {
        assertThat(module().getSessionTtl()).isEqualTo(java.time.Duration.ofMinutes(5));
    }

    @Test
    void unknownAndNonCommandUpdatesGoToFallback() {
        TelegramBotModule m = module();
        AtomicReference<JsonNode> fb = new AtomicReference<>();
        m.fallback(fb::set);
        BotUpdateDispatcher d = new BotUpdateDispatcher(m);

        String json = "{\"ok\":true,\"result\":[{\"update_id\":3,"
                + "\"message\":{\"text\":\"hello there\",\"chat\":{\"id\":9}}}]}";
        long maxId = d.dispatch(json);

        assertThat(maxId).isEqualTo(3);
        assertThat(fb.get()).isNotNull();
    }
}
