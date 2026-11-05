package io.github.dev_abdulhay.telegramauth.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedBotRoutingTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static TelegramBotModule module() {
        return TelegramBotModule.builder("123:ABC", "manager_bot").build();
    }

    @Test
    void managedBotUpdatesReachTheManagedBotHandler() throws Exception {
        TelegramBotModule module = module();
        List<Long> seen = new ArrayList<>();
        module.onManagedBot(u -> seen.add(u.path("managed_bot").path("bot").path("id").asLong()));

        String json = "{\"ok\":true,\"result\":[{\"update_id\":1,\"managed_bot\":"
                + "{\"user\":{\"id\":7},\"bot\":{\"id\":555,\"username\":\"tenant_bot\"}}}]}";
        new BotUpdateDispatcher(module).dispatch(json);

        assertThat(seen).containsExactly(555L);
    }

    @Test
    void ordinaryUpdatesAreStillRoutedAsBefore() throws Exception {
        TelegramBotModule module = module();
        List<String> commands = new ArrayList<>();
        List<String> managed = new ArrayList<>();
        module.command("/start", u -> commands.add("start"));
        module.onManagedBot(u -> managed.add("managed"));

        String json = "{\"ok\":true,\"result\":[{\"update_id\":1,\"message\":"
                + "{\"text\":\"/start\",\"chat\":{\"id\":7},\"from\":{\"id\":7}}}]}";
        new BotUpdateDispatcher(module).dispatch(json);

        assertThat(commands).containsExactly("start");
        assertThat(managed).isEmpty();
    }

    @Test
    void theSlotRefusesASecondHandler() {
        TelegramBotModule module = module();
        module.onManagedBot(u -> { });

        assertThatThrownBy(() -> module.onManagedBot(u -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("managed_bot");
    }

    @Test
    void aManagedBotUpdateWithNoHandlerFallsBackToTheModuleFallback() throws Exception {
        TelegramBotModule module = module();
        List<JsonNode> fallback = new ArrayList<>();
        module.fallback(fallback::add);

        String json = "{\"ok\":true,\"result\":[{\"update_id\":1,\"managed_bot\":"
                + "{\"user\":{\"id\":7},\"bot\":{\"id\":555}}}]}";
        new BotUpdateDispatcher(module).dispatch(json);

        assertThat(fallback).hasSize(1);
    }
}
