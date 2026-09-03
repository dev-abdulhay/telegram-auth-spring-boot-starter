package com.example.demo;

import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = DemoApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "telegram.auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:jpalayer;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
class JpaLayerTest {

    @Autowired DemoUserRepository users;
    @Autowired DemoSessionRepository sessions;

    @Test
    void userRoundTripsAndFindsByTelegramId() {
        DemoUser u = new DemoUser();
        u.setTelegramId(42L);
        u.setFirstName("Ali");
        u.setStatus(BaseTelegramUser.Status.ACTIVE);
        users.save(u);

        assertThat(users.findByTelegramId(42L)).isPresent()
                .get().extracting(BaseTelegramUser::getFirstName).isEqualTo("Ali");
    }

    @Test
    void updatedAtRefreshesOnUpdate() {
        DemoUser u = new DemoUser();
        u.setTelegramId(77L);
        u.setStatus(BaseTelegramUser.Status.PENDING);
        u = users.saveAndFlush(u);
        OffsetDateTime before = u.getUpdatedAt();

        u.setFirstName("Vali");
        DemoUser updated = users.saveAndFlush(u);

        assertThat(updated.getUpdatedAt()).isAfter(before);
    }

    @Test
    void sessionFindsByTokenHashAndByExpiry() {
        DemoSession s = new DemoSession();
        s.setTokenHash("hash-1");
        s.setStatus(BaseAuthSession.Status.PENDING);
        s.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        sessions.save(s);

        assertThat(sessions.findByTokenHash("hash-1")).isPresent();
        List<DemoSession> overdue = sessions.findByStatusInAndExpiresAtBefore(
                List.of(BaseAuthSession.Status.PENDING, BaseAuthSession.Status.AWAITING_CODE),
                OffsetDateTime.now());
        assertThat(overdue).hasSize(1);
    }

    @Test
    @Transactional
    void bulkDeleteRemovesOnlyOldTerminalSessions() {
        sessions.save(session("purge-approved", BaseAuthSession.Status.APPROVED, OffsetDateTime.now().minusDays(2)));
        sessions.save(session("purge-rejected", BaseAuthSession.Status.REJECTED, OffsetDateTime.now().minusDays(2)));
        sessions.save(session("keep-recent", BaseAuthSession.Status.APPROVED, OffsetDateTime.now().minusMinutes(1)));
        sessions.save(session("keep-pending", BaseAuthSession.Status.PENDING, OffsetDateTime.now().minusDays(2)));

        int removed = sessions.deleteByStatusInAndExpiresAtBefore(
                List.of(BaseAuthSession.Status.APPROVED, BaseAuthSession.Status.REJECTED,
                        BaseAuthSession.Status.EXPIRED),
                OffsetDateTime.now().minusDays(1));

        assertThat(removed).isEqualTo(2);
        assertThat(sessions.findByTokenHash("purge-approved")).isEmpty();
        assertThat(sessions.findByTokenHash("purge-rejected")).isEmpty();
        // still inside the retention window, and PENDING is never a purge target
        assertThat(sessions.findByTokenHash("keep-recent")).isPresent();
        assertThat(sessions.findByTokenHash("keep-pending")).isPresent();
    }

    private static DemoSession session(String hash, BaseAuthSession.Status status, OffsetDateTime expiresAt) {
        DemoSession s = new DemoSession();
        s.setTokenHash(hash);
        s.setStatus(status);
        s.setExpiresAt(expiresAt);
        return s;
    }

    @Test
    void sessionUpdatedAtRefreshesOnUpdate() {
        DemoSession s = new DemoSession();
        s.setTokenHash("hash-2");
        s.setStatus(BaseAuthSession.Status.PENDING);
        s.setExpiresAt(OffsetDateTime.now().plusMinutes(3));
        s = sessions.saveAndFlush(s);
        OffsetDateTime before = s.getUpdatedAt();

        s.setStatus(BaseAuthSession.Status.APPROVED);
        DemoSession updated = sessions.saveAndFlush(s);

        assertThat(updated.getUpdatedAt()).isAfter(before);
    }
}
