package io.github.dev_abdulhay.telegramauth.payments;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * JPA-backed {@link StarOrderStore}. State transitions are single {@code UPDATE ... WHERE
 * status IN (...)} statements, so concurrent callers cannot both win.
 *
 * @param <O> the host's concrete {@link BaseStarOrder} entity
 */
public class JpaStarOrderStore<O extends BaseStarOrder> implements StarOrderStore {

    private final BaseStarOrderRepository<O> repo;
    private final Supplier<O> factory;

    /** @param factory makes a blank, unsaved entity, e.g. {@code TenantStarOrder::new} */
    public JpaStarOrderStore(BaseStarOrderRepository<O> repo, Supplier<O> factory) {
        this.repo = repo;
        this.factory = factory;
    }

    @Override
    @Transactional
    public void save(StarOrder o) {
        O entity = repo.findById(o.id()).orElseGet(factory);
        apply(entity, o);
        repo.saveAndFlush(entity);
    }

    @Override
    public Optional<StarOrder> findById(String id) {
        return repo.findById(id).map(JpaStarOrderStore::toRecord);
    }

    @Override
    public Optional<StarOrder> findByChargeId(String chargeId) {
        return repo.findByChargeId(chargeId).map(JpaStarOrderStore::toRecord);
    }

    /**
     * Runs in its own transaction: a charge id already stored on another order violates the
     * unique constraint, which must yield {@code false} without poisoning a caller's transaction.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPaid(String id, String chargeId, OffsetDateTime at) {
        try {
            return repo.markPaid(id, chargeId, at,
                    List.of(StarOrderStatus.CREATED, StarOrderStatus.EXPIRED), StarOrderStatus.PAID) == 1;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    /** Own transaction for the same reason as {@link #markPaid}. */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean insertPaidIfChargeAbsent(StarOrder paid) {
        if (repo.existsByChargeId(paid.chargeId())) {
            return false;
        }
        O entity = factory.get();
        apply(entity, paid);
        try {
            repo.saveAndFlush(entity);
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    @Override
    @Transactional
    public boolean markFulfilled(String id, OffsetDateTime at) {
        return repo.markFulfilled(id, at, List.of(StarOrderStatus.PAID)) == 1;
    }

    @Override
    @Transactional
    public boolean markRefunded(String id, OffsetDateTime at) {
        return repo.markRefunded(id, at, List.of(StarOrderStatus.PAID), StarOrderStatus.REFUNDED) == 1;
    }

    @Override
    public List<StarOrder> findPaidUnfulfilled(int limit) {
        return repo.findByStatusAndFulfilledAtIsNullOrderByPaidAtAsc(StarOrderStatus.PAID, PageRequest.of(0, limit))
                .stream().map(JpaStarOrderStore::toRecord).toList();
    }

    @Override
    @Transactional
    public int expireCreatedBefore(OffsetDateTime now) {
        return repo.expireBefore(now, List.of(StarOrderStatus.CREATED), StarOrderStatus.EXPIRED);
    }

    @Override
    @Transactional
    public int deleteExpiredBefore(OffsetDateTime cutoff) {
        return repo.deleteByStatusAndExpiresAtBefore(StarOrderStatus.EXPIRED, cutoff);
    }

    private static void apply(BaseStarOrder e, StarOrder o) {
        e.setId(o.id());
        e.setBotId(o.botId());
        e.setUserId(o.userId());
        e.setProductCode(o.productCode());
        e.setKind(o.kind());
        e.setStars(o.stars());
        e.setCredits(o.credits());
        e.setStatus(o.status());
        e.setChargeId(o.chargeId());
        e.setSubscriptionId(o.subscriptionId());
        e.setCreatedAt(o.createdAt());
        e.setExpiresAt(o.expiresAt());
        e.setPaidAt(o.paidAt());
        e.setFulfilledAt(o.fulfilledAt());
        e.setRefundedAt(o.refundedAt());
    }

    private static StarOrder toRecord(BaseStarOrder e) {
        return new StarOrder(e.getId(), e.getBotId(), e.getUserId(), e.getProductCode(), e.getKind(),
                e.getStars(), e.getCredits(), e.getStatus(), e.getChargeId(), e.getSubscriptionId(),
                e.getCreatedAt(), e.getExpiresAt(), e.getPaidAt(), e.getFulfilledAt(), e.getRefundedAt());
    }
}
