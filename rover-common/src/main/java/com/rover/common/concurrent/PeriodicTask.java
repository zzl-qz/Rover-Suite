package com.rover.common.concurrent;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:55:00
 * Description: 固定间隔周期任务，适合心跳这种简单定时
 */
public class PeriodicTask implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PeriodicTask.class);

    private final String name;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> future;

    public PeriodicTask(String name) {
        this.name = name == null || name.isBlank() ? "periodic-task" : name;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, this.name);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 固定延迟调度，语义接近 while + sleep，但更好停。
     * 后面如果有大量超时/延时任务，再考虑时间轮。
     */
    public void start(Runnable task, long initialDelayMs, long periodMs) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        long delay = Math.max(initialDelayMs, 0L);
        long period = Math.max(periodMs, 1L);
        future = executor.scheduleWithFixedDelay(() -> safeRun(task), delay, period, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        started.set(false);
        ScheduledFuture<?> current = future;
        if (current != null) {
            current.cancel(false);
        }
        executor.shutdownNow();
    }

    @Override
    public void close() {
        stop();
    }

    private void safeRun(Runnable task) {
        try {
            task.run();
        } catch (Throwable ex) {
            // 单次失败别把整个调度打挂
            log.warn("周期任务执行失败: {}", name, ex);
        }
    }
}
