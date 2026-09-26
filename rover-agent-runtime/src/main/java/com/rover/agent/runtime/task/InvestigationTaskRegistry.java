package com.rover.agent.runtime.task;

import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.repository.AgentTaskRepository;
import com.rover.agent.runtime.metrics.AgentMetrics;
import com.rover.agent.runtime.repository.InMemoryAgentTaskRepository;
import jakarta.annotation.PreDestroy;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 调查任务登记与并发执行。
 *
 * 任务记录写入 {@link AgentTaskRepository}（当前为内存实现，重启即失），它才是任务快照的真相来源；
 * 本类另外持有的只是在当前进程里仍在执行的任务——线程池与事件订阅通道天生无法持久化，
 * 重启后自然消失。
 *
 * 三条并发规则在这里统一执行：
 * <ol>
 *   <li>同一会话同时最多一个仍在执行的任务：重复提交抛 {@link SessionTaskRunningException}（HTTP 409），
 *       避免同一事件上两个任务并发、结论互相覆盖；</li>
 *   <li>任务登记容量到达上限时先淘汰最早的已结束任务，仍在执行的任务不淘汰；淘汰后仍满则拒绝新任务；</li>
 *   <li>执行队列是有界的：队列满时提交被拒（HTTP 429），不使用无界队列也不使用缓存线程池。</li>
 * </ol>
 *
 * 线程池与容量都由 {@link AgentExecutionSettings} 配置，本类不硬编码。
 * 被拒、登记、结束与队列深度都上报给 {@link AgentMetrics}：这些计数是"容量配得对不对"的唯一依据。
 */
public final class InvestigationTaskRegistry implements TaskRetirement {

    private static final Logger log = LoggerFactory.getLogger(InvestigationTaskRegistry.class);

    private static final String REJECT_SESSION_BUSY = "SESSION_BUSY";
    private static final String REJECT_CAPACITY = "CAPACITY";
    private static final String REJECT_QUEUE = "QUEUE";

    private final AgentTaskRepository records;
    private final AgentExecutionSettings settings;
    private final TaskEventBus events;
    private final AgentMetrics metrics;
    private final Map<String, InvestigationTask> executing = new ConcurrentHashMap<>();

    /** 会话并发检查与登记必须原子：否则两个并发请求都能通过「当前无执行中任务」的检查。 */
    private final Object registrationLock = new Object();

    private final ThreadPoolExecutor workers;

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
        this.records = records;
        this.settings = settings;
        this.events = events;
        this.metrics = metrics == null ? AgentMetrics.NOOP : metrics;
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
                    records::save, events, metrics);
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
        events.close(taskId);
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
     * 回收会话下的全部任务：记录、执行登记与事件通道一起删。
     *
     * 调用方必须先确认该会话没有执行中的任务（见 {@link #hasActiveTasksInSession(String)}）：
     * 正在跑的任务还在写自己的快照，删了会在下一次状态变更时"复活"。
     * 这里仍然跳过执行中的任务，只是兜底，不作为正常路径。
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
        workers.shutdownNow();
    }

    /** 当前执行参数，供诊断与测试读取。 */
    public AgentExecutionSettings settings() {
        return settings;
    }

    /** 当前等待队列深度。 */
    public int queueSize() {
        return workers.getQueue().size();
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
                    executing.remove(oldest.taskId());
                    events.close(oldest.taskId());
                });
    }
}