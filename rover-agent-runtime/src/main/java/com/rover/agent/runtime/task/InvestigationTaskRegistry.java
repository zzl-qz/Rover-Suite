package com.rover.agent.runtime.task;

import com.rover.agent.core.model.TaskView;
import jakarta.annotation.PreDestroy;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 调查任务登记与并发执行。
 *
 * 任务只保留在当前进程：数量超过上限时先淘汰最早的已结束任务，仍在执行的任务不淘汰；
 * 淘汰后仍满则拒绝新任务，由调用方转成「任务繁忙」。
 */
public final class InvestigationTaskRegistry {

    private static final int MAX_TASKS = 100;
    private static final int WORKER_THREADS = 2;

    private final Map<String, InvestigationTask> tasks = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
            WORKER_THREADS, WORKER_THREADS, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "rover-agent-diagnosis");
                thread.setDaemon(true);
                return thread;
            });

    /** 登记一个新任务；已达上限时抛 {@link RejectedExecutionException}。 */
    public InvestigationTask register(String sessionId, String incidentId, String path, String question) {
        evictOldestTerminal();
        if (tasks.size() >= MAX_TASKS) {
            throw new RejectedExecutionException("诊断任务已满");
        }
        InvestigationTask task = new InvestigationTask(
                UUID.randomUUID().toString(), sessionId, incidentId, path, question);
        tasks.put(task.taskId(), task);
        return task;
    }

    /** 提交执行；队列满时抛 {@link RejectedExecutionException}。 */
    public void execute(Runnable runnable) {
        workers.execute(runnable);
    }

    /** 任务执行提交失败时回滚登记，避免留下永远 PENDING 的任务。 */
    public void discard(String taskId) {
        tasks.remove(taskId);
    }

    public TaskView get(String taskId) {
        InvestigationTask task = tasks.get(taskId);
        return task == null ? null : task.view();
    }

    @PreDestroy
    public void stop() {
        workers.shutdownNow();
    }

    private void evictOldestTerminal() {
        if (tasks.size() < MAX_TASKS) {
            return;
        }
        tasks.values().stream().filter(InvestigationTask::terminal)
                .min(Comparator.comparingLong(InvestigationTask::createdAtMillis))
                .ifPresent(oldest -> tasks.remove(oldest.taskId(), oldest));
    }
}