package com.rover.agent.runtime.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.journal.ResourceNotes;
import com.rover.agent.core.model.AgentMessage;
import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.Confidence;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.EvidenceType;
import com.rover.agent.core.model.Hypothesis;
import com.rover.agent.core.model.Incident;
import com.rover.agent.core.model.IncidentOrigin;
import com.rover.agent.core.model.IncidentStatus;
import com.rover.agent.core.model.InvestigationReport;
import com.rover.agent.core.model.MessageRole;
import com.rover.agent.core.model.RecallChoice;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.SessionStatus;
import com.rover.agent.core.model.Step;
import com.rover.agent.core.model.StepStatus;
import com.rover.agent.core.model.TargetType;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.core.model.TaskType;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.core.model.Verdict;
import com.rover.agent.core.planning.InvestigationPlan;
import com.rover.agent.core.planning.PlannedStep;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Agent 聚合链的关系型持久化。
 *
 * <p>P3.1 的验收标准就落在第一条用例上：<b>Admin 正常退出并重启后，会话、消息、事件、任务、步骤、证据
 * 全部读得回来，且与重启前逐字段一致</b>——UI 重新打开一个历史会话看到的就是同一份事实。
 *
 * <p>其余用例守的是这次一并定下来的语义：一次快照一次事务、外键不留孤儿、查询下推到 SQL、
 * 重启中断只改状态、迁移可重跑。
 */
class JdbcAgentStoreTest {

    @Test
    void restartKeepsTheWholeSessionIncidentTaskChain() throws Exception {
        String path = tempPath("rover-agent-restart");
        Incident incident = new Incident("i1", "s1", IncidentOrigin.USER, "/api/order", IncidentStatus.INVESTIGATING,
                ResourceTarget.route("/api/order"), new TimeRange(100L, 200L), "", 30L, 40L, List.of());
        List<AgentMessage> messages = List.of(
                new AgentMessage("m1", "s1", MessageRole.USER, "为什么 /api/order 失败？", 11L, "t1"),
                new AgentMessage("m2", "s1", MessageRole.AGENT, "上游实例返回 5xx。", 12L, "t1"));
        TaskView task = completedTask();

        try (JdbcAgentStore first = new JdbcAgentStore(path)) {
            openSession(first, session("s1"), incident);
            messages.forEach(message -> first.messages().save(message));
            first.tasks().save(task);
            first.saveNote(ResourceNotes.capture(task, 500L));
        }

        try (JdbcAgentStore second = new JdbcAgentStore(path)) {
            // 任务视图逐字段一致：步骤顺序、证据、统计口径、计划、假设、已用能力与回顾卡片都在。
            assertEquals(task, second.tasks().find("t1").orElseThrow());
            assertEquals(List.of("i1"), second.sessions().find("s1").orElseThrow().incidentIds(),
                    "会话的事件列表由事件表派生，不是另存的一份冗余");
            assertEquals("alice", second.sessions().find("s1").orElseThrow().userId());
            assertEquals("i1", second.sessions().find("s1").orElseThrow().activeIncidentId());

            // 事件本身一致，任务列表由任务表派生（挂在事件上的任务不会丢）。
            Incident reloaded = second.incidents().find("i1").orElseThrow();
            assertEquals(incident.origin(), reloaded.origin());
            assertEquals(incident.timeRange(), reloaded.timeRange());
            assertEquals(ResourceTarget.route("/api/order"), reloaded.target());
            assertEquals(List.of("t1"), reloaded.taskIds());

            assertEquals(messages, second.messages().bySession("s1"));
            assertEquals("某个上游实例返回 5xx", second.findNote("/api/order").orElseThrow().rootCause(),
                    "资源笔记按资源键存下来，根因取自被证据确认的那条假设");
        }
    }

