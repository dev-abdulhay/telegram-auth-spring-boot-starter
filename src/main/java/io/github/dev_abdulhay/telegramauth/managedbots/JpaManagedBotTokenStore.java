package io.github.dev_abdulhay.telegramauth.managedbots;

import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * JPA-backed {@link ManagedBotTokenStore}. {@code save} updates the existing row
 * when the bot is already known, so a re-delivered update cannot duplicate it.
 *
 * @param <M> the host's concrete {@link BaseManagedBot} entity
 */
public class JpaManagedBotTokenStore<M extends BaseManagedBot> implements ManagedBotTokenStore {

    private final BaseManagedBotRepository<M> repo;
    private final Supplier<M> factory;

    /**
     * @param factory makes a <b>blank, unsaved</b> entity — typically a constructor
     *                reference such as {@code TenantBot::new}. {@code save} uses
     *                {@code getBotUserId() == null} as its is-new test, so a factory
     *                that pre-fills {@code botUserId} makes every insert look like an
     *                update and silently drops {@code createdAt}.
     */
    public JpaManagedBotTokenStore(BaseManagedBotRepository<M> repo, Supplier<M> factory) {
        this.repo = repo;
        this.factory = factory;
    }

    @Override
    @Transactional
    public void save(ManagedBot bot) {
        M entity = repo.findByBotUserId(bot.botUserId()).orElseGet(factory);
        if (entity.getBotUserId() == null) {
            entity.setCreatedAt(bot.createdAt());
        }
        entity.setBotUserId(bot.botUserId());
        entity.setUsername(bot.username());
        entity.setFirstName(bot.firstName());
        entity.setOwnerUserId(bot.ownerUserId());
        entity.setEncryptedToken(bot.encryptedToken());
        entity.setUpdatedAt(bot.updatedAt());
        repo.save(entity);
    }

    @Override
    public Optional<ManagedBot> findByBotUserId(long botUserId) {
        return repo.findByBotUserId(botUserId).map(JpaManagedBotTokenStore::toRecord);
    }

    @Override
    public List<ManagedBot> findByOwnerUserId(long ownerUserId) {
        List<ManagedBot> out = new ArrayList<>();
        repo.findByOwnerUserId(ownerUserId).forEach(e -> out.add(toRecord(e)));
        return out;
    }

    @Override
    public List<ManagedBot> findAll() {
        List<ManagedBot> out = new ArrayList<>();
        repo.findAll().forEach(e -> out.add(toRecord(e)));
        return out;
    }

    @Override
    @Transactional
    public void deleteByBotUserId(long botUserId) {
        repo.deleteByBotUserId(botUserId);
    }

    private static ManagedBot toRecord(BaseManagedBot e) {
        return new ManagedBot(e.getBotUserId(), e.getUsername(), e.getFirstName(),
                e.getOwnerUserId(), e.getEncryptedToken(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
