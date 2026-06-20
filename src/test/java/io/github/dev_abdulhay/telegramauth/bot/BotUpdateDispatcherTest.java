package io.github.dev_abdulhay.telegramauth.bot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

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
