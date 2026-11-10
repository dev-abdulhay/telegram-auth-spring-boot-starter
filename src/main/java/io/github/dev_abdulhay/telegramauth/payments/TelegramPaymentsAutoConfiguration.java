package io.github.dev_abdulhay.telegramauth.payments;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.config.TelegramAuthAutoConfiguration;
import io.github.dev_abdulhay.telegramauth.whitelabel.ManagedBotCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Wires Telegram Stars payments when {@code telegram.payments.enabled=true}.
 *
 * <p>The host supplies the {@link TelegramBotModule}; every {@link StarPaymentEvents} bean
 * is combined into one composite handed to the service, so the composite itself is not a bean.
 */
@AutoConfiguration(after = TelegramAuthAutoConfiguration.class)
@ConditionalOnProperty(prefix = "telegram.payments", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TelegramPaymentsProperties.class)
public class TelegramPaymentsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public StarCatalog starCatalog(TelegramPaymentsProperties properties) {
        properties.validate();
        return new PropertiesStarCatalog(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public StarOrderStore starOrderStore() {
        return new InMemoryStarOrderStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public StarSubscriptionStore starSubscriptionStore() {
        return new InMemoryStarSubscriptionStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public StarPaymentService starPaymentService(ObjectProvider<TelegramBotModule> module, StarCatalog catalog,
                                                 StarOrderStore orders, StarSubscriptionStore subs,
                                                 ObjectProvider<StarRefundGuard> guard,
                                                 ObjectProvider<StarPaymentEvents> events,
                                                 TelegramPaymentsProperties properties,
                                                 ObjectProvider<Clock> clock) {
        return new StarPaymentService(requireModule(module), catalog, orders, subs, guard.getIfAvailable(),
                new CompositeStarPaymentEvents(events.orderedStream().toList()), properties,
                clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public StarPaymentUpdateHandler starPaymentUpdateHandler(ObjectProvider<TelegramBotModule> module,
                                                             StarPaymentService service, StarOrderStore orders,
                                                             StarSubscriptionStore subs,
                                                             ObjectProvider<PreCheckoutValidator> validator,
                                                             TelegramPaymentsProperties properties) {
        TelegramBotModule main = requireModule(module);
        StarPaymentUpdateHandler handler =
                new StarPaymentUpdateHandler(service, orders, subs, validator.getIfAvailable());
        handler.register(main);
        if (properties.getSupport().isEnabled()) {
            PaySupportCommands.register(main, properties);
        }
        return handler;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "telegram.payments.reconcile", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public StarPaymentReconciler starPaymentReconciler(StarPaymentService service, StarPaymentUpdateHandler handler,
                                                       StarOrderStore orders, StarSubscriptionStore subs) {
        return new StarPaymentReconciler(service, handler, orders, subs);
    }

    /** Gives every white-label tenant bot the same payment hooks and support commands as the main bot. */
    @Bean
    @ConditionalOnProperty(prefix = "telegram.white-label", name = "enabled", havingValue = "true")
    public ManagedBotCustomizer starPaymentsTenantCustomizer(StarPaymentService service,
                                                             StarPaymentUpdateHandler handler,
                                                             TelegramPaymentsProperties properties) {
        return (module, bot) -> {
            service.registerBot(module);
            handler.register(module);
            if (properties.getSupport().isEnabled()) {
                PaySupportCommands.register(module, properties);
            }
        };
    }

    private static TelegramBotModule requireModule(ObjectProvider<TelegramBotModule> module) {
        TelegramBotModule m = module.getIfAvailable();
        if (m == null) {
            throw new IllegalStateException(
                    "a TelegramBotModule bean is required when telegram.payments.enabled=true; "
                            + "declare one (TelegramBotModule.builder(token, username).build())");
        }
        return m;
    }
}
