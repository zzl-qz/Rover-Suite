package com.rover.agent.runtime.task;

import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.repository.AgentCheckpointRepository;
import com.rover.agent.core.repository.AgentTaskRepository;
import com.rover.agent.runtime.metrics.AgentMetrics;
import com.rover.agent.runtime.repository.InMemoryAgentCheckpointRepository;
import com.rover.agent.runtime.repository.InMemoryAgentTaskRepository;
import jakarta.annotation.PreDestroy;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 登记任务并管理有界线程池，同一会话最多一个执行中的任务。
 * 容量满时优先淘汰最早结束的任务，仍满或队列满则拒绝提交。
 * 任务快照通过仓储保存，执行态与事件通道仅存在于当前进程。
 */
public final class InvestigationTaskRegistry implements TaskRetirement {

    private static final Logger log = LoggerFactory.getLogger(InvestigationTaskRegistry.class);

    private static final String REJECT_SESSION_BUSY = "SESSION_BUSY";
    private static final String REJECT_CAPACITY = "CAPACITY";
    private static final String REJECT_QUEUE = "QUEUE";

    private final AgentTaskRepository records;
    private final AgentCheckpointRepository checkpoints;
    private final AgentExecutionSettings settings;
    private final TaskEventBus events;
    private final AgentMetrics metrics;
    private final Map<String, InvestigationTask> executing = new ConcurrentHashMap<>();

    /** 会话并发检查与登记必须原子：否则两个并发请求都能通过「当前无执行中任务」的检查。 */
    private final Object registrationLock = new Object();

    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor deadlines;
    private final AgentRunLimits runLimits;
    private volatile boolean draining;
    private com.rover.agent.runtime.repository.JdbcAgentStore durable;
    private final Map<String, java.util.function.BiConsumer<InvestigationTask,String>> handlers = new ConcurrentHashMap<>();
    private final java.util.Set<String> scheduled = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean recoveryStarted = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean stopStarted = new java.util.concurrent.atomic.AtomicBoolean();
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    public void persistence(com.rover.agent.runtime.repository.JdbcAgentStore store) { durable = store; }
    public void handler(String kind, java.util.function.BiConsumer<InvestigationTask,String> handler) { handlers.put(kind, handler); }

