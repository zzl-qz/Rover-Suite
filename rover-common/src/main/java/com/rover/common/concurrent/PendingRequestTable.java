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
 * Description: 请求响应匹配器
 *  按 requestId 挂起等待响应，超时和断连时负责清掉，避免把 Future 堆在内存里
 */
public class PendingRequestTable<T> implements AutoCloseable {

    private final Map<Long, Entry<T>> pending = new ConcurrentHashMap<>(); // 等待列表
    private final ScheduledExecutorService timeoutScheduler; // 执行线程池
    private final int maxPending; // 最大等待数
    private final AtomicBoolean closed = new AtomicBoolean(false); // 是否已关闭
    private final boolean ownsScheduler; // 是否拥有线程池

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
                thread.setDaemon(true); // 守护线程，避免卡着进程，导致无法正常关闭
                return thread;
            });
            executor.setRemoveOnCancelPolicy(true); // 任务被取消之后直接去掉
            this.timeoutScheduler = executor;
            this.ownsScheduler = true;
        } else {
            this.timeoutScheduler = timeoutScheduler;
            this.ownsScheduler = false;
        }
    }

    /**
     * 创建future，塞到自己本地并返回给调用方
     */
    public CompletableFuture<T> create(long requestId, long timeoutMs) {
        ensureOpen();
        if (pending.size() >= maxPending) {
            throw new IllegalStateException("在途请求过多: " + pending.size());
        }

        // 将请求等待封装到本地Map中
        CompletableFuture<T> future = new CompletableFuture<>();
        Entry<T> entry = new Entry<>(future);
        Entry<T> previous = pending.putIfAbsent(requestId, entry);
        if (previous != null) {
            throw new IllegalStateException("重复的 requestId: " + requestId);
        }

        // 设置延期任务来删除过期请求
        long delay = Math.max(timeoutMs, 1L);
        entry.timeoutFuture = timeoutScheduler.schedule(() -> {
            Entry<T> removed = pending.remove(requestId);
            if (removed != null) {
                removed.future.completeExceptionally(
                        new TimeoutException("等待响应超时, requestId=" + requestId + ", timeoutMs=" + delay));
            }
        }, delay, TimeUnit.MILLISECONDS);

        // 注册兜底任务，正常完成或异常完成都把超时任务取消掉
        future.whenComplete((value, error) -> {
            ScheduledFuture<?> timeoutFuture = entry.timeoutFuture;
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
            }
        });
        return future;
    }

    /**
     * 请求成功
     */
    public boolean complete(long requestId, T value) {
        Entry<T> entry = pending.remove(requestId);
        if (entry == null) {
            return false;
        }
        return entry.future.complete(value);
    }

    /**
     * 请求失败
     */
    public boolean fail(long requestId, Throwable error) {
        Entry<T> entry = pending.remove(requestId);
        if (entry == null) {
            return false;
        }
        return entry.future.completeExceptionally(error);
    }

    /**
     * 批量失败所有请求
     */
    public void failAll(Throwable error) {
        for (Long requestId : pending.keySet()) {
            fail(requestId, error);
        }
    }

    public int size() {
        return pending.size();
    }

    /**
     * 关闭
     */
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

    /**
     * 确保未被关闭
     */
    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("PendingRequestTable 已关闭");
        }
    }

    /**
     * 将等待请求封装成实体
     */
    private static final class Entry<T> {
        private final CompletableFuture<T> future;
        private volatile ScheduledFuture<?> timeoutFuture;

        private Entry(CompletableFuture<T> future) {
            this.future = future;
        }
    }
}
