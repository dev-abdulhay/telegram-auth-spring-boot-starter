package com.example.demo;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.security.TokenGenerator;
import io.github.dev_abdulhay.telegramauth.service.AbstractSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the spec's prototype-bean requirement for tenant session services.
 *
 * <p>A service built with {@code new} gets no AOP proxy, so {@code @Transactional}
 * silently does nothing: {@code approve}/{@code reject}/{@code awaitCode} run outside a
 * transaction, the {@code PESSIMISTIC_WRITE} lock in {@code findWithLockByTokenHash} is
 * released as soon as its query returns instead of serialising concurrent transitions,
 * and {@code publishAfterCommit} falls through to its "no transaction active" branch.
 * All of that compiles, runs and passes a smoke test — it only corrupts data under
 * concurrency, which is why the requirement needs a test rather than javadoc.
 */
// ProtoConfig is listed explicitly in `classes`: @SpringBootTest only auto-detects a
// nested @TestConfiguration when `classes` is left empty, and DemoApp's
// @SpringBootApplication scan filters @TestConfiguration out on purpose (see DemoApp),
// so neither route would register the prototype bean.
@SpringBootTest(classes = {DemoApp.class, TenantServiceTransactionTest.ProtoConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"telegram.auth.enabled=true", "spring.liquibase.enabled=false"})
class TenantServiceTransactionTest {

    /**
     * Records whether a transaction was actually active inside a @Transactional method.
     *
     * <p>Extends {@link AbstractSessionService} directly rather than {@code DemoSessionService}:
     * a subtype of the latter would be a second injection candidate for the demo app's own
     * {@code DemoSessionService} wiring and the context would fail to start. This is the same
     * shape a host's tenant session service has, so it inherits the same {@code @Transactional
     * create}.
     */
    static class ProbeSessionService extends AbstractSessionService<DemoUser, DemoSession> {
        Boolean sawTransaction;

        ProbeSessionService(DemoSessionRepository repo, TokenGenerator tg, TelegramBotModule module) {
            super(repo, DemoSession::new, tg, module);
        }

        @Override
        public CreatedSession create(String ipAddress, String userAgent) {
            sawTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            return super.create(ipAddress, userAgent);
        }

        /**
         * Read through a method, never through the field: the container hands back a CGLIB
         * proxy whose own copy of {@code sawTransaction} is never written, so direct field
         * access on it would always read {@code null}. A call delegates to the target.
         */
        Boolean sawTransaction() {
            return sawTransaction;
        }
    }

    @TestConfiguration
    static class ProtoConfig {
        @Bean
        @Scope("prototype")
        ProbeSessionService probeSessionService(DemoSessionRepository repo, TokenGenerator tg) {
            return new ProbeSessionService(repo, tg,
                    TelegramBotModule.builder("123:ABC", "tenant_bot").botUserId(555L).build());
        }
    }

    @Autowired
    private ObjectProvider<ProbeSessionService> provider;

    @Autowired
    private DemoSessionRepository repo;

    @Autowired
    private TokenGenerator tokenGenerator;

    @Test
    void aPrototypeScopedTenantServiceIsTransactional() {
        ProbeSessionService service = provider.getObject();

        service.create("1.2.3.4", "ua");

        assertThat(service.sawTransaction())
                .as("a prototype-scoped bean keeps its AOP proxy, so @Transactional applies")
                .isTrue();
    }

    @Test
    void aServiceBuiltWithNewIsNotTransactional() {
        ProbeSessionService service = new ProbeSessionService(repo, tokenGenerator,
                TelegramBotModule.builder("123:ABC", "tenant_bot").botUserId(556L).build());

        service.create("1.2.3.4", "ua");

        // this is exactly why TenantBotFactory must not use `new`: the annotation
        // is still on the method, it just does nothing
        assertThat(service.sawTransaction())
                .as("a hand-built service has no proxy, so @Transactional silently does nothing")
                .isFalse();
    }
}
