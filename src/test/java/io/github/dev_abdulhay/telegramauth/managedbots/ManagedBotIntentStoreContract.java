package io.github.dev_abdulhay.telegramauth.managedbots;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract every {@link ManagedBotIntentStore} must satisfy. Subclass per implementation. */
public abstract class ManagedBotIntentStoreContract {

    protected abstract ManagedBotIntentStore store();

    protected static ManagedBotIntent open(String id, OffsetDateTime createdAt) {
        return new ManagedBotIntent(id, "tenant_shop_bot", "Shop", "bot:1", null,
                ManagedBotIntentStatus.OPEN, null, createdAt, null, null, createdAt.plusMinutes(30));
    }

    protected static ManagedBotIntent claimed(String id, long ownerUserId) {
        OffsetDateTime now = OffsetDateTime.now();
        return open(id, now).claimedBy(ownerUserId, now);
    }

    @Test
    void savesAndFindsById() {
        store().save(open("i1", OffsetDateTime.now()));
        assertThat(store().findById("i1")).get()
                .extracting(ManagedBotIntent::hostRef).isEqualTo("bot:1");
    }

    @Test
    void findByIdIsEmptyForAnUnknownIntent() {
        assertThat(store().findById("nope")).isEmpty();
    }

    @Test
    void savingTheSameIdAgainOverwritesInsteadOfDuplicating() {
        store().save(open("i1", OffsetDateTime.now()));
        store().save(claimed("i1", 7L));

        assertThat(store().findById("i1")).get()
                .extracting(ManagedBotIntent::status).isEqualTo(ManagedBotIntentStatus.CLAIMED);
        assertThat(store().findClaimedByOwner(7L)).hasSize(1);
    }

    @Test
    void findsOnlyClaimedIntentsOfOneOwner() {
        store().save(claimed("i1", 7L));
        store().save(claimed("i2", 7L));
        store().save(claimed("i3", 8L));
        store().save(open("i4", OffsetDateTime.now()));
        store().save(claimed("i5", 7L).completedWith(555L, OffsetDateTime.now()));

        assertThat(store().findClaimedByOwner(7L))
                .extracting(ManagedBotIntent::id).containsExactlyInAnyOrder("i1", "i2");
    }

    @Test
    void findsTheIntentABotIsLinkedTo() {
        store().save(claimed("i1", 7L).completedWith(555L, OffsetDateTime.now()));
        store().save(claimed("i2", 7L));

        assertThat(store().findByBotUserId(555L)).get()
                .extracting(ManagedBotIntent::id).isEqualTo("i1");
        assertThat(store().findByBotUserId(999L)).isEmpty();
    }

    @Test
    void purgeRemovesClosedAndStaleOpenRowsButKeepsClaimedAndCompleted() {
        OffsetDateTime old = OffsetDateTime.now().minusDays(30);
        store().save(open("stale-open", old));                                   // never read, still OPEN
        store().save(open("expired", old).expired());
        store().save(open("cancelled", old).cancelled());
        store().save(claimed("claimed", 7L));                                    // fresh, and CLAIMED never expires
        store().save(open("completed", old).claimedBy(7L, old).completedWith(555L, old));

        int removed = store().deleteClosedBefore(OffsetDateTime.now().minusDays(7));

        assertThat(removed).isEqualTo(3);
        assertThat(store().findById("stale-open")).isEmpty();
        assertThat(store().findById("expired")).isEmpty();
        assertThat(store().findById("cancelled")).isEmpty();
        assertThat(store().findById("claimed")).isPresent();
        assertThat(store().findById("completed")).isPresent();
    }

    @Test
    void purgeKeepsRowsNewerThanTheCutoff() {
        store().save(open("fresh", OffsetDateTime.now()).cancelled());

        assertThat(store().deleteClosedBefore(OffsetDateTime.now().minusDays(7))).isZero();
        assertThat(store().findById("fresh")).isPresent();
    }
}
