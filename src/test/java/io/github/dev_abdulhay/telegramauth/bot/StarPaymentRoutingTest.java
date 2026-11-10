package io.github.dev_abdulhay.telegramauth.bot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StarPaymentRoutingTest {

    private TelegramBotModule module() {
        return TelegramBotModule.builder("123:ABC", "demo_bot")
                .bot(new TelegramBot(java.net.http.HttpClient.newHttpClient(), "x"))
                .build();
    }

    private static String updates(String update) {
        return "{\"ok\":true,\"result\":[" + update + "]}";
    }

    @Test
    void preCheckoutUpdateReachesItsHandler() {
        TelegramBotModule m = module();
        AtomicReference<JsonNode> seen = new AtomicReference<>();
        m.onPreCheckoutQuery(seen::set);

        new BotUpdateDispatcher(m).dispatch(updates("{\"update_id\":1,\"pre_checkout_query\":{\"id\":\"q1\"}}"));

        assertThat(seen.get().path("pre_checkout_query").path("id").asText()).isEqualTo("q1");
    }

    @Test
    void successfulPaymentMessageBypassesTextAndCommands() {
        TelegramBotModule m = module();
        AtomicReference<JsonNode> paid = new AtomicReference<>();
        AtomicReference<JsonNode> start = new AtomicReference<>();
        m.onSuccessfulPayment(paid::set);
        m.command("/start", start::set);

        new BotUpdateDispatcher(m).dispatch(updates(
                "{\"update_id\":2,\"message\":{\"text\":\"/start\",\"successful_payment\":{\"total_amount\":5}}}"));

        assertThat(paid.get()).isNotNull();
        assertThat(start.get()).isNull();
    }

    @Test
    void refundedPaymentReachesItsHandler() {
        TelegramBotModule m = module();
        AtomicReference<JsonNode> seen = new AtomicReference<>();
        m.onRefundedPayment(seen::set);

        new BotUpdateDispatcher(m).dispatch(updates(
                "{\"update_id\":3,\"message\":{\"refunded_payment\":{\"total_amount\":5}}}"));

        assertThat(seen.get()).isNotNull();
    }

    @Test
    void paymentUpdatesWithoutHandlerGoToFallback() {
        TelegramBotModule m = module();
        java.util.List<JsonNode> fb = new java.util.ArrayList<>();
        m.fallback(fb::add);
        BotUpdateDispatcher d = new BotUpdateDispatcher(m);

        d.dispatch(updates("{\"update_id\":1,\"pre_checkout_query\":{\"id\":\"q\"}}"));
        d.dispatch(updates("{\"update_id\":2,\"message\":{\"successful_payment\":{}}}"));
        d.dispatch(updates("{\"update_id\":3,\"message\":{\"refunded_payment\":{}}}"));

        assertThat(fb).hasSize(3);
    }

    @Test
    void secondPreCheckoutHandlerIsRejected() {
        TelegramBotModule m = module();
        m.onPreCheckoutQuery(u -> { });

        assertThatThrownBy(() -> m.onPreCheckoutQuery(u -> { })).isInstanceOf(IllegalStateException.class);
    }
}
