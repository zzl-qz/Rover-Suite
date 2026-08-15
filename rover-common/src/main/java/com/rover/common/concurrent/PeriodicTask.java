package com.rover.common.concurrent;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-03 11:05:00
 * Description: 固定延迟周期任务（近似 while+sleep），单次失败互不影响，支持随主对象一起关闭
 */
@Slf4j
public class PeriodicTask implements AutoCloseable {

    /** 任务名，同时用作内部线程名，方便排查 */
    private final String name;
    /** 内部单线程调度池 */
    private final ScheduledExecutorService executor;
    /** 是否已启动，CAS 保证 start 幂等 */
    private final AtomicBoolean started = new AtomicBoolean(false);
    /** 当前调度任务的句柄，stop 时用来取消 */
    private volatile ScheduledFuture<?> future;

    /** 构造周期任务；name 为空时用默认名，同时作为线程名前缀。 */
    public PeriodicTask(String name) {
        // 名字为空时兜底，保证线程名可读
        this.name = name == null || name.isBlank() ? "periodic-task" : name;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, this.name);
            thread.setDaemon(true); // 守护线程，进程退出时自动结束，不阻塞关闭
            return thread;
        });
    }

    /** 固定延迟调度，语义接近 while+sleep 但更好停；幂等，重复调用忽略。 */
    public void start(Runnable task, long initialDelayMs, long periodMs) {
        // compareAndSet 保证只启动一次，重复调用直接忽略
        if (!started.compareAndSet(false, true)) {
            return;
        }
        // 非法入参兜底，避免调度器抛异常
        long delay = Math.max(initialDelayMs, 0L);
        long period = Math.max(periodMs, 1L);
        // 固定延迟：上一次执行结束再等 period 才跑下一次
        future = executor.scheduleWithFixedDelay(() -> safeRun(task), delay, period, TimeUnit.MILLISECONDS);
    }

    /** 停止任务：取消当前调度并关闭内部线程池，幂等可重复调用。 */
    public void stop() {
        started.set(false);
        ScheduledFuture<?> current = future;
        if (current != null) {
            current.cancel(false); // 不中断正在执行的那一次，等它自然结束
        }
        executor.shutdownNow();
    }

    /** 实现 AutoCloseable，支持 try-with-resources / 随容器一起释放 */
    @Override
    public void close() {
        stop();
    }

    /** 包装真实任务：捕获所有 Throwable，保证调度循环不被单次失败打断 */
    private void safeRun(Runnable task) {
        try {
            task.run();
        } catch (Throwable ex) {
            // 单次失败不中断整个调度
            log.warn("周期任务执行失败: {}", name, ex);
        }
    }
}
