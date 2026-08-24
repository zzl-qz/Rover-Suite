package com.rover.gateway.core.filter.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 以当前窗口和上一窗口的加权计数平滑窗口边界。 */
final class SlidingWindowRateLimiter implements RateLimiter {

    private static final int MAX_KEYS = 4096;

    private final long limit;
    private final long windowNanos;
    private final Map<String, SlidingCounter> counters = new ConcurrentHashMap<>();

    SlidingWindowRateLimiter(long limit, int windowSeconds) {
        this.limit = limit;
        this.windowNanos = windowSeconds * 1_000_000_000L;
    }

    @Override
    public boolean tryAcquire(String key) {
        if (!counters.containsKey(key) && counters.size() >= MAX_KEYS) {
            // 与令牌桶一致，防止路径等外部输入无限占用本地内存。
            counters.clear();
        }
        return counters.computeIfAbsent(key, ignored -> new SlidingCounter())
                .tryAcquire(limit, windowNanos);
    }

    /** 单个键的窗口计数，只服务当前算法，无独立复用价值。 */
    private static final class SlidingCounter {

        private long windowStart = System.nanoTime();
        private long currentCount;
        private long previousCount;

        private synchronized boolean tryAcquire(long limit, long windowNanos) {
            long now = System.nanoTime();
            long currentWindow = now - Math.floorMod(now, windowNanos);
            if (currentWindow != windowStart) {
                long windowsElapsed = Math.max(1, (currentWindow - windowStart) / windowNanos);
                previousCount = windowsElapsed == 1 ? currentCount : 0;
                currentCount = 0;
                windowStart = currentWindow;
            }

            double previousWeight = 1.0
                    - ((double) (now - windowStart) / windowNanos);
            double estimated = previousCount * Math.max(0.0, previousWeight) + currentCount;
            if (estimated >= limit) {
                return false;
            }
            currentCount++;
            return true;
        }
    }
}
