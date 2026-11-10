package io.github.dev_abdulhay.telegramauth.payments;

import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * JPA-backed {@link StarSubscriptionStore}. {@code save} updates the existing row when the
 * id is already known.
 *
 * @param <S> the host's concrete {@link BaseStarSubscription} entity
 */
public class JpaStarSubscriptionStore<S extends BaseStarSubscription> implements StarSubscriptionStore {

    private static final List<StarSubscriptionStatus> LIVE =
            List.of(StarSubscriptionStatus.ACTIVE, StarSubscriptionStatus.CANCELED);

    private final BaseStarSubscriptionRepository<S> repo;
    private final Supplier<S> factory;

    /** @param factory makes a blank, unsaved entity, e.g. {@code TenantStarSubscription::new} */
    public JpaStarSubscriptionStore(BaseStarSubscriptionRepository<S> repo, Supplier<S> factory) {
        this.repo = repo;
        this.factory = factory;
    }

    @Override
    @Transactional
    public void save(StarSubscription s) {
        S e = repo.findById(s.id()).orElseGet(factory);
        e.setId(s.id());
        e.setBotId(s.botId());
        e.setUserId(s.userId());
        e.setProductCode(s.productCode());
        e.setFirstChargeId(s.firstChargeId());
        e.setLastChargeId(s.lastChargeId());
        e.setStatus(s.status());
        e.setExpiresAt(s.expiresAt());
        e.setCreatedAt(s.createdAt());
        repo.saveAndFlush(e);
    }

    @Override
    public Optional<StarSubscription> findById(String id) {
        return repo.findById(id).map(JpaStarSubscriptionStore::toRecord);
    }

    @Override
    public List<StarSubscription> findByUser(long userId) {
        return repo.findByUserId(userId).stream().map(JpaStarSubscriptionStore::toRecord).toList();
    }

    @Override
    public List<StarSubscription> findLiveExpiringBefore(OffsetDateTime cutoff) {
        return repo.findByStatusInAndExpiresAtBefore(LIVE, cutoff).stream()
                .map(JpaStarSubscriptionStore::toRecord).toList();
    }

    private static StarSubscription toRecord(BaseStarSubscription e) {
        return new StarSubscription(e.getId(), e.getBotId(), e.getUserId(), e.getProductCode(),
                e.getFirstChargeId(), e.getLastChargeId(), e.getStatus(), e.getExpiresAt(), e.getCreatedAt());
    }
}
