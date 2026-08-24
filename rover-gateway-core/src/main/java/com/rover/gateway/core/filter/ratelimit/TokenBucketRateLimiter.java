package com.rover.gateway.core.filter.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 平滑补充令牌，允许在桶容量内短时突发。 */
final class TokenBucketRateLimiter implements RateLimiter {

    private static final int MAX_KEYS = 4096;

    private final long permitsPerSecond;
    private final double capacity;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    TokenBucketRateLimiter(long permitsPerSecond, long burst) {
        this.permitsPerSecond = permitsPerSecond;
        this.capacity = burst;
    }

    @Override
    public boolean tryAcquire(String key) {
        return bucket(key).tryAcquire(permitsPerSecond, capacity);
    }

    private Bucket bucket(String key) {
        if (!buckets.containsKey(key) && buckets.size() >= MAX_KEYS) {
            // 保持本地限流内存有上限；高基数键需求再引入带淘汰策略的缓存。
            buckets.clear();
        }
        return buckets.computeIfAbsent(key, ignored -> new Bucket(capacity));
    }

    /** 单个键的令牌余额，只服务令牌桶算法，无独立复用价值。 */
    private static final class Bucket {

        private double tokens;
        private long lastNanos = System.nanoTime();

        private Bucket(double capacity) {
            this.tokens = capacity;
        }

        private synchronized boolean tryAcquire(long permitsPerSecond, double capacity) {
            long now = System.nanoTime();
            long elapsed = Math.max(0, now - lastNanos);
            tokens = Math.min(capacity,
                    tokens + (elapsed / 1_000_000_000.0) * permitsPerSecond);
            lastNanos = now;
            if (tokens < 1.0) {
                return false;
            }
            tokens -= 1.0;
            return true;
        }
    }
}