    @Test
    void restartMarksInFlightTasksInterruptedButKeepsTheirSteps() throws Exception {
        String path = tempPath("rover-agent-interrupt");
        Step running = new Step("st1", "t1", AgentStepType.ROUTE_INVESTIGATION, "读取路由", StepStatus.RUNNING,
                "查 /api/order", null, 50L, 0L, null);
        TaskView inFlight = new TaskView("t1", "s1", "i1", TaskStatus.RUNNING, AgentStepType.ROUTE_INVESTIGATION,
                "/api/order", ResourceTarget.route("/api/order"), "还在查", 80L, 0L, List.of(running), null, null,
                null);
        TaskView waiting = new TaskView("t2", "s1", "i1", TaskStatus.WAITING_INPUT, AgentStepType.TARGET_RESOLUTION,
                "", ResourceTarget.unknown(), "现在什么状态", 81L, 0L, List.of(), null, null, "请补充对象");

        try (JdbcAgentStore first = new JdbcAgentStore(path)) {
            openSession(first, session("s1"), incident("i1", "s1"));
            first.tasks().save(inFlight);
            first.tasks().save(waiting);
        }

        try (JdbcAgentStore second = new JdbcAgentStore(path)) {
            TaskView interrupted = second.tasks().find("t1").orElseThrow();
            assertEquals(TaskStatus.INTERRUPTED, interrupted.status(),
                    "重启中断不是失败：已有事实还在，可以按恢复点接着跑");
            assertTrue(interrupted.error().contains("重启"), "中断原因要说清是重启，而不是含糊的失败");
            assertTrue(interrupted.completedAtMillis() > 0, "中断也是一次终态，完成时间要落下");
            assertEquals(List.of(running), interrupted.steps(), "中断只改状态，过程记录保持原样");

            // 停在澄清点的任务没有线程在跑，重启不该把它判成失败。
            assertEquals(TaskStatus.WAITING_INPUT, second.tasks().find("t2").orElseThrow().status());
            assertEquals("请补充对象", second.tasks().find("t2").orElseThrow().clarification());
        }
    }

    @Test
    void saveRollsBackTheWholeAggregateWhenAnyPartFails() throws Exception {
        String path = tempPath("rover-agent-atomic");
        Step broken = new Step("st2", "t1", null, "坏步骤", StepStatus.RUNNING, null, null, 1L, 0L, null);
        TaskView task = new TaskView("t1", "s1", null, TaskStatus.RUNNING, null, "", ResourceTarget.unknown(),
                "有问题", 1L, 0L, List.of(step("st1"), broken), null, null, null);

        try (JdbcAgentStore store = new JdbcAgentStore(path)) {
            store.sessions().save(session("s1"));
            assertThrows(RuntimeException.class, () -> store.tasks().save(task));
            // 任务行先写、步骤后写：失败必须整组回滚，不能留下"任务在、步骤残缺"的半个聚合。
            assertTrue(store.tasks().find("t1").isEmpty(), "写入中途失败必须整组回滚");
        }
    }

    @Test
    void deletingASessionRemovesItsIncidentsMessagesAndTasks() throws Exception {
        String path = tempPath("rover-agent-cascade");
        try (JdbcAgentStore store = new JdbcAgentStore(path)) {
            store.sessions().save(session("s1"));
            store.incidents().save(incident("i1", "s1"));
            store.messages().save(new AgentMessage("m1", "s1", MessageRole.USER, "在吗", 1L, null));
            store.tasks().save(completedTask());

            store.sessions().remove("s1");

            assertTrue(store.sessions().find("s1").isEmpty());
            assertTrue(store.incidents().find("i1").isEmpty(), "会话删了，它的事件不该留下");
            assertTrue(store.tasks().find("t1").isEmpty(), "会话删了，它的任务不该留下");
            assertTrue(store.messages().bySession("s1").isEmpty());
        }
    }

    @Test
    void deletingAnIncidentLeavesNoDanglingReference() throws Exception {
        String path = tempPath("rover-agent-dangling");
        try (JdbcAgentStore store = new JdbcAgentStore(path)) {
            store.sessions().save(session("s1"));
            store.incidents().save(incident("i1", "s1"));
            store.tasks().save(completedTask());

            store.incidents().remove("i1");

            Session reloaded = store.sessions().find("s1").orElseThrow();
            assertNull(reloaded.activeIncidentId(), "当前事件指针不能指向一个已经消失的事件");
            assertTrue(reloaded.incidentIds().isEmpty());
            TaskView task = store.tasks().find("t1").orElseThrow();
            assertNull(task.incidentId(), "任务不再挂着已删除的事件，但任务本身还在");
            assertEquals(TaskStatus.COMPLETED, task.status());
        }
    }

