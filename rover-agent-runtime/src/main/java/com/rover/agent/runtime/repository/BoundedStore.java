package com.rover.agent.runtime.repository;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 有界有序存储：同键覆盖写入，容量超出后按写入顺序淘汰最早的记录（同键覆盖不改变原有顺序）。
 *
 * 所有读写都在同一把锁内完成，供内存版 Repository 复用以保证线程安全；后续接入持久化后本类不再使用。
 */
final class BoundedStore<K, V> {

    private final int capacity;
    private final Map<K, V> values = new LinkedHashMap<>();

    BoundedStore(int capacity) {
        this.capacity = capacity;
    }

    synchronized void put(K key, V value) {
        values.put(key, value);
        while (values.size() > capacity) {
            Iterator<K> keys = values.keySet().iterator();
            keys.next();
            keys.remove();
        }
    }

    synchronized Optional<V> get(K key) {
        return Optional.ofNullable(values.get(key));
    }

    synchronized Optional<V> remove(K key) {
        return Optional.ofNullable(values.remove(key));
    }

    synchronized List<V> values() {
        return List.copyOf(values.values());
    }

    synchronized List<V> valuesMatching(Predicate<V> predicate) {
        List<V> matched = new ArrayList<>();
        for (V value : values.values()) {
            if (predicate.test(value)) {
                matched.add(value);
            }
        }
        return List.copyOf(matched);
    }

    synchronized void removeMatching(Predicate<V> predicate) {
        values.values().removeIf(predicate);
    }
}