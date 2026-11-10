package com.example.integrationhub.study.step1;

import java.util.List;
import java.util.Optional;

/** [Generic Interface] 엔티티 타입(T)과 ID 타입(ID)에 독립적인 저장소 계약. */
public interface Repository<T, ID> {

    T save(T entity);

    /** [Optional] "없을 수 있음"을 null 대신 타입으로 표현한다. */
    Optional<T> findById(ID id);

    List<T> findAll();
}
