package io.github.dev_abdulhay.telegramauth.managedbots;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires the managed-bots feature when {@code telegram.managed-bots.enabled=true}.
 *
 * <p>The host supplies the {@link ManagedBotTokenStore} — only it knows whether
 * that is JPA (and with which entity) or in-memory.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "telegram.managed-bots", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TelegramManagedBotsProperties.class)
public class TelegramManagedBotsAutoConfiguration {

    /**
     * Fails the context when no key is configured rather than falling back to
     * storing tokens in the clear — a silent plaintext default is the kind of
     * thing that survives to production unnoticed.
     */
    @Bean
    @ConditionalOnMissingBean
    public TokenEncryptor managedBotTokenEncryptor(TelegramManagedBotsProperties properties) {
        String key = properties.getEncryptionKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "telegram.managed-bots.encryption-key is required when managed bots are enabled; "
                            + "set a Base64-encoded 32-byte key, or declare your own TokenEncryptor bean");
        }
        return new AesGcmTokenEncryptor(key);
    }

    @Bean
    @ConditionalOnMissingBean
    public ManagedBotEvents managedBotEvents() {
        return new ManagedBotEvents() { };
    }

    @Bean
    @ConditionalOnMissingBean
    public ManagedBotService managedBotService(TelegramBotModule module, ManagedBotTokenStore store,
                                               TokenEncryptor encryptor, ManagedBotEvents events,
                                               TelegramManagedBotsProperties properties) {
        return new ManagedBotService(module, store, encryptor, events,
                properties.getTokenFetchRetries(), properties.getTokenFetchBackoff());
    }

    @Bean
    @ConditionalOnMissingBean
    public ManagedBotUpdateHandler managedBotUpdateHandler(TelegramBotModule module, ManagedBotService service) {
        return new ManagedBotUpdateHandler(module, service);
    }
}
