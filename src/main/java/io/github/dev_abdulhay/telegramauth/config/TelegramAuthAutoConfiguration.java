package io.github.dev_abdulhay.telegramauth.config;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotLifecycle;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.flow.DefaultAuthFlow;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates the starter when {@code telegram.auth.enabled=true}. Registers only
 * type-agnostic infrastructure; per-type beans (entities, repositories,
 * services, controllers, {@code TelegramBotModule}) are declared by the host.
 */
@AutoConfiguration(after = {HibernateJpaAutoConfiguration.class, JpaRepositoriesAutoConfiguration.class})
@ConditionalOnProperty(prefix = "telegram.auth", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TelegramAuthProperties.class)
@EnableScheduling
public class TelegramAuthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TokenGenerator telegramAuthTokenGenerator() {
        return new TokenGenerator();
    }

    @Bean
    @ConditionalOnMissingBean
    public TelegramBotLifecycle telegramBotLifecycle(ObjectProvider<TelegramBotModule> modules) {
        return new TelegramBotLifecycle(modules);
    }

    /**
     * Flow options bound from {@code telegram.auth.flow}, so an operator can tune
     * the confirmation steps without a rebuild. Inject it into the host's
     * {@code DefaultAuthFlow} bean; a host with several user types resolves the
     * named groups itself via
     * {@code properties.getFlows().get(name).toOptions(properties.getFlow())}.
     * Declaring an {@code Options} bean of your own replaces this entirely.
     */
    @Bean
    @ConditionalOnMissingBean
    public DefaultAuthFlow.Options telegramAuthFlowOptions(TelegramAuthProperties properties) {
        return properties.getFlow().toOptions();
    }
}
