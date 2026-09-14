package io.github.dev_abdulhay.telegramauth.managedbots;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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

    /**
     * The intent store is optional and host-supplied: an {@link ObjectProvider} keeps
     * the whole feature opt-in without a second auto-configuration class.
     */
    @Bean
    @ConditionalOnMissingBean
    public ManagedBotService managedBotService(TelegramBotModule module, ManagedBotTokenStore store,
                                               TokenEncryptor encryptor, ManagedBotEvents events,
                                               TelegramManagedBotsProperties properties,
                                               ObjectProvider<ManagedBotIntentStore> intentStore) {
        return new ManagedBotService(module, store, encryptor, events,
                properties.getTokenFetchRetries(), properties.getTokenFetchBackoff(),
                intentStore.getIfAvailable(), properties.getIntentTtl(), properties.getIntentRetention());
    }

    @Bean
    @ConditionalOnMissingBean
    public ManagedBotUpdateHandler managedBotUpdateHandler(TelegramBotModule module, ManagedBotService service) {
        return new ManagedBotUpdateHandler(module, service);
    }

    /** Claims the {@code mb_} start-payload route. Absent when the host configures no intent store. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ManagedBotIntentStore.class)
    public ManagedBotIntentFlow managedBotIntentFlow(TelegramBotModule module, ManagedBotService service) {
        return new ManagedBotIntentFlow(module, service);
    }
}
