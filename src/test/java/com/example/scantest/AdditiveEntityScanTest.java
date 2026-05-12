package com.example.scantest;

import io.github.dev_abdulhay.telegramauth.api.TelegramAuthApproveHandler;
import io.github.dev_abdulhay.telegramauth.api.dto.AuthApproveResult;
import io.github.dev_abdulhay.telegramauth.entity.MTelegramAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.MTelegramUser;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Regression guard for the {@code @EntityScan} → {@code @AutoConfigurationPackage}
 * fix: a host application that lives in {@code com.example.scantest} must end up
 * with <em>both</em> its own {@link Foo} entity <em>and</em> the starter's
 * {@link MTelegramUser} / {@link MTelegramAuthSession} in the Hibernate
 * metamodel. The previous {@code @EntityScan} implementation would have hidden
 * {@code Foo} (it replaces the default scan rather than extending it).
 */
@SpringBootTest(
        classes = AdditiveEntityScanTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "telegram.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:scantest;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.liquibase.enabled=false"
})
class AdditiveEntityScanTest {

    @Autowired
    EntityManagerFactory emf;

    @Test
    void hostEntityAndStarterEntitiesAreAllManaged() {
        assertThatNoException().isThrownBy(() -> emf.getMetamodel().entity(MTelegramUser.class));
        assertThatNoException().isThrownBy(() -> emf.getMetamodel().entity(MTelegramAuthSession.class));
        assertThatNoException().isThrownBy(() -> emf.getMetamodel().entity(Foo.class));

        assertThat(emf.getMetamodel().getEntities())
                .extracting(e -> e.getJavaType().getName())
                .contains(
                        Foo.class.getName(),
                        MTelegramUser.class.getName(),
                        MTelegramAuthSession.class.getName()
                );
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        TelegramAuthApproveHandler approveHandler() {
            return (user, ctx) -> new AuthApproveResult(Map.of());
        }
    }
}
