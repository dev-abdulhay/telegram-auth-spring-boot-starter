package io.github.dev_abdulhay.telegramauth.managedbots;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Map-backed store for tests and hosts that do not use JPA. Not durable. */
public class InMemoryManagedBotStore implements ManagedBotTokenStore {

    private final ConcurrentHashMap<Long, ManagedBot> byBotUserId = new ConcurrentHashMap<>();

    @Override
    public void save(ManagedBot bot) {
        byBotUserId.put(bot.botUserId(), bot);
    }

    @Override
    public Optional<ManagedBot> findByBotUserId(long botUserId) {
        return Optional.ofNullable(byBotUserId.get(botUserId));
    }

    @Override
    public List<ManagedBot> findByOwnerUserId(long ownerUserId) {
        List<ManagedBot> out = new ArrayList<>();
        byBotUserId.values().forEach(b -> {
            if (b.ownerUserId() == ownerUserId) out.add(b);
        });
        return out;
    }

    @Override
    public List<ManagedBot> findAll() {
        return new ArrayList<>(byBotUserId.values());
    }

    @Override
    public void deleteByBotUserId(long botUserId) {
        byBotUserId.remove(botUserId);
    }
}
