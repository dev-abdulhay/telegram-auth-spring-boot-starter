package com.example.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.miniapp.InMemoryInitDataReplayStore;
import io.github.dev_abdulhay.telegramauth.miniapp.InitDataReplayStore;
import io.github.dev_abdulhay.telegramauth.miniapp.MiniAppTestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = DemoApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(MiniAppControllerTest.ReplayStoreConfig.class)
@TestPropertySource(properties = {
        "telegram.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:miniappctrl;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
class MiniAppControllerTest {

    /** A host-provided store; it must replace the auto-configured one and be what the controller claims on. */
    static class RecordingReplayStore implements InitDataReplayStore {
        final List<String> claimed = new CopyOnWriteArrayList<>();
        private final InitDataReplayStore delegate = new InMemoryInitDataReplayStore(Clock.systemUTC());

        @Override
        public boolean claim(String hash, Instant expiresAt) {
            claimed.add(hash);
            return delegate.claim(hash, expiresAt);
        }

        @Override
        public void release(String hash) {
            delegate.release(hash);
        }
    }

    @TestConfiguration
    static class ReplayStoreConfig {
        @Bean
        RecordingReplayStore recordingReplayStore() {
            return new RecordingReplayStore();
        }
    }

    /** {@link DemoTgConfig}'s bot token. */
    private static final String BOT_TOKEN = "TEST";

    @Autowired MockMvc mvc;
    @Autowired DemoUserRepository users;
    @Autowired DemoSessionService sessionService;
    @Autowired RecordingReplayStore replayStore;
    private final ObjectMapper json = new ObjectMapper();

    /** Fresh signed initData; a unique {@code query_id} keeps hashes distinct across tests sharing the replay store. */
    private static String initData(long telegramId, String username, String startParam) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("query_id", UUID.randomUUID().toString());
        f.put("user", "{\"id\":" + telegramId + ",\"first_name\":\"Ali\",\"username\":\"" + username + "\"}");
        f.put("auth_date", String.valueOf(Instant.now().getEpochSecond()));
        if (startParam != null) {
            f.put("start_param", startParam);
        }
        return MiniAppTestSigner.sign(BOT_TOKEN, f);
    }

    private ResultActions postMiniApp(String path, Map<String, Object> body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    @Test
    void validInitDataReturnsApprovedPayload() throws Exception {
        postMiniApp("/api/demo-auth/miniapp", Map.of("initData", initData(42, "ali", "order_7")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.payload.tgId").value(42))
                .andExpect(jsonPath("$.payload.channel").value("MINI_APP"))
                .andExpect(jsonPath("$.payload.startParam").value("order_7"));

        DemoUser saved = users.findByTelegramId(42L).orElseThrow();
        assertThat(saved.getUsername()).isEqualTo("ali");
    }

    @Test
    void bodyTelegramIdIsIgnored() throws Exception {
        postMiniApp("/api/demo-auth/miniapp",
                Map.of("initData", initData(42, "ali", null), "telegramId", 999))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.tgId").value(42));
        assertThat(users.findByTelegramId(999L)).isEmpty();
    }

    @Test
    void tamperedInitDataIs401() throws Exception {
        String signed = initData(42, "ali", null);
        char last = signed.charAt(signed.length() - 1);
        String tampered = signed.substring(0, signed.length() - 1) + (last == '0' ? '1' : '0');

        postMiniApp("/api/demo-auth/miniapp", Map.of("initData", tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("init_data_invalid"));
    }

    @Test
    void missingInitDataIs400() throws Exception {
        postMiniApp("/api/demo-auth/miniapp", Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("init_data_missing"));
    }

    @Test
    void replayIsRejected() throws Exception {
        String raw = initData(43, "vali", null);
        postMiniApp("/api/demo-auth/miniapp", Map.of("initData", raw)).andExpect(status().isOk());

        postMiniApp("/api/demo-auth/miniapp", Map.of("initData", raw))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("init_data_expired"));
    }

    @Test
    void customReplayStoreBeanIsTheOneTheControllerUses(@Autowired List<InitDataReplayStore> stores) throws Exception {
        assertThat(stores).containsExactly(replayStore);
        String raw = initData(45, "ali", null);
        String hash = raw.substring(raw.lastIndexOf("hash=") + "hash=".length());

        postMiniApp("/api/demo-auth/miniapp", Map.of("initData", raw)).andExpect(status().isOk());

        assertThat(replayStore.claimed).contains(hash);
    }

    @Test
    void blockedUserIs403AndHandlerNotCalled() throws Exception {
        DemoUser blocked = new DemoUser();
        blocked.setTelegramId(77L);
        blocked.setStatus(BaseTelegramUser.Status.BLOCKED);
        blocked.setCreatedAt(OffsetDateTime.now());
        blocked.setUpdatedAt(OffsetDateTime.now());
        users.save(blocked);

        MvcResult res = postMiniApp("/api/demo-auth/miniapp", Map.of("initData", initData(77, "bad", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("user_blocked"))
                .andReturn();
        // handler-not-called is proven in MiniAppLoginServiceTest; over HTTP we can only see no payload leaked
        assertThat(json.readTree(res.getResponse().getContentAsString()).has("payload")).isFalse();
    }

    @Test
    void legacyControllerHasNoMiniApp() throws Exception {
        postMiniApp("/api/legacy-auth/miniapp", Map.of("initData", initData(44, "ali", null)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/legacy-auth/session")).andExpect(status().isOk());
    }

    @Test
    void botLoginStillReportsBotLoginChannel() throws Exception {
        MvcResult createRes = mvc.perform(post("/api/demo-auth/session")).andExpect(status().isOk()).andReturn();
        String token = json.readTree(createRes.getResponse().getContentAsString()).get("token").asText();

        MvcResult async = mvc.perform(get("/api/demo-auth/session/{t}/poll", token)).andReturn();
        DemoUser u = new DemoUser();
        u.setTelegramId(123L);
        sessionService.approve(sessionService.hash(token), u);

        MvcResult done = mvc.perform(asyncDispatch(async)).andExpect(status().isOk()).andReturn();
        JsonNode wait = json.readTree(done.getResponse().getContentAsString());
        assertThat(wait.get("payload").get("tgId").asLong()).isEqualTo(123L);
        assertThat(wait.get("payload").get("channel").asText()).isEqualTo("BOT_LOGIN");
    }
}
