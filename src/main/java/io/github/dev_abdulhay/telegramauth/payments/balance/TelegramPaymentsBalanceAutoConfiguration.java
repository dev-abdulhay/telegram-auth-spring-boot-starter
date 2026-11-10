package io.github.dev_abdulhay.telegramauth.payments.balance;

import io.github.dev_abdulhay.telegramauth.payments.TelegramPaymentsAutoConfiguration;
import io.github.dev_abdulhay.telegramauth.payments.TelegramPaymentsProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Adds the internal credit balance when {@code telegram.payments.enabled=true} and
 * {@code telegram.payments.balance.enabled=true}. The {@link TopUpCreditor} is picked up by the
 * payments service as both a {@code StarPaymentEvents} listener and the refund guard; it depends
 * only on the ledger and properties so it cannot form a cycle with the payments beans.
 */
@AutoConfiguration(before = TelegramPaymentsAutoConfiguration.class)
@ConditionalOnProperty(prefix = "telegram.payments", name = {"enabled", "balance.enabled"}, havingValue = "true")
@EnableConfigurationProperties(TelegramPaymentsProperties.class)
public class TelegramPaymentsBalanceAutoConfiguration {

    /** In-memory default; production hosts declare a {@link JpaBalanceLedger} bean. */
    @Bean
    @ConditionalOnMissingBean
    public BalanceLedger balanceLedger() {
        return new InMemoryBalanceLedger();
    }

    @Bean
    @ConditionalOnMissingBean
    public TopUpCreditor topUpCreditor(BalanceLedger ledger, TelegramPaymentsProperties properties) {
        TelegramPaymentsProperties.Balance b = properties.getBalance();
        return new TopUpCreditor(ledger, b.getScope(), b.getRefundPolicy());
    }
}
