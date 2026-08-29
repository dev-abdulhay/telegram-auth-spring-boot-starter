package com.example.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = DemoApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "telegram.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:ctrlflow;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.liquibase.enabled=false"
})
class ControllerFlowTest {

    @Autowired MockMvc mvc;
    @Autowired DemoSessionService sessionService;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void createThenApproveReleasesPollWithPayload() throws Exception {
        MvcResult createRes = mvc.perform(post("/api/demo-auth/session"))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = json.readTree(createRes.getResponse().getContentAsString());
        String token = body.get("token").asText();
        assertThat(body.get("botDeepLink").asText()).contains("t.me/demo_bot?start=");

        // Start the long-poll (async), then approve out-of-band.
        MvcResult async = mvc.perform(get("/api/demo-auth/session/{t}/poll", token))
                .andExpect(request -> assertThat(request.getRequest().isAsyncStarted()).isTrue())
                .andReturn();

        DemoUser u = new DemoUser();
        u.setTelegramId(123L);
        sessionService.approve(sessionService.hash(token), u);

        MvcResult done = mvc.perform(asyncDispatch(async)).andExpect(status().isOk()).andReturn();
        JsonNode wait = json.readTree(done.getResponse().getContentAsString());
        assertThat(wait.get("status").asText()).isEqualTo("APPROVED");
        assertThat(wait.get("payload").get("tgId").asLong()).isEqualTo(123L);
    }

    @Test
    void statusOfMissingTokenIsGone() throws Exception {
        mvc.perform(get("/api/demo-auth/session/{t}/status", "nope")).andExpect(status().isGone());
    }

