package io.github.dev_abdulhay.telegramauth.config;

import io.github.dev_abdulhay.telegramauth.api.TelegramAuthApproveHandler;
import io.github.dev_abdulhay.telegramauth.api.TelegramAuthRegisterHandler;
import io.github.dev_abdulhay.telegramauth.bot.BotUpdateDispatcher;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotClient;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotRunner;
import io.github.dev_abdulhay.telegramauth.repository.TelegramAuthSessionRepository;
import io.github.dev_abdulhay.telegramauth.repository.TelegramUserRepository;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import io.github.dev_abdulhay.telegramauth.service.AuthEventBus;
import io.github.dev_abdulhay.telegramauth.service.InMemoryAuthEventBus;
import io.github.dev_abdulhay.telegramauth.service.SessionService;
import io.github.dev_abdulhay.telegramauth.service.TelegramUserService;
import io.github.dev_abdulhay.telegramauth.web.TelegramAuthController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Entry point that activates the starter. Stays inert unless
 * {@code telegram.auth.enabled=true}.
 */
@AutoConfiguration(after = {HibernateJpaAutoConfiguration.class, JpaRepositoriesAutoConfiguration.class})
@ConditionalOnProperty(prefix = "telegram.auth", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TelegramAuthProperties.class)
@EnableScheduling
@EnableJpaRepositories(basePackages = "io.github.dev_abdulhay.telegramauth.repository")
@Import({TelegramAuthEntityScanConfig.class, TelegramAuthController.class})
public class TelegramAuthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TokenGenerator telegramAuthTokenGenerator() {
        return new TokenGenerator();
    }

    @Bean
    @ConditionalOnMissingBean
    public AuthEventBus telegramAuthEventBus() {
        return new InMemoryAuthEventBus();
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionService telegramAuthSessionService(TelegramAuthSessionRepository sessionRepo,
                                                     TelegramUserRepository userRepo,
                                                     TokenGenerator tokenGenerator,
                                                     AuthEventBus bus,
                                                     TelegramAuthApproveHandler approveHandler,
                                                     TelegramAuthProperties props) {
        return new SessionService(sessionRepo, userRepo, tokenGenerator, bus, approveHandler, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public TelegramUserService telegramAuthUserService(TelegramUserRepository userRepo,
                                                       org.springframework.beans.factory.ObjectProvider<TelegramAuthRegisterHandler> registerHandler) {
        return new TelegramUserService(userRepo, registerHandler.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public HttpClient telegramAuthHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public TelegramBotClient telegramAuthBotClient(HttpClient http, TelegramAuthProperties props) {
        return new TelegramBotClient(http, props.getBot().getToken());
    }

    @Bean
    @ConditionalOnMissingBean
    public BotUpdateDispatcher telegramAuthBotDispatcher(TelegramBotClient client,
                                                         SessionService sessionService,
                                                         TelegramUserService userService) {
        return new BotUpdateDispatcher(client, sessionService, userService);
    }

    @Bean
    @ConditionalOnMissingBean
    public TelegramBotRunner telegramAuthBotRunner(TelegramBotClient client,
                                                   BotUpdateDispatcher dispatcher,
                                                   TelegramAuthProperties props) {
        return new TelegramBotRunner(client, dispatcher, props);
    }
}