    @Test
    void queriesArePushedDownToSql() throws Exception {
        String path = tempPath("rover-agent-queries");
        try (JdbcAgentStore store = new JdbcAgentStore(path)) {
            store.sessions().save(session("s1"));
            store.incidents().save(incident("i1", "s1"));
            store.incidents().save(incident("i2", "s1"));
            store.tasks().save(task("t1", "i1", TaskStatus.COMPLETED, 100L));
            store.tasks().save(task("t2", "i1", TaskStatus.COMPLETED, 200L));
            store.tasks().save(task("t3", "i2", TaskStatus.PENDING, 300L));
            store.tasks().save(task("t4", "i2", TaskStatus.WAITING_INPUT, 400L));

            assertEquals(List.of("t1", "t2"), store.tasks().findByIncidentId("i1").stream()
                    .map(TaskView::taskId).toList());
            assertEquals(List.of("t3"), store.tasks().findByIncidentId("i2").stream()
                    .filter(view -> view.status().active()).map(TaskView::taskId).toList());
            assertEquals("t3", store.tasks().findActiveBySessionId("s1").orElseThrow().taskId(),
                    "澄清点不占并发位：活跃任务应当跳过 WAITING_INPUT");
            assertEquals(List.of("t4", "t3"), store.tasks().recentBySession("s1", 2).stream()
                    .map(TaskView::taskId).toList());
            assertEquals(2, store.tasks().removeByIncident("i1"));
            assertTrue(store.tasks().find("t1").isEmpty());
            assertEquals(0, store.tasks().removeByIncident("i1"), "删干净之后再删没有可删的");

            store.tasks().removeBySession("s1");
            assertTrue(store.tasks().findBySessionId("s1").isEmpty());
            assertEquals(2, store.incidents().removeBySession("s1"));
            assertTrue(store.incidents().bySession("s1").isEmpty());
        }
    }

    @Test
    void messageOrderFollowsInsertionNotTimestamp() throws Exception {
        String path = tempPath("rover-agent-messages");
        try (JdbcAgentStore store = new JdbcAgentStore(path)) {
            store.sessions().save(session("s1"));
            // 时间戳刻意倒挂：窗口清理与"最早一条"必须按写入顺序，而不是按对方机器上不可信的时间。
            store.messages().save(new AgentMessage("m1", "s1", MessageRole.USER, "第一句", 900L, null));
            store.messages().save(new AgentMessage("m2", "s1", MessageRole.AGENT, "第二句", 100L, null));

            assertEquals(List.of("m1", "m2"), store.messages().bySession("s1").stream()
                    .map(AgentMessage::messageId).toList());
            assertEquals("m1", store.messages().oldest().orElseThrow().messageId());
            assertEquals(2, store.messages().listAll().size());
        }
    }

