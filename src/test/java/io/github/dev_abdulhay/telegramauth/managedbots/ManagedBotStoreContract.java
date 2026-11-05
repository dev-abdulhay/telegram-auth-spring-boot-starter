package io.github.dev_abdulhay.telegramauth.managedbots;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract every {@link ManagedBotTokenStore} must satisfy. Subclass per implementation. */
public abstract class ManagedBotStoreContract {

    protected abstract ManagedBotTokenStore store();

    protected static ManagedBot bot(long botUserId, long ownerUserId) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ManagedBot(botUserId, "tenant_" + botUserId + "_bot", "Tenant " + botUserId,
                ownerUserId, "enc-" + botUserId, now, now);
    }

    @Test
    void savesAndFindsByBotUserId() {
        store().save(bot(101L, 7L));
        assertThat(store().findByBotUserId(101L)).get()
                .extracting(ManagedBot::encryptedToken).isEqualTo("enc-101");
    }

    @Test
    void findByBotUserIdIsEmptyForAnUnknownBot() {
        assertThat(store().findByBotUserId(999L)).isEmpty();
    }

    @Test
    void savingTheSameBotAgainOverwritesInsteadOfDuplicating() {
        store().save(bot(101L, 7L));
        OffsetDateTime now = OffsetDateTime.now();
        store().save(new ManagedBot(101L, "renamed_bot", "Renamed", 7L, "enc-new", now, now));

        assertThat(store().findAll()).hasSize(1);
        assertThat(store().findByBotUserId(101L)).get()
                .extracting(ManagedBot::encryptedToken).isEqualTo("enc-new");
    }

    @Test
    void findsEveryBotOfOneOwner() {
        store().save(bot(101L, 7L));
        store().save(bot(102L, 7L));
        store().save(bot(103L, 8L));

        assertThat(store().findByOwnerUserId(7L))
                .extracting(ManagedBot::botUserId).containsExactlyInAnyOrder(101L, 102L);
    }

    @Test
    void deleteRemovesOnlyTheNamedBot() {
        store().save(bot(101L, 7L));
        store().save(bot(102L, 7L));

        store().deleteByBotUserId(101L);

        assertThat(store().findByBotUserId(101L)).isEmpty();
        assertThat(store().findByBotUserId(102L)).isPresent();
    }

    @Test
    void deletingAnUnknownBotIsNotAnError() {
        store().deleteByBotUserId(404L);
        assertThat(store().findAll()).isEmpty();
    }

    @Test
    void toStringNeverRevealsTheToken() {
        assertThat(bot(101L, 7L).toString()).doesNotContain("enc-101").contains("***");
    }
}
