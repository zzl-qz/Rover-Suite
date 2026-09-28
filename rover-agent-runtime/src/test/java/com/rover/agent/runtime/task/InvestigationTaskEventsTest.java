package com.rover.agent.runtime.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.event.TaskEvent;
import com.rover.agent.core.event.TaskEventSubscriber;
import com.rover.agent.core.event.TaskEventSubscription;
import com.rover.agent.core.event.TaskEventType;
import com.rover.agent.core.event.TaskSnapshot;
import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.Confidence;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.EvidenceType;
import com.rover.agent.core.model.InvestigationReport;
import com.rover.agent.core.model.StepStatus;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.runtime.metrics.AgentMetrics;
import com.rover.agent.runtime.repository.InMemoryAgentCheckpointRepository;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * 任务事件流：状态变更同步发布事件，晚连上的订阅者靠快照对齐——补发之外的增量既不漏也不重。
 *
 * 事件由总线的派发线程投递，因此断言前先等事件到达；不再有「在任务锁里回调监听器」的语义。
 */
class InvestigationTaskEventsTest {

    private static final long TIMEOUT_MILLIS = 3_000L;

    private final TaskEventBus bus = new TaskEventBus(64);
    private final InvestigationTask task = new InvestigationTask("task-1", "session-1", "为什么失败？",
            view -> { }, bus, AgentMetrics.NOOP, new InMemoryAgentCheckpointRepository());

    @Test
    void publishesLifecycleStepsAndAnalysisInOrder() {
        Recording early = new Recording();
        task.subscribe(early);

        task.announceCreated();
        task.start();
        task.step(AgentStepType.TARGET_RESOLUTION, "目标解析", StepStatus.RUNNING, "确定对象");
        task.step(AgentStepType.TARGET_RESOLUTION, "目标解析", StepStatus.COMPLETED, "已确定");
        task.appendAnalysis("先看路由");
        task.appendAnalysis("，再看实例");
        task.complete(report());

        early.awaitTerminal();
        assertEquals(List.of(TaskEventType.SNAPSHOT, TaskEventType.TASK_CREATED, TaskEventType.TASK_STARTED,
                TaskEventType.STEP_STARTED, TaskEventType.STEP_COMPLETED, TaskEventType.ANALYSIS_DELTA,
                TaskEventType.ANALYSIS_DELTA, TaskEventType.EVIDENCE_ADDED, TaskEventType.TASK_COMPLETED),
                early.types());
        // 快照是订阅那一刻的状态：事件只是通知，任务状态本身始终查得到。
        TaskSnapshot snapshot = early.snapshot();
        assertEquals("", snapshot.analysis());
        assertEquals(TaskStatus.PENDING, snapshot.task().status());
        assertEquals("建议优先排查路由配置", early.payloadValues("summary").get(0));
    }

    @Test
    void lateSubscriberGetsSnapshotWithProducedTextAndOnlyLaterDeltas() {
        task.start();
        task.appendAnalysis("前半段");

        Recording late = new Recording();
        task.subscribe(late);
        task.appendAnalysis("后半段");
        task.complete(report());

        late.awaitTerminal();
        assertEquals(List.of(TaskEventType.SNAPSHOT, TaskEventType.ANALYSIS_DELTA, TaskEventType.EVIDENCE_ADDED,
                TaskEventType.TASK_COMPLETED), late.types());
        // 快照带上订阅前已产生的全文，并声明它覆盖到哪个事件序号，重复投递因此可以被识别。
        TaskSnapshot snapshot = late.snapshot();
        assertEquals("前半段", snapshot.analysis());
        assertTrue(snapshot.coveredEventId() > 0);
        assertEquals(List.of("后半段"), late.payloadValues("text"));
        assertEquals(1, late.payloadValues("summary").size());
    }

    /**
     * 思考增量走独立事件与独立缓冲：它与解读是两段内容，界面上也占两个位置。
     *
     * 顺带断言快照同时带上思考与解读两份全文——晚连上的订阅者靠它一次对齐，不必回放历史事件。
     */
    @Test
    void thinkingDeltaTravelsOnItsOwnStreamAndRidesAlongInSnapshot() {
        task.start();
        task.appendThinking("先确认路由是否命中");
        task.appendAnalysis("路由命中了");

        Recording late = new Recording();
        task.subscribe(late);
        task.appendThinking("再确认实例健康");
        task.complete(report());

        late.awaitTerminal();
        assertEquals(List.of(TaskEventType.SNAPSHOT, TaskEventType.THINKING_DELTA, TaskEventType.EVIDENCE_ADDED,
                TaskEventType.TASK_COMPLETED), late.types());
        TaskSnapshot snapshot = late.snapshot();
        assertEquals("先确认路由是否命中", snapshot.thinking(), "订阅前的思考全文要出现在快照里");
        assertEquals("路由命中了", snapshot.analysis());
        assertEquals(List.of("再确认实例健康"), late.payloadValues("text"), "思考增量不能混进解读那条流");
    }

