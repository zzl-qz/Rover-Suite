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
 * Created: 2026-08-03 11:10:00
 * Description: 基于 requestId 的请求/响应挂起表：超时/断连时兜底清理，杜绝 Future 泄漏
 */
public class PendingRequestTable<T> implements AutoCloseable {

    private final Map<Long, Entry<T>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeoutScheduler;
    private final int maxPending;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final boolean ownsScheduler;

    /** 默认：最多 10000 个在途请求，内部自建超时调度线程池 */
    public PendingRequestTable() {
        this(10000, null);
    }

    /** 指定最大在途请求数，超时调度线程池内部自建 */
    public PendingRequestTable(int maxPending) {
        this(maxPending, null);
    }

    /** 全参数构造；timeoutScheduler 为 null 时内部自建守护线程池 */
    public PendingRequestTable(int maxPending, ScheduledExecutorService timeoutScheduler) {
        this.maxPending = Math.max(1, maxPending);
        if (timeoutScheduler == null) {
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
                Thread thread = new Thread(r, "pending-request-timeout");
                thread.setDaemon(true); // 守护线程，避免阻塞进程关闭
                return thread;
            });
            executor.setRemoveOnCancelPolicy(true); // 取消的任务直接从队列移除
            this.timeoutScheduler = executor;
            this.ownsScheduler = true;
        } else {
            this.timeoutScheduler = timeoutScheduler;
            this.ownsScheduler = false;
        }
    }

    /**
     * 挂起一个请求：登记超时任务并返回 future 供调用方 await。
     * 表已关闭 / 在途数超上限 / requestId 重复时抛 IllegalStateException。
     */
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

        // 登记超时任务，到期移除挂起项并终止等待
        long delay = Math.max(timeoutMs, 1L);
        entry.timeoutFuture = timeoutScheduler.schedule(() -> {
            // 超时：移除挂起项并以 TimeoutException 终止等待中的 Future
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

    /** 请求成功：先移除再完成保证只唤醒一次；false 表示已超时或 requestId 不存在。 */
    public boolean complete(long requestId, T value) {
        // 先移除再完成，保证只唤醒一次；remove 返回 null 说明已被超时任务清掉
        Entry<T> entry = pending.remove(requestId);
        if (entry == null) {
            return false;
        }
        return entry.future.complete(value);
    }

    /** 请求失败：以异常终止等待中的 Future；false 表示已超时或 requestId 不存在。 */
    public boolean fail(long requestId, Throwable error) {
        Entry<T> entry = pending.remove(requestId);
        if (entry == null) {
            return false;
        }
        return entry.future.completeExceptionally(error);
    }

    /** 批量失败所有在途请求（断连时调用），避免调用方无限等待。 */
    public void failAll(Throwable error) {
        // keySet 是弱一致视图，逐条 fail 内部做 remove，循环安全
        for (Long requestId : pending.keySet()) {
            fail(requestId, error);
        }
    }

    /** 当前在途请求数。 */
    public int size() {
        return pending.size();
    }

    /** 关闭：幂等，先批量失败在途请求，再停掉内部自建的调度线程池。 */
    @Override
    public void close() {
        // CAS 保证关闭只执行一次
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        failAll(new IllegalStateException("PendingRequestTable 已关闭"));
        // 只有自建的线程池才需要关，业务方传入的由对方管理
        if (ownsScheduler) {
            timeoutScheduler.shutdownNow();
        }
    }

    /** 校验未关闭，已关闭抛 IllegalStateException。 */
    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("PendingRequestTable 已关闭");
        }
    }

    /** 挂起项：future 为等待方回调，timeoutFuture 指向已登记的超时任务。 */
    private static final class Entry<T> {
        private final CompletableFuture<T> future;
        private volatile ScheduledFuture<?> timeoutFuture;

        private Entry(CompletableFuture<T> future) {
            this.future = future;
        }
    }
}
