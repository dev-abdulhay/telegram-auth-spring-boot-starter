package com.example.demo;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** Hand-written in-memory repo for unit tests. Only implements what the service uses. */
@SuppressWarnings({"unchecked", "ConstantConditions", "NullableProblems"})
public class StubUserRepo implements DemoUserRepository {

    private final Map<Long, DemoUser> store = new HashMap<>();
    private final AtomicLong seq = new AtomicLong(0);

    @Override public Optional<DemoUser> findByTelegramId(Long telegramId) {
        return store.values().stream().filter(u -> telegramId.equals(u.getTelegramId())).findFirst();
    }
    @Override public <Sx extends DemoUser> Sx save(Sx entity) {
        if (entity.getId() == null) entity.setId(seq.incrementAndGet());
        store.put(entity.getId(), entity);
        return entity;
    }
    @Override public Optional<DemoUser> findById(Long id) { return Optional.ofNullable(store.get(id)); }
    @Override public List<DemoUser> findAll() { return new ArrayList<>(store.values()); }
    @Override public <Sx extends DemoUser> List<Sx> saveAll(Iterable<Sx> entities) {
        List<Sx> out = new ArrayList<>(); entities.forEach(e -> out.add(save(e))); return out;
    }

    // ---- no-op stubs for the rest of JpaRepository / ListCrudRepository surface ----
    @Override public void flush() {}
    @Override public <Sx extends DemoUser> Sx saveAndFlush(Sx entity) { return save(entity); }
    @Override public <Sx extends DemoUser> List<Sx> saveAllAndFlush(Iterable<Sx> entities) { return saveAll(entities); }
    @Override public void deleteAllInBatch(Iterable<DemoUser> entities) {}
    @Override public void deleteAllByIdInBatch(Iterable<Long> ids) {}
    @Override public void deleteAllInBatch() {}
    @Override public DemoUser getOne(Long id) { return store.get(id); }
    @Override public DemoUser getById(Long id) { return store.get(id); }
    @Override public DemoUser getReferenceById(Long id) { return store.get(id); }
    @Override public <Sx extends DemoUser> List<Sx> findAll(Example<Sx> ex) { return List.of(); }
    @Override public <Sx extends DemoUser> List<Sx> findAll(Example<Sx> ex, Sort sort) { return List.of(); }
    @Override public List<DemoUser> findAllById(Iterable<Long> ids) { return List.of(); }
    @Override public List<DemoUser> findAll(Sort sort) { return List.of(); }
    @Override public Page<DemoUser> findAll(Pageable pageable) { return Page.empty(); }
    @Override public boolean existsById(Long id) { return store.containsKey(id); }
    @Override public long count() { return store.size(); }
    @Override public void deleteById(Long id) { store.remove(id); }
    @Override public void delete(DemoUser entity) { if (entity.getId() != null) store.remove(entity.getId()); }
    @Override public void deleteAllById(Iterable<? extends Long> ids) { ids.forEach(store::remove); }
    @Override public void deleteAll(Iterable<? extends DemoUser> entities) { entities.forEach(this::delete); }
    @Override public void deleteAll() { store.clear(); }
    @Override public <Sx extends DemoUser> Optional<Sx> findOne(Example<Sx> ex) { return Optional.empty(); }
    @Override public <Sx extends DemoUser> Page<Sx> findAll(Example<Sx> ex, Pageable pageable) { return Page.empty(); }
    @Override public <Sx extends DemoUser> long count(Example<Sx> ex) { return 0; }
    @Override public <Sx extends DemoUser> boolean exists(Example<Sx> ex) { return false; }
    @Override public <Sx extends DemoUser, R> R findBy(Example<Sx> ex, Function<FluentQuery.FetchableFluentQuery<Sx>, R> fn) { return null; }
}
