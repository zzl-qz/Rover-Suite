package com.rover.gateway.core.filter.ratelimit;

/** 限流算法的最小内部契约；一个键代表一个独立配额。 */
interface RateLimiter {

    boolean tryAcquire(String key);
}
