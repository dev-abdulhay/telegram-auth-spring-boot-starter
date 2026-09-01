package com.example.demo;

import io.github.dev_abdulhay.telegramauth.entity.BaseAuthSession;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** Hand-written in-memory repo for unit tests. Only implements what the service uses. */
@SuppressWarnings({"unchecked", "NullableProblems"})
public class StubSessionRepo implements DemoSessionRepository {

    private final Map<Long, DemoSession> store = new HashMap<>();
    private final AtomicLong seq = new AtomicLong(0);

    @Override public Optional<DemoSession> findByTokenHash(String tokenHash) {
        return store.values().stream().filter(s -> tokenHash.equals(s.getTokenHash())).findFirst();
    }
    @Override public Optional<DemoSession> findByTokenHashAndBotUserId(String tokenHash, Long botUserId) {
        return store.values().stream()
                .filter(s -> tokenHash.equals(s.getTokenHash()) && botUserId.equals(s.getBotUserId()))
                .findFirst();
    }
    @Override public Optional<DemoSession> findWithLockByTokenHash(String tokenHash) {
        return findByTokenHash(tokenHash);
    }
    @Override public Optional<DemoSession> findWithLockByTokenHashAndBotUserId(String tokenHash, Long botUserId) {
        return findByTokenHashAndBotUserId(tokenHash, botUserId);
    }
    @Override public List<DemoSession> findByStatusInAndExpiresAtBefore(
            java.util.Collection<BaseAuthSession.Status> statuses, OffsetDateTime time) {
        return store.values().stream()
                .filter(s -> statuses.contains(s.getStatus())
                        && s.getExpiresAt() != null && s.getExpiresAt().isBefore(time))
                .toList();
    }
    @Override public long countByIpAddressAndStatusInAndExpiresAtAfter(
            String ipAddress, java.util.Collection<BaseAuthSession.Status> statuses, OffsetDateTime time) {
        return store.values().stream()
                .filter(s -> ipAddress.equals(s.getIpAddress()) && statuses.contains(s.getStatus())
                        && s.getExpiresAt() != null && s.getExpiresAt().isAfter(time))
                .count();
    }
    @Override public long countByIpAddressAndBotUserIdAndStatusInAndExpiresAtAfter(
            String ipAddress, Long botUserId, java.util.Collection<BaseAuthSession.Status> statuses, OffsetDateTime time) {
        return store.values().stream()
                .filter(s -> ipAddress.equals(s.getIpAddress()) && botUserId.equals(s.getBotUserId())
                        && statuses.contains(s.getStatus())
                        && s.getExpiresAt() != null && s.getExpiresAt().isAfter(time))
                .count();
    }
    @Override public int deleteByStatusInAndExpiresAtBefore(java.util.Collection<BaseAuthSession.Status> statuses, OffsetDateTime time) {
        List<Long> ids = store.values().stream()
                .filter(s -> statuses.contains(s.getStatus()) && s.getExpiresAt() != null && s.getExpiresAt().isBefore(time))
                .map(DemoSession::getId)
                .toList();
        ids.forEach(store::remove);
        return ids.size();
    }
    @Override public <Sx extends DemoSession> Sx save(Sx entity) {
        if (entity.getId() == null) entity.setId(seq.incrementAndGet());
        store.put(entity.getId(), entity);
        return entity;
    }
    @Override public Optional<DemoSession> findById(Long id) { return Optional.ofNullable(store.get(id)); }
    @Override public List<DemoSession> findAll() { return new ArrayList<>(store.values()); }
    @Override public <Sx extends DemoSession> List<Sx> saveAll(Iterable<Sx> entities) {
        List<Sx> out = new ArrayList<>(); entities.forEach(e -> out.add(save(e))); return out;
    }

    // ---- no-op stubs for the rest of JpaRepository / ListCrudRepository surface ----
    @Override public void flush() {}
    @Override public <Sx extends DemoSession> Sx saveAndFlush(Sx entity) { return save(entity); }
    @Override public <Sx extends DemoSession> List<Sx> saveAllAndFlush(Iterable<Sx> entities) { return saveAll(entities); }
    @Override public void deleteAllInBatch(Iterable<DemoSession> entities) {}
    @Override public void deleteAllByIdInBatch(Iterable<Long> ids) {}
    @Override public void deleteAllInBatch() {}
    @Override public DemoSession getOne(Long id) { return store.get(id); }
    @Override public DemoSession getById(Long id) { return store.get(id); }
    @Override public DemoSession getReferenceById(Long id) { return store.get(id); }
    @Override public <Sx extends DemoSession> List<Sx> findAll(Example<Sx> ex) { return List.of(); }
    @Override public <Sx extends DemoSession> List<Sx> findAll(Example<Sx> ex, Sort sort) { return List.of(); }
    @Override public List<DemoSession> findAllById(Iterable<Long> ids) { return List.of(); }
    @Override public List<DemoSession> findAll(Sort sort) { return List.of(); }
    @Override public Page<DemoSession> findAll(Pageable pageable) { return Page.empty(); }
    @Override public boolean existsById(Long id) { return store.containsKey(id); }
    @Override public long count() { return store.size(); }
    @Override public void deleteById(Long id) { store.remove(id); }
    @Override public void delete(DemoSession entity) { if (entity.getId() != null) store.remove(entity.getId()); }
    @Override public void deleteAllById(Iterable<? extends Long> ids) { ids.forEach(store::remove); }
    @Override public void deleteAll(Iterable<? extends DemoSession> entities) { entities.forEach(this::delete); }
    @Override public void deleteAll() { store.clear(); }
    @Override public <Sx extends DemoSession> Optional<Sx> findOne(Example<Sx> ex) { return Optional.empty(); }
    @Override public <Sx extends DemoSession> Page<Sx> findAll(Example<Sx> ex, Pageable pageable) { return Page.empty(); }
    @Override public <Sx extends DemoSession> long count(Example<Sx> ex) { return 0; }
    @Override public <Sx extends DemoSession> boolean exists(Example<Sx> ex) { return false; }
    @Override public <Sx extends DemoSession, R> R findBy(Example<Sx> ex, Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<Sx>, R> fn) { return null; }
}
