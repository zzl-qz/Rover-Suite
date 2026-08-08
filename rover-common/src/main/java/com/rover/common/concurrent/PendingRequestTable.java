package com.rover.common.concurrent;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:55:00
 * Description: 按 requestId 挂起等待响应，超时和断连时负责清掉，避免把 Future 堆在内存里
 */
public class PendingRequestTable<T> implements AutoCloseable {

    private final Map<Long, Entry<T>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeoutScheduler;
    private final int maxPending;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final boolean ownsScheduler;

    public PendingRequestTable() {
        this(10000, null);
    }

    public PendingRequestTable(int maxPending) {
        this(maxPending, null);
    }

    public PendingRequestTable(int maxPending, ScheduledExecutorService timeoutScheduler) {
        this.maxPending = Math.max(1, maxPending);
        if (timeoutScheduler == null) {
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
                Thread thread = new Thread(r, "pending-request-timeout");
                thread.setDaemon(true);
                return thread;
            });
            executor.setRemoveOnCancelPolicy(true);
            this.timeoutScheduler = executor;
            this.ownsScheduler = true;
        } else {
            this.timeoutScheduler = timeoutScheduler;
            this.ownsScheduler = false;
        }
    }

    public CompletableFuture<T> create(long requestId, long timeoutMs) {
        ensureOpen();
        if (pending.size() >= maxPending) {
            throw new IllegalStateException("在途请求过多: " + pending.size());
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        Entry<T> entry = new Entry<>(future);
        Entry<T> previous = pending.putIfAbsent(requestId, entry);
        if (previous != null) {
            throw new IllegalStateException("重复的 requestId: " + requestId);
        }

        long delay = Math.max(timeoutMs, 1L);
        entry.timeoutFuture = timeoutScheduler.schedule(() -> {
            Entry<T> removed = pending.remove(requestId);
            if (removed != null) {
                removed.future.completeExceptionally(
                        new TimeoutException("等待响应超时, requestId=" + requestId + ", timeoutMs=" + delay));
            }
        }, delay, TimeUnit.MILLISECONDS);

        // 正常完成或异常完成都把超时任务取消掉
        future.whenComplete((value, error) -> {
            ScheduledFuture<?> timeoutFuture = entry.timeoutFuture;
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
            }
        });
        return future;
    }

    public boolean complete(long requestId, T value) {
        Entry<T> entry = pending.remove(requestId);
        if (entry == null) {
            return false;
        }
        return entry.future.complete(value);
    }

    public boolean fail(long requestId, Throwable error) {
        Entry<T> entry = pending.remove(requestId);
        if (entry == null) {
            return false;
        }
        return entry.future.completeExceptionally(error);
    }

    public void failAll(Throwable error) {
        for (Long requestId : pending.keySet()) {
            fail(requestId, error);
        }
    }

    public int size() {
        return pending.size();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        failAll(new IllegalStateException("PendingRequestTable 已关闭"));
        if (ownsScheduler) {
            timeoutScheduler.shutdownNow();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("PendingRequestTable 已关闭");
        }
    }

    private static final class Entry<T> {
        private final CompletableFuture<T> future;
        private volatile ScheduledFuture<?> timeoutFuture;

        private Entry(CompletableFuture<T> future) {
            this.future = future;
        }
    }
}
