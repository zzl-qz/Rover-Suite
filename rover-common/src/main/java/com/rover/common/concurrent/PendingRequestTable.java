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
 *
 * 这个类是什么：基于 requestId 的请求/响应挂起表，Netty 客户端模型下
 * 每个在途请求对应一个 CompletableFuture。
 * 核心职责：①create 时挂起并登记超时任务；②complete/fail 按 requestId 精准唤醒；
 * ③超时、批量失败、close 时兜底清空，杜绝 Future 泄漏。
 * 被谁用：rover-client/rover-registry-client 等发送请求后等待响应的模块。
 */
public class PendingRequestTable<T> implements AutoCloseable {

    private final Map<Long, Entry<T>> pending = new ConcurrentHashMap<>(); // 等待列表
    private final ScheduledExecutorService timeoutScheduler; // 执行线程池
    private final int maxPending; // 最大等待数
    private final AtomicBoolean closed = new AtomicBoolean(false); // 是否已关闭
    private final boolean ownsScheduler; // 是否拥有线程池

    /** 默认：最多 10000 个在途请求，内部自建超时调度线程池 */
    public PendingRequestTable() {
        this(10000, null);
    }

    /** 指定最大在途请求数，超时调度线程池内部自建 */
    public PendingRequestTable(int maxPending) {
        this(maxPending, null);
    }

    /**
     * 全参数构造。
     *
     * @param maxPending          最大在途请求数，下限 1
     * @param timeoutScheduler    外部传入的调度线程池；传 null 时内部自建守护线程池
     */
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
     *
     * @param requestId 请求响应配对 ID，必须全局唯一
     * @param timeoutMs 超时毫秒，下限 1ms
     * @return 挂起中的 CompletableFuture，供调用方 await
     * @throws IllegalStateException 表已关闭 / 在途请求数超过上限 / requestId 重复
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
            // 超时触发：把挂起项移除，并以 TimeoutException 异常终止等待中的 Future
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
     *
     * @param requestId 请求 ID
     * @param value     响应值
     * @return true 表示成功唤醒并完成；false 表示已超时被移除或 requestId 不存在
     */
    /** @return false 表示已超时或根本不认识这个 requestId */
    public boolean complete(long requestId, T value) {
        // 先移除再完成，保证只唤醒一次；remove 返回 null 说明已被超时任务清掉
        Entry<T> entry = pending.remove(requestId);
        if (entry == null) {
            return false;
        }
        return entry.future.complete(value);
    }

    /**
     * 请求失败
     *
     * @param requestId 请求 ID
     * @param error     失败原因
     * @return true 表示成功唤醒并以异常终止；false 表示已超时被移除或 requestId 不存在
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
     * 断连时调用，把所有在途请求统一置为失败，避免调用方无限等待。
     */
    public void failAll(Throwable error) {
        // keySet 是弱一致视图，逐条 fail 内部做 remove，循环安全
        for (Long requestId : pending.keySet()) {
            fail(requestId, error);
        }
    }

    /** @return 当前在途请求数 */
    public int size() {
        return pending.size();
    }

    /**
     * 关闭
     * 幂等：只有第一次调用生效。先批量失败全部在途请求，再停掉内部自建的调度线程池。
     */
    @Override
    public void close() {
        // CAS 保证关闭只执行一次
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
     *
     * @throws IllegalStateException 表已关闭
     */
    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("PendingRequestTable 已关闭");
        }
    }

    /**
     * 将等待请求封装成实体
     * future 为等待方持有的回调；timeoutFuture 指向已登记的超时任务，用 volatile 保证可见性。
     */
    private static final class Entry<T> {
        private final CompletableFuture<T> future;
        private volatile ScheduledFuture<?> timeoutFuture;

        private Entry(CompletableFuture<T> future) {
            this.future = future;
        }
    }
}
