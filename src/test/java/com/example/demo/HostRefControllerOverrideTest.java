package com.example.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBot;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import io.github.dev_abdulhay.telegramauth.web.AbstractTelegramAuthController;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RestController;

import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Spec §11.6: a controller subclass overriding {@code hostRef(HttpServletRequest)}
 * is the only way {@code hostRef} can be set through the stock controller, and
 * {@code hostRef} decides whose account an approval may bind to.
 *
 * <p>Standalone MockMvc, not the shared {@code DemoApp} Spring context
 * {@link ControllerFlowTest} boots — {@link DemoAuthController} already claims
 * {@code /api/demo-auth} there, so a second controller with an overridden
 * {@code hostRef()} gets its own wiring instead of colliding on the path or
 * getting swept into every other {@code @SpringBootTest(classes = DemoApp.class)}
 * by the package-wide component scan.
 */
class HostRefControllerOverrideTest {

    @RestController
    static class HostRefAuthController extends AbstractTelegramAuthController<DemoUser, DemoSession> {
        HostRefAuthController(DemoSessionService service, TelegramBotModule module) {
            super(service, module);
        }

        @Override
        protected String hostRef(HttpServletRequest request) {
            return "platform-session:42";
        }
    }

    @Test
    void anOverriddenHostRefIsPersistedOnTheCreatedSession() throws Exception {
        TelegramBotModule module = TelegramBotModule.builder("123:ABC", "demo_bot")
                .bot(new TelegramBot(HttpClient.newHttpClient(), "123:ABC") {
                    @Override public void sendMessage(long chatId, String text) { }
                })
                .build();
        DemoSessionService service = new DemoSessionService(new StubSessionRepo(), new TokenGenerator(), module);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new HostRefAuthController(service, module)).build();

        MvcResult res = mvc.perform(post("/session")).andReturn();
        JsonNode body = new ObjectMapper().readTree(res.getResponse().getContentAsString());
        String token = body.get("token").asText();

        assertThat(service.findByRawToken(token).orElseThrow().getHostRef())
                .isEqualTo("platform-session:42");
    }
}
