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
}
