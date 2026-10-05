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
    public void stop() {
        deadlines.shutdownNow();
        workers.shutdownNow();
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
