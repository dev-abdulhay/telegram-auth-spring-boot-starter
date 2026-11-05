package io.github.dev_abdulhay.telegramauth.flow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlowMessagesTest {

    @Test
    void everyKeyHasTextInAllThreeLanguages() {
        for (FlowMessages.Key key : FlowMessages.Key.values()) {
            for (String lang : new String[] {"uz", "ru", "en"}) {
                assertThat(FlowMessages.text(key, lang))
                        .as("%s/%s", key, lang).isNotBlank();
            }
        }
    }
}
