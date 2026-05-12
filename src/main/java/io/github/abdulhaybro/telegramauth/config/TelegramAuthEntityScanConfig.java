package io.github.abdulhaybro.telegramauth.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;

/**
 * Brings the starter's JPA entities into the host application's persistence
 * unit without forcing the host to widen its own {@code @EntityScan}.
 */
@Configuration(proxyBeanMethods = false)
@EntityScan(basePackages = "io.github.abdulhaybro.telegramauth.entity")
public class TelegramAuthEntityScanConfig {
}
