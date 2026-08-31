package com.example.demo;

import io.github.dev_abdulhay.telegramauth.config.TelegramAuthProperties;
import io.github.dev_abdulhay.telegramauth.flow.CodeConfirmation;
import io.github.dev_abdulhay.telegramauth.flow.DefaultAuthFlow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = DemoApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "telegram.auth.enabled=true",
        "telegram.auth.flow.require-approval=true",
        "telegram.auth.flow.code-confirmation=TYPED",
        "telegram.auth.flow.code-cooldown=90s",
        "spring.datasource.url=jdbc:h2:mem:flowopts;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.liquibase.enabled=false"
})
class FlowOptionsBindingTest {

    @Autowired DefaultAuthFlow.Options options;

    @Test
    void theAutoConfiguredOptionsBeanReflectsTheFlowProperties() {
        assertThat(options.requireApproval()).isTrue();
        assertThat(options.codeConfirmation()).isEqualTo(CodeConfirmation.TYPED);
        assertThat(options.codeCooldown()).isEqualTo(Duration.ofSeconds(90));
        // untouched settings keep their built-in defaults
        assertThat(options.codeButtons()).isEqualTo(3);
        assertThat(options.codeCooldownMax()).isEqualTo(Duration.ofHours(1));
        assertThat(options.requireContact()).isFalse();
    }

    @Test
    void anAbsentFieldInANamedGroupFallsBackToTheFlowGroupAndThenToTheDefaults() {
        TelegramAuthProperties.Flow base = new TelegramAuthProperties.Flow();
        base.setCodeConfirmation(CodeConfirmation.TYPED);
        base.setCodeButtons(7);

        TelegramAuthProperties.Flow admin = new TelegramAuthProperties.Flow();
        admin.setCodeButtons(9);

        DefaultAuthFlow.Options resolved = admin.toOptions(base);

        assertThat(resolved.codeButtons()).isEqualTo(9);                          // own value wins
        assertThat(resolved.codeConfirmation()).isEqualTo(CodeConfirmation.TYPED); // inherited from base
        assertThat(resolved.codeCooldown()).isEqualTo(Duration.ofMinutes(5));      // built-in default
    }

    @Test
    void aFlowGroupWithNothingSetProducesTheBuiltInDefaults() {
        assertThat(new TelegramAuthProperties.Flow().toOptions())
                .usingRecursiveComparison()
                .isEqualTo(DefaultAuthFlow.Options.defaults());
    }

    @Test
    void aBadValueIsRejectedByTheSameValidationTheBuilderUses() {
        TelegramAuthProperties.Flow bad = new TelegramAuthProperties.Flow();
        bad.setCodeButtons(11);

        assertThatThrownBy(bad::toOptions)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("codeButtons");
    }
}
