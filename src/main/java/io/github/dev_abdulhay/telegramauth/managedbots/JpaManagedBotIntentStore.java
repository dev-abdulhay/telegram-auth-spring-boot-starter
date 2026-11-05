package io.github.dev_abdulhay.telegramauth.managedbots;

import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * JPA-backed {@link ManagedBotIntentStore}. {@code save} updates the existing row
 * when the id is already known, so re-saving a claimed or completed intent cannot
 * duplicate it.
 *
 * @param <I> the host's concrete {@link BaseManagedBotIntent} entity
 */
public class JpaManagedBotIntentStore<I extends BaseManagedBotIntent> implements ManagedBotIntentStore {

    private static final Collection<ManagedBotIntentStatus> KEEP =
            List.of(ManagedBotIntentStatus.CLAIMED, ManagedBotIntentStatus.COMPLETED);

    private final BaseManagedBotIntentRepository<I> repo;
    private final Supplier<I> factory;

    /**
     * @param factory makes a <b>blank, unsaved</b> entity — typically a constructor
     *                reference such as {@code TenantIntent::new}
     */
    public JpaManagedBotIntentStore(BaseManagedBotIntentRepository<I> repo, Supplier<I> factory) {
        this.repo = repo;
        this.factory = factory;
    }

    @Override
    @Transactional
    public void save(ManagedBotIntent intent) {
        I entity = repo.findById(intent.id()).orElseGet(factory);
        entity.setId(intent.id());
        entity.setSuggestedUsername(intent.suggestedUsername());
        entity.setSuggestedName(intent.suggestedName());
        entity.setHostRef(intent.hostRef());
        entity.setOwnerUserId(intent.ownerUserId());
        entity.setStatus(intent.status());
        entity.setBotUserId(intent.botUserId());
        entity.setCreatedAt(intent.createdAt());
        entity.setClaimedAt(intent.claimedAt());
        entity.setCompletedAt(intent.completedAt());
        entity.setExpiresAt(intent.expiresAt());
        repo.saveAndFlush(entity);   // flush here so a duplicate bot_user_id fails now, not at commit
    }

    @Override
    public Optional<ManagedBotIntent> findById(String id) {
        return repo.findById(id).map(JpaManagedBotIntentStore::toRecord);
    }

    @Override
    public Optional<ManagedBotIntent> findByBotUserId(long botUserId) {
        return repo.findByBotUserId(botUserId).map(JpaManagedBotIntentStore::toRecord);
    }

    @Override
    public List<ManagedBotIntent> findClaimedByOwner(long ownerUserId) {
        List<ManagedBotIntent> out = new ArrayList<>();
        repo.findByOwnerUserIdAndStatus(ownerUserId, ManagedBotIntentStatus.CLAIMED)
                .forEach(e -> out.add(toRecord(e)));
        return out;
    }

    @Override
    @Transactional
    public int deleteClosedBefore(OffsetDateTime cutoff) {
        List<I> doomed = repo.findByStatusNotInAndExpiresAtBefore(KEEP, cutoff);
        repo.deleteAll(doomed);
        return doomed.size();
    }

    private static ManagedBotIntent toRecord(BaseManagedBotIntent e) {
        return new ManagedBotIntent(e.getId(), e.getSuggestedUsername(), e.getSuggestedName(),
                e.getHostRef(), e.getOwnerUserId(), e.getStatus(), e.getBotUserId(),
                e.getCreatedAt(), e.getClaimedAt(), e.getCompletedAt(), e.getExpiresAt());
    }
}