    @Test
    void clarificationAndFailureAreExpressedAsEvents() {
        Recording subscriber = new Recording();
        task.subscribe(subscriber);
        task.waitForInput("请指明要调查的路由或服务");

        subscriber.await(event -> event.type() == TaskEventType.CLARIFICATION_REQUIRED);
        assertEquals("请指明要调查的路由或服务", subscriber.payloadValue("clarification"));
        // 澄清不是终态：晚连上的订阅者从快照读到 WAITING_INPUT，任务本身仍可被继续提问。
        Recording reconnected = new Recording();
        task.subscribe(reconnected);
        reconnected.await(event -> event.type() == TaskEventType.SNAPSHOT);
        assertEquals(TaskStatus.WAITING_INPUT, reconnected.snapshot().task().status());

        Recording failing = new Recording();
        task.subscribe(failing);
        task.fail("诊断任务执行失败");
        failing.awaitTerminal();
        assertEquals(TaskEventType.TASK_FAILED, failing.last().type());
        assertEquals("诊断任务执行失败", failing.payloadValue("error"));
    }

    /** 任务落定只上报一次指标：终态不可逆，重复上报会把活跃数减成负数、耗时也会重复计入。 */
    @Test
    void terminalStateIsReportedToMetricsExactlyOnce() {
        List<String> settled = new CopyOnWriteArrayList<>();
        AgentMetrics recording = new AgentMetrics() {
            @Override
            public void taskSettled(String status, long durationMillis) {
                settled.add(status + ":" + (durationMillis >= 0));
            }
        };
        InvestigationTask measured = new InvestigationTask("task-metrics", "session-1", "为什么失败？",
                view -> { }, bus, recording, new InMemoryAgentCheckpointRepository());

        measured.start();
        measured.complete(report());
        measured.fail("重复终态不应再次上报");

        assertEquals(List.of("COMPLETED:true"), settled);
    }

    @Test
    void cancelledSubscriptionStopsReceivingEvents() {
        Recording subscriber = new Recording();
        TaskEventSubscription subscription = task.subscribe(subscriber);
        subscription.cancel();
        task.start();
        task.appendAnalysis("不该收到");
        task.complete(report());

        // 退订即停止投递：退订瞬间可能还在队列里的补发快照要么已投出、要么随队列一起丢弃，
        // 但此后任何状态变更都不会再送到这个订阅者。
        sleep();
        assertTrue(subscriber.types().stream().allMatch(type -> type == TaskEventType.SNAPSHOT),
                "退订后不应再收到事件：" + subscriber.types());
    }

    private static void sleep() {
        try {
            Thread.sleep(300L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static InvestigationReport report() {
        List<Evidence> evidence = List.of(Evidence.of("task-1", EvidenceType.ROUTE, "Gateway 路由表", "路由快照",
                "路由 /api/hello 未命中", "/api/routes", 1L));
        return new InvestigationReport("建议优先排查路由配置", Confidence.MEDIUM, evidence, List.of(), List.of(),
                "解读文本");
    }

    /** 记录订阅到的事件；等待条件用轮询实现，避免测试依赖固定睡眠。 */
    private static final class Recording implements TaskEventSubscriber {

        private final List<TaskEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onEvent(TaskEvent event) {
            events.add(event);
        }

        private List<TaskEventType> types() {
            return events.stream().map(TaskEvent::type).toList();
        }

        private TaskEvent last() {
            return events.get(events.size() - 1);
        }

        private TaskSnapshot snapshot() {
            return (TaskSnapshot) events.stream().filter(event -> event.type() == TaskEventType.SNAPSHOT)
                    .findFirst().orElseThrow().payload();
        }

        private List<String> payloadValues(String key) {
            return events.stream().map(event -> payloadValue(event, key)).filter(value -> value != null).toList();
        }

        private String payloadValue(String key) {
            return payloadValue(last(), key);
        }

        private static String payloadValue(TaskEvent event, String key) {
            if (event.payload() instanceof Map<?, ?> payload && payload.get(key) instanceof String value) {
                return value;
            }
            return null;
        }

        private void awaitTerminal() {
            await(event -> event.type().terminal());
        }

        private void await(Predicate<TaskEvent> condition) {
            long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
            while (events.stream().noneMatch(condition) && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            assertTrue(events.stream().anyMatch(condition), "等待事件超时：" + types());
        }
    }
}