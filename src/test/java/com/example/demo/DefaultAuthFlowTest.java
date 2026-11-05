package com.example.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBot;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.flow.DefaultAuthFlow;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAuthFlowTest {

    @Test
    void registersUserAndApprovesSession() throws Exception {
        AtomicReference<String> sent = new AtomicReference<>();
        TelegramBot fake = new TelegramBot(HttpClient.newHttpClient(), "x") {
            @Override public void sendMessage(long chatId, String text) { sent.set(text); }
        };
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "demo_bot")
                .bot(fake)
                .approveHandler((info, ctx) -> new io.github.dev_abdulhay.telegramauth.api.dto.AuthApproveResult(
                        Map.of("tgId", info.telegramId())))
                .build();

        StubUserRepo userRepo = new StubUserRepo();
        StubSessionRepo sessionRepo = new StubSessionRepo();
        DemoUserService userService = new DemoUserService(userRepo);
        DemoSessionService sessionService = new DemoSessionService(sessionRepo, new TokenGenerator(), module);

        // self-registration happens in the ctor
        new DefaultAuthFlow<>(userService, sessionService, module,
                DefaultAuthFlow.Options.builder()
                        .codeConfirmation(io.github.dev_abdulhay.telegramauth.flow.CodeConfirmation.OFF)
                        .build());
        assertThat(module.getCommands()).containsKey("/start");

        var created = sessionService.create("ip", "ua");
        String raw = created.rawToken();

        String update = new ObjectMapper().writeValueAsString(Map.of(
                "message", Map.of(
                        "text", "/start " + raw,
                        "chat", Map.of("id", 555L),
                        "from", Map.of("id", 555L, "first_name", "Ali", "language_code", "uz"))));

        module.getCommands().get("/start").accept(new ObjectMapper().readTree(update));

        assertThat(userService.findByTelegramId(555L)).isPresent();
        assertThat(((BaseAuthSession) created.entity()).getStatus()).isEqualTo(BaseAuthSession.Status.APPROVED);
        assertThat(sent.get()).isNotBlank();
    }
}
