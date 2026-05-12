package io.github.dev_abdulhay.telegramauth.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;

/**
 * Appends the starter's entity package to Spring Boot's auto-configuration
 * package list. Unlike {@code @EntityScan} — which <em>replaces</em> the
 * default package set and silently hides the host application's own
 * {@code @Entity} classes — this registration is <em>additive</em>: the host's
 * {@code @SpringBootApplication} package is still picked up by Spring Boot's
 * default detection, and this just appends one more package on top.
 *
 * <p>This is registered as its own {@code @AutoConfiguration} and ordered
 * <em>before</em> {@link HibernateJpaAutoConfiguration} and
 * {@link JpaRepositoriesAutoConfiguration}. Ordering matters:
 * {@link JpaRepositoriesAutoConfiguration}'s registrar eagerly resolves
 * {@code AutoConfigurationPackages.get(...)} during configuration-class
 * processing, which caches the {@code BasePackages} singleton. If our entry
 * is appended after that point the cached singleton is stale and the host's
 * EMF never sees our entities.
 */
@AutoConfiguration(before = {HibernateJpaAutoConfiguration.class, JpaRepositoriesAutoConfiguration.class})
@ConditionalOnProperty(prefix = "telegram.auth", name = "enabled", havingValue = "true")
@AutoConfigurationPackage(basePackages = "io.github.dev_abdulhay.telegramauth.entity")
public class TelegramAuthEntityScanConfig {
}
