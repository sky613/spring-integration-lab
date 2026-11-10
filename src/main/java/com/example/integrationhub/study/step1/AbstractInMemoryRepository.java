package com.example.integrationhub.study.step1;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [Abstract Class / Inheritance] 공통 저장 로직은 구현하고, 엔티티별로 다른 ID 추출만 하위 클래스에 위임한다.
 * Interface는 "계약", Abstract Class는 "상태와 공통 구현"을 공유할 때 사용한다.
 */
public abstract class AbstractInMemoryRepository<T, ID> implements Repository<T, ID> {

    // 비동기 처리(CompletableFuture)에서 동시에 접근하므로 Thread-safe Map을 사용한다.
    private final Map<ID, T> store = new ConcurrentHashMap<>();

    protected abstract ID idOf(T entity);

    @Override
    public T save(T entity) {
        store.put(idOf(entity), entity);
        return entity;
    }

    @Override
    public Optional<T> findById(ID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<T> findAll() {
        return List.copyOf(store.values());
    }
}
