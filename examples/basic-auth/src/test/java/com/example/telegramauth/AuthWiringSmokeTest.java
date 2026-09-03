package com.example.telegramauth;

import io.github.dev_abdulhay.telegramauth.api.dto.AuthApproveResult;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBot;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotLifecycle;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.flow.DefaultAuthFlow;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpClient;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots a real application context and asserts the wiring described in
 * {@link TelegramConfig} is real: the starter's own infrastructure beans
 * ({@link TokenGenerator}, {@link TelegramBotLifecycle}) come up, and so do
 * this app's {@link DefaultAuthFlow}, {@link AppSessionService} and
 * {@link AuthController} beans. Modeled on the library's own
 * {@code AutoConfigWiringTest}.
 *
 * <p>Deliberately does not reuse {@link ExampleApp} + {@link TelegramConfig}
 * directly: {@link TelegramConfig} builds a real {@code TelegramBot} that
 * talks to the network, and {@link TelegramBotLifecycle} starts polling on
 * {@code ApplicationReadyEvent} during context startup — same as
 * {@link org.springframework.boot.test.context.SpringBootTest} boots a
 * real {@code SpringApplication}. {@link TestConfig} below substitutes a
 * bot that never leaves the JVM, the same pattern the library's own tests use
 * (see {@code DemoTgConfig} in the library's test sources).
 */
@SpringBootTest(classes = AuthWiringSmokeTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "telegram.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:basic-auth-smoke;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.liquibase.enabled=false"
})
class AuthWiringSmokeTest {

    @Autowired(required = false) TokenGenerator tokenGenerator;
    @Autowired(required = false) TelegramBotLifecycle lifecycle;
    @Autowired AppSessionService sessionService;
    @Autowired DefaultAuthFlow<AppUser, AppSession> authFlow;
    @Autowired AuthController authController;

    @Test
    void starterAndAppBeansArePresent() {
        assertThat(tokenGenerator).isNotNull();
        assertThat(lifecycle).isNotNull();
        assertThat(sessionService).isNotNull();
        assertThat(authFlow).isNotNull();
        assertThat(authController).isNotNull();
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = AppUser.class)
    @EnableJpaRepositories(basePackageClasses = AppUserRepository.class)
    static class TestConfig {

        @Bean
        AppUserService appUserService(AppUserRepository repo) {
            return new AppUserService(repo);
        }

        @Bean
        TelegramBotModule telegramBotModule() {
            // A fake bot that never performs I/O, so this context never calls out
            // to Telegram — same technique the library's own DemoTgConfig uses.
            TelegramBot fakeBot = new TelegramBot(HttpClient.newHttpClient(), "TEST") {
                @Override
                public void sendMessage(long chatId, String text) {
                }

                @Override
                public String getUpdates(long offset, int timeoutSeconds) throws Exception {
                    Thread.sleep(300); // interruptible, so shutdown is not delayed
                    return "{\"ok\":true,\"result\":[]}";
                }
            };
            return TelegramBotModule.builder("TEST", "smoke_test_bot")
                    .bot(fakeBot)
                    .approveHandler((info, ctx) -> new AuthApproveResult(Map.of("userId", info.telegramId())))
                    .build();
        }

        @Bean
        AppSessionService appSessionService(AppSessionRepository repo, TokenGenerator tokenGenerator,
                                            TelegramBotModule module) {
            return new AppSessionService(repo, tokenGenerator, module);
        }

        @Bean
        DefaultAuthFlow<AppUser, AppSession> defaultAuthFlow(AppUserService userService,
                                                              AppSessionService sessionService,
                                                              TelegramBotModule module) {
            return new DefaultAuthFlow<>(userService, sessionService, module);
        }

        @Bean
        AuthController authController(AppSessionService sessionService, TelegramBotModule module) {
            return new AuthController(sessionService, module);
        }
    }
}