    /** 启动完成后再恢复，确保认证服务、端口与模型均已装配。 */
    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextRefreshedEvent.class)
    public void startRecovery() {
        if (durable != null && recoveryStarted.compareAndSet(false, true)) {
            deadlines.scheduleWithFixedDelay(() -> {
                if (draining || workers.isShutdown()) { return; }
                try { durable.pendingJobs().forEach(this::schedule); }
                catch (RuntimeException failure) { log.error("持久任务调度失败，停止接收任务", failure); beginDrain(); }
            }, 0, 250, TimeUnit.MILLISECONDS);
        }
    }

    /** 先持久化可重建的输入，再报告已受理；读 Job 可重放，外部写操作不走此队列。 */
    public void enqueue(InvestigationTask task, String kind, Object payload, Runnable fallback) {
        if (durable == null) { execute(fallback); return; }
        durable.enqueueJob(task.taskId(), kind, payload);
        durable.pendingJobs().stream().filter(job -> job.taskId().equals(task.taskId())).findFirst().ifPresent(this::schedule);
    }

    private void schedule(com.rover.agent.runtime.repository.JdbcAgentStore.Job pending) {
        if (!handlers.containsKey(pending.kind()) || !scheduled.add(pending.taskId())) { return; }
        try {
            workers.execute(() -> {
                try {
                    var job = durable.claimJob(pending.taskId(), pending.attempt()).orElse(null);
                    if (job == null) { return; }
                    InvestigationTask task = executing.get(job.taskId());
                    if (task == null) {
                        TaskView saved = records.find(job.taskId()).orElse(null);
                        if (saved == null || !saved.status().active()) { durable.finishJob(job.taskId(), job.attempt()); return; }
                        task = new InvestigationTask(saved.taskId(), saved.sessionId(), saved.question(), records::save, events,
                                metrics, checkpoints, runLimits, deadlines);
                        task.restore(saved, records.streamSnapshot(job.taskId()).orElse(null), checkpoints.latest(job.taskId()).orElse(null));
                        executing.put(job.taskId(), task);
                    }
                    final long attempt = job.attempt();
                    if (job.budgetJson() != null) { task.restoreBudget(json.readValue(job.budgetJson(), AgentRunBudget.State.class)); }
                    task.budget().persistTo(state -> durable.saveBudget(job.taskId(), attempt, state));
                    task.budget().validateWith(() -> {
                        if (!durable.currentAttempt(job.taskId(), attempt)) { throw new IllegalStateException("任务 attempt 已失效"); }
                    });
                    task.snapshotSink(view -> durable.withCurrentAttempt(job.taskId(), attempt, () -> records.save(view)));
                    task.eventSink((view, event) -> durable.withCurrentAttempt(job.taskId(), attempt, () -> records.saveEvent(view, event)));
                    task.transactionalEvents(true);
                    task.checkpointSink((view, checkpoint) -> durable.withCurrentAttempt(job.taskId(), attempt, () -> checkpoints.save(view, checkpoint)));
                    events.open(job.taskId());
                    try (var actor = com.rover.agent.core.util.AuditActor.enter(durable.sessions().find(task.sessionId()).map(com.rover.agent.core.model.Session::userId).orElse(null))) {
                        handlers.get(job.kind()).accept(task, job.payloadJson());
                    }
                    if (!task.view().status().active()) { durable.finishJob(job.taskId(), attempt); }
                } catch (Exception failure) {
                    log.error("持久任务执行失败: {}", pending.taskId(), failure);
                    InvestigationTask task = executing.get(pending.taskId());
                    if (task != null && !draining) { task.fail("任务恢复或执行失败，请检查持久存储及输入"); }
                } finally { scheduled.remove(pending.taskId()); }
            });
        } catch (RejectedExecutionException busy) { scheduled.remove(pending.taskId()); }
    }
    public boolean draining() { return draining; }
    public void beginDrain() { synchronized (registrationLock) { draining = true; } }
    public void resumeAccepting() { synchronized (registrationLock) { if (workers.isShutdown()) { throw new IllegalStateException("执行器已关闭"); } draining = false; } }

    /** 内存实现与默认执行参数；接入持久化后由组合根注入具体实现。 */
    public InvestigationTaskRegistry() {
        this(new InMemoryAgentTaskRepository(AgentExecutionSettings.defaults().taskCapacity()));
    }

    public InvestigationTaskRegistry(AgentTaskRepository records) {
        this(records, AgentExecutionSettings.defaults());
    }

    public InvestigationTaskRegistry(AgentTaskRepository records, AgentExecutionSettings settings) {
        this(records, settings, new TaskEventBus());
    }

    public InvestigationTaskRegistry(AgentTaskRepository records, AgentExecutionSettings settings,
                                     TaskEventBus events) {
        this(records, settings, events, AgentMetrics.NOOP);
    }

    public InvestigationTaskRegistry(AgentTaskRepository records, AgentExecutionSettings settings,
                                     TaskEventBus events, AgentMetrics metrics) {
        this(records, settings, events, metrics, new InMemoryAgentCheckpointRepository());
    }

    public InvestigationTaskRegistry(AgentTaskRepository records, AgentExecutionSettings settings,
                                     TaskEventBus events, AgentMetrics metrics,
                                     AgentCheckpointRepository checkpoints) {
        this(records, settings, events, metrics, checkpoints, AgentRunLimits.defaults());
    }

    public InvestigationTaskRegistry(AgentTaskRepository records, AgentExecutionSettings settings,
                                     TaskEventBus events, AgentMetrics metrics,
                                     AgentCheckpointRepository checkpoints, AgentRunLimits runLimits) {
        this.records = records;
        this.settings = settings;
        this.events = events;
        this.metrics = metrics == null ? AgentMetrics.NOOP : metrics;
        this.checkpoints = checkpoints == null ? new InMemoryAgentCheckpointRepository() : checkpoints;
        this.runLimits = runLimits;
        this.deadlines = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "rover-agent-deadline");
            thread.setDaemon(true);
            return thread;
        });
        this.deadlines.setRemoveOnCancelPolicy(true);
        this.workers = new ThreadPoolExecutor(settings.workerThreads(), settings.workerThreads(), 0,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(settings.queueCapacity()), runnable -> {
                    Thread thread = new Thread(runnable, "rover-agent-worker");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /**
     * 登记一个新任务：此时只确定会话与问题，调查对象由执行线程解析后回填。
     *
     * @throws SessionTaskRunningException 同会话已有仍在执行的任务
     * @throws RejectedExecutionException  登记容量已满
     */
    public InvestigationTask register(String sessionId, String question) {
        synchronized (registrationLock) {
            if (draining) { throw new RejectedExecutionException("Agent 正在排空，暂不接收新任务"); }
            records.findActiveBySessionId(sessionId).ifPresent(active -> {
                metrics.taskRejected(REJECT_SESSION_BUSY);
                throw new SessionTaskRunningException(active.taskId());
            });
            evictOldestTerminal();
            if (records.listAll().size() >= settings.taskCapacity()) {
                metrics.taskRejected(REJECT_CAPACITY);
                throw new RejectedExecutionException("调查任务已满");
            }
            InvestigationTask task = new InvestigationTask(UUID.randomUUID().toString(), sessionId, question,
                    records::save, events, metrics, checkpoints, runLimits, deadlines);
            task.eventSink(records::saveEvent);
            task.transactionalEvents(records.durableEvents());
            executing.put(task.taskId(), task);
            records.save(task.view());
            // 通道随登记一起开：全程无人订阅时，终态事件也不会丢，晚连上的订阅者仍能读到并收尾。
            events.open(task.taskId());
            task.announceCreated();
            metrics.taskSubmitted();
            return task;
        }
    }

    /** 提交执行；队列满时抛 {@link RejectedExecutionException}，并计入拒绝指标。 */
    public void execute(Runnable runnable) {
        try {
            workers.execute(runnable);
        } catch (RejectedExecutionException ex) {
            metrics.taskRejected(REJECT_QUEUE);
            throw ex;
        }
        metrics.taskQueued(workers.getQueue().size());
    }

    /** 任务执行提交失败时回滚登记，避免留下永远 PENDING 的任务。 */
    public void discard(String taskId) {
        executing.remove(taskId);
        records.remove(taskId);
        checkpoints.removeByTask(taskId);
        events.close(taskId);
    }

    /**
     * 取消一个仍在执行中的任务（协作式）。
     *
     * 标记取消并中断其执行线程；执行线程在检查点看到 {@link InvestigationTask#cancelled()} 后会停下，
     * 不再产出结论。任务不存在或已终态时返回 false（调用方据此回 404 / 409）。
     */
    public boolean cancel(String taskId) {
        InvestigationTask task = executing.get(taskId);
        if (task == null) {
            return false;
        }
        boolean cancelledNow = task.cancel("任务已被用户取消");
        if (cancelledNow) {
            task.interruptRunner();
        }
        return cancelledNow;
    }

    public TaskView get(String taskId) {
        return records.find(taskId).orElse(null);
    }

    /** 取任务运行态：事件订阅需要直接挂到任务上；对外视图仍走 {@link #get(String)}。 */
    public InvestigationTask find(String taskId) {
        return executing.get(taskId);
    }

    @Override
    public boolean hasActiveTasksInSession(String sessionId) {
        return records.findBySessionId(sessionId).stream().anyMatch(view -> view.status().active());
    }

    @Override
    public boolean hasActiveTasksInIncident(String incidentId) {
        return records.findByIncidentId(incidentId).stream().anyMatch(view -> view.status().active());
    }

    /**
     * 回收会话的任务记录、执行登记与事件通道；调用前须确认无执行中任务。
     * 执行中任务会被跳过。
     */
    @Override
    public int retireBySession(String sessionId) {
        return retire(records.findBySessionId(sessionId));
    }

    @Override
    public int retireByIncident(String incidentId) {
        return retire(records.findByIncidentId(incidentId));
    }

    @PreDestroy
    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class)
    public void stop() {
        if (!stopStarted.compareAndSet(false, true)) { return; }
        beginDrain();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                executing.values().forEach(task -> {
                    if (durable != null) { task.suspendForRestart(); }
                    else { task.cancel("Admin 正在关闭，执行已停止"); }
                });
                workers.shutdownNow();
            }
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); workers.shutdownNow(); }
        deadlines.shutdownNow();
    }

    private int retire(List<TaskView> tasks) {
        int retired = 0;
        for (TaskView view : tasks) {
            if (view.status().active()) {
                log.warn("保留策略尝试回收仍在执行的任务 {}，已跳过", view.taskId());
                continue;
            }
            executing.remove(view.taskId());
            records.remove(view.taskId());
            checkpoints.removeByTask(view.taskId());
            events.close(view.taskId());
            retired++;
        }
        return retired;
    }

    private void evictOldestTerminal() {
        if (records.listAll().size() < settings.taskCapacity()) {
            return;
        }
        records.listAll().stream()
                .filter(view -> view.status().terminal())
                .min(Comparator.comparingLong(TaskView::createdAtMillis))
                .ifPresent(oldest -> {
                    records.remove(oldest.taskId());
                    checkpoints.removeByTask(oldest.taskId());
                    executing.remove(oldest.taskId());
                    events.close(oldest.taskId());
                });
    }
}
