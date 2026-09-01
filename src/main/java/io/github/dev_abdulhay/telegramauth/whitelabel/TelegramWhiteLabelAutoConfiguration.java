package io.github.dev_abdulhay.telegramauth.whitelabel;

import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotService;
import io.github.dev_abdulhay.telegramauth.managedbots.ManagedBotTokenStore;
import io.github.dev_abdulhay.telegramauth.managedbots.TelegramManagedBotsAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

import java.util.concurrent.ThreadFactory;

/**
 * Wires the white-label runtime when {@code telegram.white-label.enabled=true}.
 *
 * <p>Ordered before the managed-bots auto-configuration on purpose: that one
 * registers a no-op {@code ManagedBotEvents} under {@code @ConditionalOnMissingBean},
 * and the event bridge has to win. When this runtime is on, the library owns the
 * events bean; a host adding its own per-bot wiring uses {@link ManagedBotCustomizer}.
 *
 * <p><b>{@code @Lazy} on {@code managedBots} below is load-bearing, not decorative.</b>
 * {@code ManagedBotService} takes {@code ManagedBotEvents} as a constructor argument,
 * and once this runtime is on, that bean is {@link TenantBotEventBridge}, which in
 * turn takes this {@code TenantBotRegistry} as a constructor argument — a genuine
 * three-way cycle: {@code ManagedBotService -> ManagedBotEvents(bridge) ->
 * TenantBotRegistry -> ManagedBotService}. Constructor injection cannot resolve that
 * eagerly ("Requested bean is currently in creation"). {@code @Lazy} swaps this one
 * edge for a lazily-resolving proxy, which is safe here: {@code managedBots} is only
 * ever called from inside {@code start()}, long after the context has finished
 * refreshing, never during construction.
 */
@AutoConfiguration(before = TelegramManagedBotsAutoConfiguration.class)
@ConditionalOnProperty(prefix = "telegram.white-label", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TelegramWhiteLabelProperties.class)
public class TelegramWhiteLabelAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public <U extends BaseTelegramUser, S extends BaseAuthSession> TenantBotRegistry<U, S>
    tenantBotRegistry(@Lazy ManagedBotService managedBots,
                      ObjectProvider<TenantBotFactory<U, S>> factory,
                      ObjectProvider<ManagedBotCustomizer> customizer,
                      ObjectProvider<ThreadFactory> threadFactory,
                      TelegramWhiteLabelProperties properties) {
        TenantBotFactory<U, S> f = factory.getIfAvailable();
        if (f == null) {
            throw new IllegalStateException(
                    "a TenantBotFactory bean is required when telegram.white-label.enabled=true; "
                            + "only the host can build a session service for its own entity types");
        }
        return new TenantBotRegistry<>(managedBots, f, customizer.getIfAvailable(),
                threadFactory.getIfAvailable(), properties.getPollFailureBudget());
    }

    @Bean
    @ConditionalOnMissingBean
    public <U extends BaseTelegramUser, S extends BaseAuthSession> TenantBotEventBridge<U, S>
    tenantBotEventBridge(TenantBotRegistry<U, S> registry) {
        return new TenantBotEventBridge<>(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public <U extends BaseTelegramUser, S extends BaseAuthSession> TenantBotLifecycle<U, S>
    tenantBotLifecycle(ObjectProvider<ManagedBotTokenStore> store, TenantBotRegistry<U, S> registry,
                       TelegramWhiteLabelProperties properties) {
        // Taken through a provider purely so the failure is legible. Injected
        // directly, the commonest misconfiguration — white-label on, managed bots
        // off — surfaces as a raw NoSuchBeanDefinitionException for a type the host
        // never asked for, naming neither switch. Same shape as the factory check.
        ManagedBotTokenStore s = store.getIfAvailable();
        if (s == null) {
            throw new IllegalStateException(
                    "a ManagedBotTokenStore bean is required when telegram.white-label.enabled=true; "
                            + "the white-label runtime is built on the managed-bots feature, so set "
                            + "telegram.managed-bots.enabled=true and declare a store bean "
                            + "(InMemoryManagedBotStore, or JpaManagedBotTokenStore over your own "
                            + "BaseManagedBot entity)");
        }
        return new TenantBotLifecycle<>(s, registry, properties.isRestoreOnStartup());
    }
}
