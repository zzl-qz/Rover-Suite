package com.rover.gateway.core.filter.ratelimit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RateLimiterKeyCapTest {

    @Test
    void tokenBucketFullMapRejectsNewKeyWithoutReset() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1);
        for (int i = 0; i < 4096; i++) {
            assertTrue(limiter.tryAcquire("k" + i));
        }
        assertFalse(limiter.tryAcquire("k0"));
        assertFalse(limiter.tryAcquire("overflow"));
        assertFalse(limiter.tryAcquire("k0"));
    }

    @Test
    void slidingWindowFullMapRejectsNewKeyWithoutReset() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(1, 60);
        for (int i = 0; i < 4096; i++) {
            assertTrue(limiter.tryAcquire("k" + i));
        }
        assertFalse(limiter.tryAcquire("k0"));
        assertFalse(limiter.tryAcquire("overflow"));
        assertFalse(limiter.tryAcquire("k0"));
    }
}
