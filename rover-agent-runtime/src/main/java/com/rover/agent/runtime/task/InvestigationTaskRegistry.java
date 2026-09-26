package com.rover.agent.runtime.task;

import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.repository.AgentTaskRepository;
import com.rover.agent.runtime.repository.InMemoryAgentTaskRepository;
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
 * 任务记录写入 {@link AgentTaskRepository}（当前为内存实现，重启即失），它才是任务快照的真相来源；
 * 本类另外持有的只是在当前进程里仍在执行的任务——线程池与解读增量订阅者天生无法持久化，
 * 重启后自然消失。
 *
 * 任务数量超过上限时先淘汰最早的已结束任务，仍在执行的任务不淘汰；淘汰后仍满则拒绝新任务，
 * 由调用方转成「任务繁忙」。
 */
public final class InvestigationTaskRegistry {

    private static final int MAX_TASKS = 100;
    private static final int WORKER_THREADS = 2;

    private final AgentTaskRepository records;
    private final Map<String, InvestigationTask> executing = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
            WORKER_THREADS, WORKER_THREADS, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "rover-agent-diagnosis");
                thread.setDaemon(true);
                return thread;
            });

    /** 当前阶段的内存实现；接入持久化后由组合根注入具体实现。 */
    public InvestigationTaskRegistry() {
        this(new InMemoryAgentTaskRepository(MAX_TASKS));
    }

    public InvestigationTaskRegistry(AgentTaskRepository records) {
        this.records = records;
    }

    /** 登记一个新任务；已达上限时抛 {@link RejectedExecutionException}。 */
    public InvestigationTask register(String sessionId, String incidentId, String path, ResourceTarget target,
                                      String question) {
        evictOldestTerminal();
        if (records.listAll().size() >= MAX_TASKS) {
            throw new RejectedExecutionException("诊断任务已满");
        }
        InvestigationTask task = new InvestigationTask(UUID.randomUUID().toString(), sessionId, incidentId,
                path, target, question, records::save);
        executing.put(task.taskId(), task);
        records.save(task.view());
        return task;
    }

    /** 提交执行；队列满时抛 {@link RejectedExecutionException}。 */
    public void execute(Runnable runnable) {
        workers.execute(runnable);
    }

    /** 任务执行提交失败时回滚登记，避免留下永远 PENDING 的任务。 */
    public void discard(String taskId) {
        executing.remove(taskId);
        records.remove(taskId);
    }

    public TaskView get(String taskId) {
        return records.find(taskId).orElse(null);
    }

    /** 取任务运行态：解读增量订阅需要直接挂到任务上；对外视图仍走 {@link #get(String)}。 */
    public InvestigationTask find(String taskId) {
        return executing.get(taskId);
    }

    @PreDestroy
    public void stop() {
        workers.shutdownNow();
    }

    private void evictOldestTerminal() {
        if (records.listAll().size() < MAX_TASKS) {
            return;
        }
        records.listAll().stream()
                .filter(view -> view.status() == TaskStatus.COMPLETED || view.status() == TaskStatus.FAILED)
                .min(Comparator.comparingLong(TaskView::createdAtMillis))
                .ifPresent(oldest -> {
                    records.remove(oldest.taskId());
                    executing.remove(oldest.taskId());
                });
    }
}