    private String newSessionToken() throws Exception {
        MvcResult res = mvc.perform(post("/api/demo-auth/session")).andExpect(status().isOk()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void pollWithSincePendingReturnsTheConfirmationCodeAlreadyAtTheCodeStage() throws Exception {
        String token = newSessionToken();
        String hash = sessionService.hash(token);
        sessionService.awaitCode(hash);

        MvcResult async = mvc.perform(get("/api/demo-auth/session/{t}/poll", token)
                .param("since", "PENDING")).andReturn();
        MvcResult done = mvc.perform(asyncDispatch(async)).andExpect(status().isAccepted()).andReturn();

        JsonNode body = json.readTree(done.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("AWAITING_CODE");
        assertThat(body.get("confirmCode").asInt())
                .isEqualTo(io.github.dev_abdulhay.telegramauth.security.ConfirmCode.of(hash));
    }

    @Test
    void pollWithSincePendingAlsoDeliversTheCodeOnTheLiveEvent() throws Exception {
        String token = newSessionToken();
        String hash = sessionService.hash(token);

        // subscribed while still PENDING, so the code must arrive through the bus
        MvcResult async = mvc.perform(get("/api/demo-auth/session/{t}/poll", token)
                .param("since", "PENDING")).andReturn();
        sessionService.awaitCode(hash);

        MvcResult done = mvc.perform(asyncDispatch(async)).andExpect(status().isAccepted()).andReturn();
        JsonNode body = json.readTree(done.getResponse().getContentAsString());
        assertThat(body.get("confirmCode").asInt())
                .isEqualTo(io.github.dev_abdulhay.telegramauth.security.ConfirmCode.of(hash));
    }

    @Test
    void pollWithoutSinceAnswersTheCodeTransitionWithNoContentSoTheClientRepolls() throws Exception {
        String token = newSessionToken();

        // a 0.3.0 client: subscribed while PENDING, never asked for the code step
        MvcResult async = mvc.perform(get("/api/demo-auth/session/{t}/poll", token)).andReturn();
        sessionService.awaitCode(sessionService.hash(token));

        mvc.perform(asyncDispatch(async)).andExpect(status().isNoContent());
    }

    @Test
    void pollWithoutSinceKeepsWaitingOnAnAlreadyAwaitingCodeSession() throws Exception {
        String token = newSessionToken();
        String hash = sessionService.hash(token);
        sessionService.awaitCode(hash);

        MvcResult async = mvc.perform(get("/api/demo-auth/session/{t}/poll", token)).andReturn();
        DemoUser u = new DemoUser();
        u.setTelegramId(11L);
        sessionService.approve(hash, u);

        // the half-finished state never short-circuits a 0.3.0 poll — only the terminal one does
        MvcResult done = mvc.perform(asyncDispatch(async)).andExpect(status().isOk()).andReturn();
        String body = done.getResponse().getContentAsString();
        assertThat(json.readTree(body).get("status").asText()).isEqualTo("APPROVED");
        assertThat(body).doesNotContain("confirmCode");
    }

    @Test
    void pollWithSinceAwaitingCodeWaitsForATerminalStateInsteadOfBusyLooping() throws Exception {
        String token = newSessionToken();
        String hash = sessionService.hash(token);
        sessionService.awaitCode(hash);

        MvcResult async = mvc.perform(get("/api/demo-auth/session/{t}/poll", token)
                .param("since", "AWAITING_CODE")).andReturn();
        DemoUser u = new DemoUser();
        u.setTelegramId(12L);
        sessionService.approve(hash, u);

        MvcResult done = mvc.perform(asyncDispatch(async)).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(done.getResponse().getContentAsString()).get("status").asText())
                .isEqualTo("APPROVED");
    }

    @Test
    void statusReportsAwaitingCodeButNeverTheCode() throws Exception {
        String token = newSessionToken();
        sessionService.awaitCode(sessionService.hash(token));

        MvcResult res = mvc.perform(get("/api/demo-auth/session/{t}/status", token))
                .andExpect(status().isOk()).andReturn();
        String body = res.getResponse().getContentAsString();
        assertThat(json.readTree(body).get("status").asText()).isEqualTo("AWAITING_CODE");
        assertThat(body).doesNotContain("confirmCode");
    }

    @Test
    void cancelRejectsASessionStuckAtTheCodeStep() throws Exception {
        String token = newSessionToken();
        sessionService.awaitCode(sessionService.hash(token));

        mvc.perform(delete("/api/demo-auth/session/{t}", token)).andExpect(status().isNoContent());

        assertThat(sessionService.findByRawToken(token).orElseThrow().getStatus())
                .isEqualTo(io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession.Status.REJECTED);
    }

    @Test
    void forwardedForUsesTheProxyAppendedEntryNotTheClientSuppliedOne() throws Exception {
        MvcResult createRes = mvc.perform(post("/api/demo-auth/session")
                        .header("X-Forwarded-For", "6.6.6.6, 10.0.0.9"))
                .andExpect(status().isOk()).andReturn();
        String token = json.readTree(createRes.getResponse().getContentAsString()).get("token").asText();

        // "6.6.6.6" is whatever the caller wrote; only the trailing entry came from the proxy
        assertThat(sessionService.findByRawToken(token).orElseThrow().getIpAddress()).isEqualTo("10.0.0.9");
    }

    @Test
    void pollAfterApprovalReturnsPersistedPayload() throws Exception {
        MvcResult createRes = mvc.perform(post("/api/demo-auth/session"))
                .andExpect(status().isOk()).andReturn();
        String token = json.readTree(createRes.getResponse().getContentAsString()).get("token").asText();

        DemoUser u = new DemoUser();
        u.setTelegramId(321L);
        sessionService.approve(sessionService.hash(token), u);

        // no live subscription was open during approval — payload must come from the DB
        MvcResult async = mvc.perform(get("/api/demo-auth/session/{t}/poll", token)).andReturn();
        MvcResult done = mvc.perform(asyncDispatch(async)).andExpect(status().isOk()).andReturn();
        JsonNode wait = json.readTree(done.getResponse().getContentAsString());
        assertThat(wait.get("status").asText()).isEqualTo("APPROVED");
        assertThat(wait.get("payload").get("tgId").asLong()).isEqualTo(321L);
    }
}
