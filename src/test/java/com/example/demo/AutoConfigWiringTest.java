package com.example.demo;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotLifecycle;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = DemoApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "telegram.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:wiring;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
class AutoConfigWiringTest {

    @Autowired(required = false) TokenGenerator tokenGenerator;
    @Autowired(required = false) TelegramBotLifecycle lifecycle;
    @Autowired DemoSessionService sessionService; // proves host beans wire via TokenGenerator

    @Test
    void starterInfraBeansArePresent() {
        assertThat(tokenGenerator).isNotNull();
        assertThat(lifecycle).isNotNull();
        assertThat(sessionService).isNotNull();
    }
}