    @Test
    void migrationIsReapplicableAfterLedgerLoss() throws Exception {
        String path = tempPath("rover-agent-migration");
        TaskView task = completedTask();
        try (JdbcAgentStore store = new JdbcAgentStore(path)) {
            openSession(store, session("s1"), incident("i1", "s1"));
            store.tasks().save(task);
            assertEquals(3, store.schemaVersion(), "v1 建聚合表，v2 清理旧 JSON 快照表，v3 加安全恢复点");
        }

        // 模拟"迁移记录丢了但表还在"：MySQL 的 DDL 不能回滚，重跑必须照样通过。
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:file:" + path + ";DB_CLOSE_DELAY=0;AUTO_SERVER=TRUE", "sa", "");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM agent_schema_migrations");
        }

        try (JdbcAgentStore store = new JdbcAgentStore(path)) {
            assertEquals(3, store.schemaVersion());
            assertEquals(task, store.tasks().find("t1").orElseThrow(), "重跑迁移不能动已有数据");
        }
    }

    private static String tempPath(String prefix) throws Exception {
        return Files.createTempDirectory(prefix).resolve("rover").toString();
    }

    /**
     * 按生产顺序建立"会话 + 当前事件"：事件先落库，再回填会话的当前事件指针。
     *
     * 顺序不是随意的：当前事件指针是指向 {@code agent_incident} 的外键，
     * 先写指针就会引用一个还不存在的事件——这也正是"会话不可能指向一个不存在的事件"的由来。
     */
    private static void openSession(JdbcAgentStore store, Session session, Incident incident) {
        store.sessions().save(session);
        store.incidents().save(incident);
        store.sessions().save(new Session(session.sessionId(), session.userId(), session.title(),
                incident.incidentId(), session.status(), session.createdAtMillis(), session.lastActiveAtMillis(),
                List.of()));
    }

    private static Session session(String sessionId) {
        return new Session(sessionId, "alice", "标题", null, SessionStatus.ACTIVE, 10L, 20L, List.of());
    }

    private static Incident incident(String incidentId, String sessionId) {
        return new Incident(incidentId, sessionId, IncidentOrigin.USER, "/api/order", IncidentStatus.OPEN,
                ResourceTarget.route("/api/order"), TimeRange.unspecified(), "", 30L, 40L, List.of());
    }

    private static Step step(String stepId) {
        return new Step(stepId, "t1", AgentStepType.PLANNING, "制定计划", StepStatus.COMPLETED, "定计划", "查三项",
                50L, 60L, null);
    }

    private static TaskView task(String taskId, String incidentId, TaskStatus status, long createdAt) {
        return new TaskView(taskId, "s1", incidentId, status, null, "", ResourceTarget.unknown(), "问题",
                createdAt, 0L, List.of(), null, null, null);
    }

    /** 一份内容填满的任务：报告、证据、假设、计划、已用能力与回顾卡片都非空。 */
    private static TaskView completedTask() {
        Evidence evidence = Evidence.of("t1", EvidenceType.METRIC, "Gateway 实时指标", "路由窗口观测",
                "窗口内 12 次 5xx", "/api/metrics/route", Map.of("windowSeconds", "60", "sampleSize", "120"), 70L);
        InvestigationReport report = new InvestigationReport("上游实例返回 5xx", Confidence.HIGH,
                List.of(evidence), List.of("样本窗口只有 60 秒"),
                List.of(new Hypothesis("H1", "某个上游实例返回 5xx", Verdict.CONFIRMED, "12 条 5xx",
                        List.of("Gateway 实时指标"))), "AI 解读正文");
        InvestigationPlan plan = new InvestigationPlan("定位 /api/order 的问题原因", List.of("上游 5xx"),
                List.of(new PlannedStep(AgentCapability.GATEWAY_METRICS_QUERY, "确认窗口流量",
                        ResourceTarget.route("/api/order"), false)));
        return new TaskView("t1", "s1", "i1", TaskStatus.COMPLETED, AgentStepType.ANSWER, "/api/order",
                ResourceTarget.route("/api/order"), "为什么失败？", 80L, 900L,
                List.of(step("st1"), new Step("st2", "t1", AgentStepType.METRIC_INVESTIGATION, "读取指标",
                        StepStatus.COMPLETED, null, "5xx=12", 61L, 70L, null)),
                report, null, null, TaskType.INVESTIGATION, plan,
                List.of(AgentCapability.ROUTE_QUERY, AgentCapability.GATEWAY_METRICS_QUERY),
                List.of(new RecallChoice("昨天那场", "s0")), List.of(evidence));
    }

    @Test
    void targetTypeSurvivesAsColumnsNotAsAString() throws Exception {
        String path = tempPath("rover-agent-target");
        try (JdbcAgentStore store = new JdbcAgentStore(path)) {
            store.sessions().save(session("s1"));
            store.tasks().save(new TaskView("t1", "s1", null, TaskStatus.PENDING, null, "",
                    ResourceTarget.instance("10.0.0.1:8080"), "这台实例健康吗", 1L, 0L, List.of(), null, null, null));

            ResourceTarget reloaded = store.tasks().find("t1").orElseThrow().target();
            assertEquals(TargetType.INSTANCE, reloaded.type());
            assertEquals("10.0.0.1:8080", reloaded.value());
            assertFalse(store.tasks().findByIncidentId("none").iterator().hasNext());
        }
    }
}
