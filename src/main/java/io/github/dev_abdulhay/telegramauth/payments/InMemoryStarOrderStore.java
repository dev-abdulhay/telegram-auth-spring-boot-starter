package io.github.dev_abdulhay.telegramauth.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Map-backed store for tests and hosts that do not use JPA. Not durable. */
public class InMemoryStarOrderStore implements StarOrderStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryStarOrderStore.class);

    private final ConcurrentHashMap<String, StarOrder> byId = new ConcurrentHashMap<>();

    public InMemoryStarOrderStore() {
        log.warn("StarOrderStore is in-memory — not for production");
    }

    @Override
    public synchronized void save(StarOrder o) {
        byId.put(o.id(), o);
    }

    @Override
    public Optional<StarOrder> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<StarOrder> findByChargeId(String chargeId) {
        return byId.values().stream().filter(o -> chargeId.equals(o.chargeId())).findFirst();
    }

    @Override
    public synchronized boolean markPaid(String id, String chargeId, OffsetDateTime at) {
        StarOrder o = byId.get(id);
        if (o == null || (o.status() != StarOrderStatus.CREATED && o.status() != StarOrderStatus.EXPIRED)) {
            return false;
        }
        if (findByChargeId(chargeId).filter(other -> !other.id().equals(id)).isPresent()) {
            return false;   // mirrors the UNIQUE charge_id constraint
        }
        byId.put(id, copy(o, StarOrderStatus.PAID, chargeId, at, o.fulfilledAt(), o.refundedAt()));
        return true;
    }

    @Override
    public synchronized boolean insertPaidIfChargeAbsent(StarOrder paid) {
        if (findByChargeId(paid.chargeId()).isPresent()) {
            return false;
        }
        byId.put(paid.id(), paid);
        return true;
    }

    @Override
    public synchronized boolean markFulfilled(String id, OffsetDateTime at) {
        StarOrder o = byId.get(id);
        if (o == null || o.status() != StarOrderStatus.PAID || o.fulfilledAt() != null) {
            return false;
        }
        byId.put(id, copy(o, o.status(), o.chargeId(), o.paidAt(), at, o.refundedAt()));
        return true;
    }

    @Override
    public synchronized boolean markRefunded(String id, OffsetDateTime at) {
        StarOrder o = byId.get(id);
        if (o == null || o.status() != StarOrderStatus.PAID) {
            return false;
        }
        byId.put(id, copy(o, StarOrderStatus.REFUNDED, o.chargeId(), o.paidAt(), o.fulfilledAt(), at));
        return true;
    }

    @Override
    public List<StarOrder> findPaidUnfulfilled(int limit) {
        return byId.values().stream()
                .filter(o -> o.status() == StarOrderStatus.PAID && o.fulfilledAt() == null)
                .sorted(Comparator.comparing(StarOrder::paidAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized int expireCreatedBefore(OffsetDateTime now) {
        List<StarOrder> due = byId.values().stream()
                .filter(o -> o.status() == StarOrderStatus.CREATED && o.expiresAt().isBefore(now))
                .toList();
        due.forEach(o -> byId.put(o.id(),
                copy(o, StarOrderStatus.EXPIRED, o.chargeId(), o.paidAt(), o.fulfilledAt(), o.refundedAt())));
        return due.size();
    }

    @Override
    public synchronized int deleteExpiredBefore(OffsetDateTime cutoff) {
        List<String> doomed = byId.values().stream()
                .filter(o -> o.status() == StarOrderStatus.EXPIRED && o.expiresAt().isBefore(cutoff))
                .map(StarOrder::id)
                .toList();
        doomed.forEach(byId::remove);
        return doomed.size();
    }

    @Override
    public synchronized boolean linkSubscription(String id, String subscriptionId) {
        StarOrder o = byId.get(id);
        if (o == null || o.subscriptionId() != null) {
            return false;
        }
        byId.put(id, o.withSubscriptionId(subscriptionId));
        return true;
    }

    private static StarOrder copy(StarOrder o, StarOrderStatus status, String chargeId, OffsetDateTime paidAt,
                                  OffsetDateTime fulfilledAt, OffsetDateTime refundedAt) {
        return new StarOrder(o.id(), o.botId(), o.userId(), o.productCode(), o.kind(), o.stars(), o.credits(),
                status, chargeId, o.subscriptionId(), o.createdAt(), o.expiresAt(), paidAt, fulfilledAt,
                refundedAt);
    }
}
