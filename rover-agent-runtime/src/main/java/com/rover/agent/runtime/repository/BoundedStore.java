package com.rover.agent.runtime.repository;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 线程安全的有界有序存储；容量满时拒绝新增，由上层整组清理。
 * 同键覆盖不改变顺序，也不受容量限制。
 */
final class BoundedStore<K, V> {

    private final int capacity;
    private final Map<K, V> values = new LinkedHashMap<>();

    BoundedStore(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("容量必须为正数");
        }
        this.capacity = capacity;
    }

    /**
     * 写入一条记录。
     *
     * @return 是否写入成功；容量已满且是新键时返回 {@code false}，调用方需要先腾出位置再重试
     */
    synchronized boolean put(K key, V value) {
        if (!values.containsKey(key) && values.size() >= capacity) {
            return false;
        }
        values.put(key, value);
        return true;
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

    synchronized int size() {
        return values.size();
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

    synchronized int removeMatching(Predicate<V> predicate) {
        int before = values.size();
        values.values().removeIf(predicate);
        return before - values.size();
    }

    /** 最早写入的记录（写入顺序上的第一条）；空存储返回空。 */
    synchronized Optional<V> oldest() {
        Iterator<V> iterator = values.values().iterator();
        return iterator.hasNext() ? Optional.of(iterator.next()) : Optional.empty();
    }
}