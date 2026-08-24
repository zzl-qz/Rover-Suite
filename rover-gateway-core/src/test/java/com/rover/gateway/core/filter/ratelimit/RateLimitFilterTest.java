package com.rover.gateway.core.filter.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.spi.filter.FilterChain;
import com.rover.common.spi.filter.RequestContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RateLimitFilterTest {

    @Test
    void tokenBucketRejectsAfterBurstExhausted() {
        RateLimitSettings settings = new RateLimitSettings();
        settings.setEnabled(true);
        settings.setAlgorithm(RateLimitSettings.TOKEN_BUCKET);
        settings.setKey(RateLimitSettings.PATH);
        settings.setPermitsPerSecond(1);
        settings.setBurst(2);

        RateLimitFilter filter = new RateLimitFilter(settings);
        RecordingContext context = new RecordingContext("/api/demo");
        FilterChain pass = (ctx) -> CompletableFuture.completedFuture(null);

        assertTrue(filter.doFilter(context, pass).isDone());
        assertEquals(0, context.rejectCount.get());
        assertTrue(filter.doFilter(context, pass).isDone());
        assertEquals(0, context.rejectCount.get());

        assertTrue(filter.doFilter(context, pass).isDone());
        assertEquals(1, context.rejectCount.get());
        assertEquals(429, context.lastStatus.get());
    }

    @Test
    void pathKeyIsolatesDifferentPaths() {
        RateLimitSettings settings = new RateLimitSettings();
        settings.setAlgorithm(RateLimitSettings.TOKEN_BUCKET);
        settings.setKey(RateLimitSettings.PATH);
        settings.setPermitsPerSecond(1);
        settings.setBurst(1);

        RateLimitFilter filter = new RateLimitFilter(settings);
        FilterChain pass = (ctx) -> CompletableFuture.completedFuture(null);

        RecordingContext a = new RecordingContext("/a");
        RecordingContext b = new RecordingContext("/b");
        assertTrue(filter.doFilter(a, pass).isDone());
        assertTrue(filter.doFilter(b, pass).isDone());
        assertEquals(0, a.rejectCount.get());
        assertEquals(0, b.rejectCount.get());

        assertTrue(filter.doFilter(a, pass).isDone());
        assertEquals(1, a.rejectCount.get());
        assertFalse(b.rejectCount.get() > 0);
    }

    private static final class RecordingContext implements RequestContext {

        private final String path;
        private final AtomicInteger rejectCount = new AtomicInteger();
        private final AtomicInteger lastStatus = new AtomicInteger();
        private boolean completed;

        private RecordingContext(String path) {
            this.path = path;
        }

        @Override
        public Object getAttribute(String key) {
            return null;
        }

        @Override
        public void setAttribute(String key, Object value) {
        }

        @Override
        public String getRequestPath() {
            return path;
        }

        @Override
        public void reject(int status, String body) {
            rejectCount.incrementAndGet();
            lastStatus.set(status);
            markCompleted();
        }

        @Override
        public boolean isCompleted() {
            return completed;
        }

        @Override
        public void markCompleted() {
            completed = true;
        }
    }
}
