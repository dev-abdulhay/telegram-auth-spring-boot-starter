package com.example.demo;

import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = DemoApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "telegram.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:usersvc;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
class UserServiceTest {

    @Autowired DemoUserService service;

    @Test
    void registerCreatesActiveUserThenUpsertsSameRow() {
        DemoUser first = service.register(7L, "+998", "Ali", null, "ali", "uz");
        assertThat(first.getId()).isNotNull();
        assertThat(first.getStatus()).isEqualTo(BaseTelegramUser.Status.ACTIVE);

        DemoUser second = service.register(7L, "+998", "Ali Updated", null, "ali", "uz");
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getFirstName()).isEqualTo("Ali Updated");
        assertThat(service.findByTelegramId(7L)).isPresent();
    }
}
