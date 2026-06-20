package io.github.dev_abdulhay.telegramauth.bot;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dev_abdulhay.telegramauth.api.dto.AuthApproveResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramBotModuleTest {

    @Test
    void builderWiresDefaultsAndRegistry() {
        AtomicReference<JsonNode> seen = new AtomicReference<>();
        Consumer<JsonNode> handler = seen::set;

        TelegramBotModule m = TelegramBotModule.builder("123:ABCDEF", "demo_bot")
                .sessionTtl(Duration.ofMinutes(5))
                .approveHandler((info, ctx) -> new AuthApproveResult(Map.of("ok", true)))
                .build();
        m.command("/start", handler);

        assertThat(m.getUsername()).isEqualTo("demo_bot");
        assertThat(m.getSessionTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(m.getPollingTimeout()).isEqualTo(Duration.ofSeconds(30)); // default
        assertThat(m.getBot()).isNotNull();
        assertThat(m.getBus()).isNotNull();
        assertThat(m.getCommands()).containsKey("/start");
        assertThat(m.getApproveHandler().onApprove(null, null).payload()).containsEntry("ok", true);
    }

    @Test
    void customBotOverrideIsUsed() {
        TelegramBot fake = new TelegramBot(java.net.http.HttpClient.newHttpClient(), "x") {
            @Override public void sendMessage(long chatId, String text) { /* no network */ }
        };
        TelegramBotModule m = TelegramBotModule.builder("123:ABCDEF", "demo_bot")
                .bot(fake)
                .build();
        assertThat(m.getBot()).isSameAs(fake);
    }
}
