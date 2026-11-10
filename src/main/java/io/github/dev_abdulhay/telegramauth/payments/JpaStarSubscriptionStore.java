package io.github.dev_abdulhay.telegramauth.payments;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * JPA-backed {@link StarSubscriptionStore}. {@code save} updates the existing row when the
 * id is already known. {@link #insertIfAbsent} is a real INSERT in its own
 * ({@code REQUIRES_NEW}) transaction: the entity is
 * {@link org.springframework.data.domain.Persistable}, so a row committed concurrently makes the
 * insert fail on the primary key instead of being merged over.
 *
 * @param <S> the host's concrete {@link BaseStarSubscription} entity
 */
public class JpaStarSubscriptionStore<S extends BaseStarSubscription> implements StarSubscriptionStore {

    private static final List<StarSubscriptionStatus> LIVE =
            List.of(StarSubscriptionStatus.ACTIVE, StarSubscriptionStatus.CANCELED);

    private final BaseStarSubscriptionRepository<S> repo;
    private final Supplier<S> factory;
    private final TransactionTemplate isolated;

    /** @param factory makes a blank, unsaved entity, e.g. {@code TenantStarSubscription::new} */
    public JpaStarSubscriptionStore(BaseStarSubscriptionRepository<S> repo, Supplier<S> factory,
                                    PlatformTransactionManager txManager) {
        this.repo = repo;
        this.factory = factory;
        this.isolated = new TransactionTemplate(txManager);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional
    public void save(StarSubscription s) {
        S e = repo.findById(s.id()).orElseGet(factory);
        copy(s, e);
        repo.saveAndFlush(e);
    }

    @Override
    public boolean insertIfAbsent(StarSubscription s) {
        if (repo.existsById(s.id())) {
            return false;
        }
        S e = factory.get();
        copy(s, e);
        try {
            isolated.executeWithoutResult(status -> repo.saveAndFlush(e));
            return true;
        } catch (DataIntegrityViolationException ex) {
            // caught outside the transaction (inside would leave it rollback-only)
            if (repo.existsById(s.id())) {
                return false;
            }
            throw ex;
        }
    }

    private static void copy(StarSubscription s, BaseStarSubscription e) {
        e.setId(s.id());
        e.setBotId(s.botId());
        e.setUserId(s.userId());
        e.setProductCode(s.productCode());
        e.setFirstChargeId(s.firstChargeId());
        e.setLastChargeId(s.lastChargeId());
        e.setStatus(s.status());
        e.setExpiresAt(s.expiresAt());
        e.setCreatedAt(s.createdAt());
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

    @Override
    public boolean extend(String id, String chargeId, OffsetDateTime newExpiry) {
        return repo.extend(id, chargeId, newExpiry, StarSubscriptionStatus.EXPIRED,
                StarSubscriptionStatus.ACTIVE) == 1;
    }

    @Override
    public boolean expireIfBefore(String id, OffsetDateTime cutoff) {
        return repo.expireIfBefore(id, cutoff, LIVE, StarSubscriptionStatus.EXPIRED) == 1;
    }

    @Override
    public boolean updateLiveStatus(String id, StarSubscriptionStatus status) {
        return repo.updateLiveStatus(id, LIVE, status) == 1;
    }

    private static StarSubscription toRecord(BaseStarSubscription e) {
        return new StarSubscription(e.getId(), e.getBotId(), e.getUserId(), e.getProductCode(),
                e.getFirstChargeId(), e.getLastChargeId(), e.getStatus(), e.getExpiresAt(), e.getCreatedAt());
    }
}
