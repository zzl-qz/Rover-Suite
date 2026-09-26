package com.rover.agent.runtime.repository;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 有界有序存储：同键覆盖写入，容量满时**拒绝新增键**（返回 {@code false}），不做任何淘汰。
 *
 * 刻意不在这里静默淘汰：这些存储承载的是同一批互相引用的领域对象（会话 → 事件 → 任务 → 消息），
 * 由存储自己按写入顺序丢最早的一条，会出现「会话没了但事件、消息、任务还在」的孤儿记录。
 * 谁该被清理由上层保留策略（{@link com.rover.agent.runtime.task.WorkspaceRetention}）决定并整组清理，
 * 腾出位置后写入自然成功。
 *
 * 同键覆盖写入不改变原有顺序，也不受容量限制——更新已有记录永远不会因为"满了"而失败。
 * 所有读写都在同一把锁内完成，供内存版 Repository 复用以保证线程安全；后续接入持久化后本类不再使用。
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