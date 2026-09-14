package io.github.dev_abdulhay.telegramauth.managedbots;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Map-backed store for tests and hosts that do not use JPA. Not durable. */
public class InMemoryManagedBotIntentStore implements ManagedBotIntentStore {

    private final ConcurrentHashMap<String, ManagedBotIntent> byId = new ConcurrentHashMap<>();

    @Override
    public void save(ManagedBotIntent intent) {
        byId.put(intent.id(), intent);
    }

    @Override
    public Optional<ManagedBotIntent> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<ManagedBotIntent> findByBotUserId(long botUserId) {
        return byId.values().stream()
                .filter(i -> i.botUserId() != null && i.botUserId() == botUserId)
                .findFirst();
    }

    @Override
    public List<ManagedBotIntent> findClaimedByOwner(long ownerUserId) {
        return byId.values().stream()
                .filter(i -> i.status() == ManagedBotIntentStatus.CLAIMED)
                .filter(i -> i.ownerUserId() != null && i.ownerUserId() == ownerUserId)
                .toList();
    }

    @Override
    public int deleteClosedBefore(OffsetDateTime cutoff) {
        List<String> doomed = byId.values().stream()
                .filter(i -> i.status() != ManagedBotIntentStatus.CLAIMED
                        && i.status() != ManagedBotIntentStatus.COMPLETED)
                .filter(i -> i.expiresAt().isBefore(cutoff))
                .map(ManagedBotIntent::id)
                .toList();
        doomed.forEach(byId::remove);
        return doomed.size();
    }
}
