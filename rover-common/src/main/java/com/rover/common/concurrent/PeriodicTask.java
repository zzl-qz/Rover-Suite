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
 *
 * 这个类是什么：包装单线程定时线程池的固定延迟周期任务。
 * 核心职责：以 scheduleWithFixedDelay 近似「while + sleep」的间隔语义跑一个 Runnable，
 * 单次执行失败互不影响，并暴露 start/stop 方便启停与随主对象一起关闭。
 * 被谁用：注册中心/网关中需要周期发送心跳、定期清扫的场景。
 */
public class PeriodicTask implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PeriodicTask.class);

    /** 任务名，同时用作内部线程名，方便排查 */
    private final String name;
    /** 内部单线程调度池 */
    private final ScheduledExecutorService executor;
    /** 是否已启动，CAS 保证 start 幂等 */
    private final AtomicBoolean started = new AtomicBoolean(false);
    /** 当前调度任务的句柄，stop 时用来取消 */
    private volatile ScheduledFuture<?> future;

    /**
     * 构造周期任务。
     *
     * @param name 任务名；为空时使用默认名，同时作为线程名前缀
     */
    public PeriodicTask(String name) {
        // 名字为空时兜底，保证线程名可读
        this.name = name == null || name.isBlank() ? "periodic-task" : name;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, this.name);
            thread.setDaemon(true); // 守护线程，进程退出时自动结束，不阻塞关闭
            return thread;
        });
    }

    /**
     * 固定延迟调度，语义接近 while + sleep，但更好停。
     * 后面如果有大量超时/延时任务，再考虑时间轮。
     *
     * @param task          每次周期执行的任务
     * @param initialDelayMs 首次执行前的延迟(毫秒)，下限 0
     * @param periodMs      相邻两次执行的间隔(毫秒)，下限 1
     */
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

    /**
     * 停止任务：取消当前调度并关闭内部线程池，幂等可重复调用。
     */
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
            // 单次失败别把整个调度打挂
            log.warn("周期任务执行失败: {}", name, ex);
        }
    }
}
