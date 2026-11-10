package io.github.dev_abdulhay.telegramauth.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Map-backed store for tests and hosts that do not use JPA. Not durable. */
public class InMemoryStarSubscriptionStore implements StarSubscriptionStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryStarSubscriptionStore.class);

    private final ConcurrentHashMap<String, StarSubscription> byId = new ConcurrentHashMap<>();

    public InMemoryStarSubscriptionStore() {
        log.warn("StarSubscriptionStore is in-memory — not for production");
    }

    @Override
    public synchronized void save(StarSubscription s) {
        byId.put(s.id(), s);
    }

    @Override
    public boolean insertIfAbsent(StarSubscription s) {
        return byId.putIfAbsent(s.id(), s) == null;
    }

    @Override
    public Optional<StarSubscription> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<StarSubscription> findByUser(long userId) {
        return byId.values().stream().filter(s -> s.userId() == userId).toList();
    }

    @Override
    public List<StarSubscription> findLiveExpiringBefore(OffsetDateTime cutoff) {
        return byId.values().stream()
                .filter(s -> s.status() != StarSubscriptionStatus.EXPIRED)
                .filter(s -> s.expiresAt().isBefore(cutoff))
                .toList();
    }

    @Override
    public synchronized boolean extend(String id, String chargeId, OffsetDateTime newExpiry) {
        StarSubscription s = byId.get(id);
        if (s == null || !s.expiresAt().isBefore(newExpiry)) {
            return false;
        }
        StarSubscription renewed = s.renewed(chargeId, newExpiry);
        byId.put(id, s.status() == StarSubscriptionStatus.CANCELED
                ? renewed.withStatus(StarSubscriptionStatus.CANCELED) : renewed);
        return true;
    }

    @Override
    public synchronized boolean expireIfBefore(String id, OffsetDateTime cutoff) {
        StarSubscription s = byId.get(id);
        if (s == null || s.status() == StarSubscriptionStatus.EXPIRED || !s.expiresAt().isBefore(cutoff)) {
            return false;
        }
        byId.put(id, s.withStatus(StarSubscriptionStatus.EXPIRED));
        return true;
    }

    @Override
    public synchronized boolean updateLiveStatus(String id, StarSubscriptionStatus status) {
        StarSubscription s = byId.get(id);
        if (s == null || s.status() == StarSubscriptionStatus.EXPIRED) {
            return false;
        }
        byId.put(id, s.withStatus(status));
        return true;
    }
}
