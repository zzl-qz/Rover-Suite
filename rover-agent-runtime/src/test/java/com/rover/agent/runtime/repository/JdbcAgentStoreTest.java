package com.rover.agent.runtime.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.journal.ResourceNotes;
import com.rover.agent.core.model.AgentMessage;
import com.rover.agent.core.model.ConfigActionIntent;
import com.rover.agent.core.model.ConfigActionApproval;
import com.rover.agent.core.model.ConfigActionStatus;
import com.rover.agent.core.model.GatewayConfigAction;
import com.rover.agent.core.model.KnowledgeDocumentVersion;
import com.rover.agent.core.model.ResourceScope;
import com.rover.agent.core.model.AlertDelivery;
import com.rover.agent.core.model.AutoExecutionPolicy;
import com.rover.agent.core.model.AlertEventState;
import com.rover.agent.core.model.AlertLifecycle;
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
import com.rover.agent.runtime.task.IncidentRegistry;
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
    void configActionIntentAndUnknownStateSurviveRestartAndCannotBeReplayedAsPending() throws Exception {
        String path = tempPath("rover-config-action");
        ResourceScope scope = new ResourceScope("org", "team", "test");
        ConfigActionIntent intent = new ConfigActionIntent(ConfigActionIntent.ALLOWED_KEY, "0.0", "1",
                scope, "gateway-test", "namespace-test");
        long now = System.currentTimeMillis();
        String policy = "gateway-config-approval-v1";
        List<ConfigActionApproval> approvals = List.of(
                new ConfigActionApproval("reviewer-a", intent.intentHash(), policy, now),
                new ConfigActionApproval("reviewer-b", intent.intentHash(), policy, now + 1));
        GatewayConfigAction action = new GatewayConfigAction("cfg-action-1", "s1", null, null, "alice",
                intent, intent.intentHash(), policy, approvals, "agent-op-1", "cfg-parent",
                ConfigActionStatus.QUEUED, null, now, now + 1, now + 60_000, 0);
        try (JdbcAgentStore first = new JdbcAgentStore(path)) {
            first.sessions().save(session("s1"));
            first.gatewayConfigActions().save(action);
            assertTrue(first.gatewayConfigActions().hasNonTerminalForSession("s1"));
            assertThrows(IllegalStateException.class, () -> first.sessions().remove("s1"));
            assertTrue(first.gatewayConfigActions().find(action.actionId()).isPresent());
            assertThrows(IllegalArgumentException.class, () -> first.gatewayConfigActions().transition(
                    "cfg-action-1", ConfigActionStatus.QUEUED, current ->
                            new GatewayConfigAction(current.actionId(), current.sessionId(), current.incidentId(),
                                    current.taskId(), current.requestedBy(), current.intent(), current.intentHash(),
                                    current.policyVersion(), current.approvals(), current.operationId(),
                                    ConfigActionStatus.EXECUTING, null, current.createdAtMillis(), now + 1,
                                    current.expiresAtMillis() + 1, current.version())));
            assertTrue(first.gatewayConfigActions().transition("cfg-action-1", ConfigActionStatus.QUEUED,
                    current -> current.withStatus(ConfigActionStatus.EXECUTING, null, now + 2)).isPresent());
            assertTrue(first.gatewayConfigActions().transition("cfg-action-1", ConfigActionStatus.EXECUTING,
                    current -> current.withStatus(ConfigActionStatus.UNKNOWN, "Gateway 回执待人工对账", now + 3)).isPresent());
        }
        try (JdbcAgentStore reopened = new JdbcAgentStore(path)) {
            GatewayConfigAction restored = reopened.gatewayConfigActions().find("cfg-action-1").orElseThrow();
            assertEquals(intent, restored.intent());
            assertEquals(intent.intentHash(), restored.intentHash());
            assertEquals("agent-op-1", restored.operationId());
            assertEquals("cfg-parent", restored.compensatesActionId());
            assertEquals(action.actionId(), reopened.gatewayConfigActions().findCompensation("cfg-parent").orElseThrow().actionId());
            assertEquals(ConfigActionStatus.UNKNOWN, restored.status());
            assertTrue(reopened.gatewayConfigActions().hasNonTerminalForSession("s1"));
            assertTrue(reopened.gatewayConfigActions().byStatus(java.util.Set.of(ConfigActionStatus.UNKNOWN), 10)
                    .contains(restored));
        }
    }

    @Test
    void versionedKnowledgeRequiresReviewKeepsScopeAndRevocationAcrossRestart() throws Exception {
        String path = tempPath("rover-knowledge");
        ResourceScope dev = new ResourceScope("org", "team", "dev");
        ResourceScope prod = new ResourceScope("org", "team", "prod");
        long expiry = System.currentTimeMillis() + 60_000;
        try (JdbcAgentStore first = new JdbcAgentStore(path)) {
            var repository = first.knowledgeDocuments();
            var draft = repository.createDraft("runbook", dev, "Route failure", "check gateway route",
                    List.of("route"), "git:1", "author", expiry);
            repository.createDraft("runbook-prod", prod, "Production secret", "prod content",
                    List.of("prod"), "git:secret", "author", expiry);
            assertTrue(repository.approved(dev, System.currentTimeMillis(), 10).isEmpty());
            assertEquals(1, repository.list(dev).size());
            var approved = repository.transition(draft.documentId(), draft.version(), KnowledgeDocumentVersion.Status.DRAFT,
                    value -> value.approve("reviewer", System.currentTimeMillis())).orElseThrow();
            assertEquals("reviewer", approved.reviewedBy());
            assertEquals(1, repository.approved(dev, System.currentTimeMillis(), 10).size());
            assertTrue(repository.approved(prod, System.currentTimeMillis(), 10).isEmpty());
            assertThrows(IllegalStateException.class, () -> repository.createDraft("runbook", prod, "cross-scope",
                    "must not reuse id across scopes", List.of(), "git:other", "author", expiry));
            assertThrows(IllegalArgumentException.class, () -> repository.createDraft("expired", dev, "expired",
                    "must not create already-expired draft", List.of(), "git:expired", "author", System.currentTimeMillis()-1));
            assertThrows(IllegalStateException.class, () -> approved.approve("late-reviewer", approved.expiresAtMillis()));
            assertEquals(2, repository.createDraft("runbook", dev, "next draft",
                    "a draft can be prepared while the current approved version remains searchable",
                    List.of(), "git:2", "author", System.currentTimeMillis()+60_000).version());
        }
        try (JdbcAgentStore reopened = new JdbcAgentStore(path)) {
            var repository = reopened.knowledgeDocuments();
            assertEquals(KnowledgeDocumentVersion.Status.APPROVED, repository.find("runbook", 1).orElseThrow().status());
            repository.transition("runbook", 1, KnowledgeDocumentVersion.Status.APPROVED,
                    value -> value.revoke("security", System.currentTimeMillis())).orElseThrow();
            assertTrue(repository.approved(dev, System.currentTimeMillis(), 10).isEmpty());
            assertTrue(repository.approved(dev, System.currentTimeMillis(), 10).isEmpty());
            assertThrows(IllegalStateException.class, () -> repository.createDraft("runbook", prod, "bad",
                    "cross scope id reuse", List.of(), "git:2", "author", System.currentTimeMillis()+60_000));
            assertEquals(2, repository.find("runbook", 2).orElseThrow().version());
        }
    }

    @Test
    void configActionIntentRejectsTamperedHashAndUnregisteredConfigValues() throws Exception {
        ResourceScope scope = new ResourceScope("org", "team", "test");
        ConfigActionIntent intent = new ConfigActionIntent(ConfigActionIntent.ALLOWED_KEY, "0.0", "1", scope,
                "gateway-test", "namespace-test");
        long now = System.currentTimeMillis();
        assertThrows(IllegalArgumentException.class, () -> new ConfigActionIntent("gateway.adminToken", "0", "1",
                scope, "gateway-test", "namespace-test"));
        assertThrows(IllegalArgumentException.class, () -> new GatewayConfigAction("cfg-tamper", "s1", null, null,
                "alice", intent, "0".repeat(64), "gateway-config-approval-v1", List.of(), "agent-op-tamper",
                ConfigActionStatus.PENDING_APPROVAL, null, now, now, now + 60_000, 0));
    }

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
            assertEquals(21, store.schemaVersion(),
                    "v1 建聚合表，v2 清理旧 JSON 快照表，v3 加安全恢复点，v4 加受控变更记录，"
                            + "v5 保存请求数值与单位，v6 独立保存告警来源主体，v12 保存双人审批凭证，"
                            + "v13 保存按管理目标绑定的持久动作命令");
        }

        // 模拟"迁移记录丢了但表还在"：MySQL 的 DDL 不能回滚，重跑必须照样通过。
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:file:" + path + ";DB_CLOSE_DELAY=0;AUTO_SERVER=TRUE", "sa", "");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM agent_schema_migrations");
        }

        try (JdbcAgentStore store = new JdbcAgentStore(path)) {
            assertEquals(21, store.schemaVersion());
            assertEquals(task, store.tasks().find("t1").orElseThrow(), "重跑迁移不能动已有数据");
        }
    }

    private static String tempPath(String prefix) throws Exception {
        return Files.createTempDirectory(prefix).resolve("rover").toString();
    }

    @Test
    void authenticatedAlertSourceSurvivesUpdatesAndRestartSeparatelyFromItsHandler() throws Exception {
        String path = tempPath("rover-alert-source");
        String incidentId;
        try (JdbcAgentStore first = new JdbcAgentStore(path)) {
            first.sessions().save(session("s1"));
            IncidentRegistry registry = new IncidentRegistry(first.sessions(), first.incidents());
            Incident alert = registry.openIncident("s1", IncidentOrigin.ALERT, ResourceTarget.route("/api/order"),
                    new TimeRange(100, 200), "alerts-service");
            incidentId = alert.incidentId();
            registry.updateTimeRange(incidentId, new TimeRange(120, 180));
            registry.attachTask(incidentId, "not-persisted-task");
            registry.summarise(incidentId, "该时段证据不足");
        }
        try (JdbcAgentStore reopened = new JdbcAgentStore(path)) {
            Incident alert = reopened.incidents().find(incidentId).orElseThrow();
            assertEquals("alerts-service", alert.triggeredBy());
            assertEquals(new TimeRange(120, 180), alert.timeRange());
            assertEquals("alice", reopened.sessions().find(alert.sessionId()).orElseThrow().userId());
            assertEquals("该时段证据不足", alert.summary());
        }
    }

    @Test
    void alertDeliveryReceiptsAndLifecycleSurviveRestartAndTransactionsRollBack() throws Exception {
        String path = tempPath("rover-alert-lifecycle");
        AlertDelivery receipt = new AlertDelivery("monitor-a", "delivery-1", "route-errors",
                AlertEventState.FIRING, "a".repeat(64), 1, "s1", "i1", "t1", 100L);
        AlertLifecycle lifecycle = new AlertLifecycle("monitor-a", "route-errors", true, "/api/order",
                1, "s1", "i1", "t1", AlertEventState.FIRING, "b".repeat(64), 101L);
        try (JdbcAgentStore first = new JdbcAgentStore(path)) {
            first.alertLifecycles().transaction(tx -> {
                assertTrue(tx.saveDelivery(receipt));
                tx.saveLifecycle(lifecycle);
                return null;
            });
        }
        try (JdbcAgentStore reopened = new JdbcAgentStore(path)) {
            assertEquals(receipt, reopened.alertLifecycles().transaction(tx ->
                    tx.delivery("monitor-a", "delivery-1").orElseThrow()));
            assertEquals(lifecycle, reopened.alertLifecycles().transaction(tx ->
                    tx.lifecycle("monitor-a", "route-errors").orElseThrow()));
            assertThrows(IllegalStateException.class, () -> reopened.alertLifecycles().transaction(tx -> {
                tx.saveLifecycle(new AlertLifecycle("monitor-a", "route-errors", false, "/api/order",
                        1, "s1", "i1", null, AlertEventState.RESOLVED, "c".repeat(64), 102L));
                throw new IllegalStateException("rollback");
            }));
            assertEquals(lifecycle, reopened.alertLifecycles().transaction(tx ->
                    tx.lifecycle("monitor-a", "route-errors").orElseThrow()));
        }
    }

    @Test
    void autonomousPoliciesAreVersionedAuditedAndSurviveRestart() throws Exception {
        String path = tempPath("rover-auto-policy");
        var scope = new com.rover.agent.core.model.ResourceScope("org", "team", "prod");
        var resource = new com.rover.agent.core.model.ResourceRef(scope, "gateway", "GATEWAY_ROUTE", "orders", "uid-1", 1);
        AutoExecutionPolicy policy = AutoExecutionPolicy.create("policy-1", "namespace-a", resource,
                "orders", "v2", 5, 5, System.currentTimeMillis() + 60_000, "admin", System.currentTimeMillis());
        try (JdbcAgentStore first = new JdbcAgentStore(path)) {
            first.autoExecutionPolicies().save(policy, 0);
            var revoked = policy.revise(false, 5, 5, policy.expiresAtMillis(), "admin", System.currentTimeMillis());
            first.autoExecutionPolicies().save(revoked, 1);
            assertEquals(2, first.autoExecutionPolicies().history("policy-1").size());
            var audits = first.audit("namespace-a", 0, 10);
            assertEquals("AUTO_POLICY_VERSIONED", audits.get(audits.size() - 1).kind());
            assertThrows(IllegalStateException.class, () -> first.autoExecutionPolicies().save(
                    revoked.revise(true, 5, 5, revoked.expiresAtMillis(), "admin", System.currentTimeMillis()), 1));
        }
        try (JdbcAgentStore reopened = new JdbcAgentStore(path)) {
            assertEquals(2, reopened.autoExecutionPolicies().find("policy-1").orElseThrow().version());
            assertFalse(reopened.autoExecutionPolicies().find("policy-1").orElseThrow().enabled());
            assertTrue(reopened.autoExecutionPolicies().find("policy-1", "other-namespace").isEmpty());
            assertEquals(2, reopened.autoExecutionPolicies().history("policy-1").size());
        }
    }

    @Test
    void autonomousBudgetKeepsUnknownLocksAcrossRestartAndEnforcesCumulativeLimit() throws Exception {
        String path = tempPath("rover-action-budget");
        long day = java.time.Duration.ofHours(24).toMillis();
        long cooldown = java.time.Duration.ofMinutes(15).toMillis();
        try (JdbcAgentStore first = new JdbcAgentStore(path)) {
            var budget = first.autonomousActionBudget();
            assertEquals(com.rover.agent.core.repository.AutonomousActionBudgetRepository.Reservation.RESERVED,
                    budget.reserve("resource", "action-1", "action-1A", 6, 10, day, cooldown, -1, true, 1_000));
            assertEquals(com.rover.agent.core.repository.AutonomousActionBudgetRepository.Reservation.RESOURCE_BUSY,
                    budget.reserve("resource", "action-2", "action-2A", 1, 10, day, cooldown, -1, true, 1_001));
            budget.complete("action-1A", com.rover.agent.core.repository.AutonomousActionBudgetRepository.Outcome.UNKNOWN, -1, 1_002);
        }
        try (JdbcAgentStore reopened = new JdbcAgentStore(path)) {
            var budget = reopened.autonomousActionBudget();
            assertEquals(com.rover.agent.core.repository.AutonomousActionBudgetRepository.Reservation.RESOURCE_BUSY,
                    budget.reserve("resource", "action-2", "action-2A", 1, 10, day, cooldown, -1, true, 1_003),
                    "未知结果必须在重启后继续锁住资源");
            budget.complete("action-1A", com.rover.agent.core.repository.AutonomousActionBudgetRepository.Outcome.APPLIED, -1, 1_004);
            assertEquals(com.rover.agent.core.repository.AutonomousActionBudgetRepository.Reservation.COOLDOWN_ACTIVE,
                    budget.reserve("resource", "action-2", "action-2A", 1, 10, day, cooldown, 1, true, 1_005));
            assertEquals(com.rover.agent.core.repository.AutonomousActionBudgetRepository.Reservation.BUDGET_EXCEEDED,
                    budget.reserve("resource", "action-2", "action-2A", 5, 10, day, 0, 1, true, 1_000 + cooldown + 10));
        }
    }

    @Test
    void autonomousRollingBudgetDoesNotResetAtWindowBoundary() throws Exception {
        try (JdbcAgentStore store = new JdbcAgentStore(tempPath("rover-action-rolling-window"))) {
            var budget = store.autonomousActionBudget();
            long window = java.time.Duration.ofHours(24).toMillis();
            long start = 5_000_000L;
            assertEquals(com.rover.agent.core.repository.AutonomousActionBudgetRepository.Reservation.RESERVED,
                    budget.reserve("resource", "seed", "seed", 0, 10, window, 0, 0, false, start));
            budget.complete("seed", com.rover.agent.core.repository.AutonomousActionBudgetRepository.Outcome.NOT_APPLIED, 0, start);
            assertEquals(com.rover.agent.core.repository.AutonomousActionBudgetRepository.Reservation.RESERVED,
                    budget.reserve("resource", "a1", "a1-apply", 7, 10, window, 0, -1, true, start + window * 23 / 24));
            budget.complete("a1-apply", com.rover.agent.core.repository.AutonomousActionBudgetRepository.Outcome.APPLIED, -1, start + window * 23 / 24);
            assertEquals(com.rover.agent.core.repository.AutonomousActionBudgetRepository.Reservation.RESERVED,
                    budget.reserve("resource", "a2", "a2-apply", 2, 10, window, 0, -1, true, start + window * 23 / 24 + 1));
            budget.complete("a2-apply", com.rover.agent.core.repository.AutonomousActionBudgetRepository.Outcome.APPLIED, -1, start + window * 23 / 24 + 1);
            assertEquals(com.rover.agent.core.repository.AutonomousActionBudgetRepository.Reservation.BUDGET_EXCEEDED,
                    budget.reserve("resource", "a3", "a3-apply", 2, 10, window, 0, -1, true, start + window + 1),
                    "滚动窗口边界不应把上一笔尚在 24 小时内的影响清零");
        }
    }

    @Test
    void autonomousBudgetDetectsOscillationAndReleasesUnappliedReservation() throws Exception {
        try (JdbcAgentStore store = new JdbcAgentStore(tempPath("rover-action-oscillation"))) {
            var budget = store.autonomousActionBudget();
            long window = java.time.Duration.ofHours(24).toMillis();
            assertEquals(com.rover.agent.core.repository.AutonomousActionBudgetRepository.Reservation.RESERVED,
                    budget.reserve("resource", "a1", "a1", 1, 10, window, 0, -1, true, 1_000));
            budget.complete("a1", com.rover.agent.core.repository.AutonomousActionBudgetRepository.Outcome.APPLIED, -1, 1_001);
            assertEquals(com.rover.agent.core.repository.AutonomousActionBudgetRepository.Reservation.RESERVED,
                    budget.reserve("resource", "a2", "a2", 1, 10, window, 0, 1, true, 1_002));
            budget.complete("a2", com.rover.agent.core.repository.AutonomousActionBudgetRepository.Outcome.APPLIED, 1, 1_003);
            assertEquals(com.rover.agent.core.repository.AutonomousActionBudgetRepository.Reservation.OSCILLATION_DETECTED,
                    budget.reserve("resource", "a3", "a3", 1, 10, window, 0, -1, true, 1_004));
            assertEquals(com.rover.agent.core.repository.AutonomousActionBudgetRepository.Reservation.RESERVED,
                    budget.reserve("resource", "manual", "manual", 1, 100, window, 0, -1, false, 1_005));
            budget.complete("manual", com.rover.agent.core.repository.AutonomousActionBudgetRepository.Outcome.APPLIED, -1, 1_006);
            assertEquals(com.rover.agent.core.repository.AutonomousActionBudgetRepository.Reservation.OSCILLATION_DETECTED,
                    budget.reserve("resource", "a3", "a3", 1, 10, window, 0, -1, true, 1_007));
            assertEquals(com.rover.agent.core.repository.AutonomousActionBudgetRepository.Reservation.RESERVED,
                    budget.reserve("resource", "after-manual", "after-manual", 1, 10, window, 0, 1, true, 1_008),
                    "未生效的操作释放锁后，其他方向的自动动作可重新获取");
        }
    }

    @Test
    void shadowRunsAndVersionedCertificationsSurviveRestartAndKeepScopeIsolation() throws Exception {
        String path = tempPath("rover-shadow-certification");
        var scope = new com.rover.agent.core.model.ResourceScope("org", "team", "staging");
        var subject = new com.rover.agent.core.model.ActionCertificationSubject(scope,
                com.rover.agent.core.model.ActionType.ADJUST_ROUTE_TARGET_WEIGHT, "RULE_EVIDENCE_GATE",
                "route-weight-evidence-gate-v1", "route-weight-v1", "effect-v1", "query-v1", "gateway");
        var otherScope = new com.rover.agent.core.model.ResourceScope("org", "team", "prod");
        var otherSubject = new com.rover.agent.core.model.ActionCertificationSubject(otherScope,
                com.rover.agent.core.model.ActionType.ADJUST_ROUTE_TARGET_WEIGHT, "runbook", "1",
                "adapter-1", "effect-1", "query-1", "gateway");
        long otherNow = System.currentTimeMillis();
        var otherCert = new com.rover.agent.core.model.ActionCertification(otherSubject, 1,
                com.rover.agent.core.model.ActionCertification.State.ACTIVE, 3, "d".repeat(64), "admin",
                otherNow, otherNow + 60_000, "admin", otherNow);
        var run = new com.rover.agent.core.model.ActionShadowRun("shadow-1", subject, "action-1", 1,
                "a".repeat(64), com.rover.agent.core.model.ActionShadowRun.Outcome.PASS, "ok", 7,
                "b".repeat(64), System.currentTimeMillis());
        try (JdbcAgentStore store = new JdbcAgentStore(path)) {
            store.actionCertifications().saveShadow(run);
            var cert = new com.rover.agent.core.model.ActionCertification(subject, 1,
                    com.rover.agent.core.model.ActionCertification.State.ACTIVE, 3, "c".repeat(64), "admin",
                    System.currentTimeMillis(), System.currentTimeMillis() + 60_000, "admin", System.currentTimeMillis());
            store.actionCertifications().save(cert, 0);
            store.actionCertifications().save(otherCert, 0);
            assertEquals(List.of(cert), store.actionCertifications().current(scope),
                    "scope list must filter in SQL rather than return certifications from other environments");
            assertTrue(store.audit("3:org4:team7:staging", 0, 100).stream()
                    .anyMatch(a -> a.kind().equals("ACTION_CERTIFICATION_VERSIONED")));
        }
        try (JdbcAgentStore reopened = new JdbcAgentStore(path)) {
            assertEquals(List.of(run), reopened.actionCertifications().recentShadowRuns(subject.fingerprint(), 10));
            var cert = reopened.actionCertifications().current(subject.fingerprint()).orElseThrow();
            assertTrue(cert.current(subject, System.currentTimeMillis()));
            assertEquals(List.of(otherCert), reopened.actionCertifications().current(
                    otherScope));
            var revoked = cert.revoke("admin", System.currentTimeMillis());
            assertEquals(revoked, reopened.actionCertifications().save(revoked, 1));
        }
    }

    @Test
    void schemaV17UpgradesToV18AndCertificationAuditIsAtomicWithRecord() throws Exception {
        String path = tempPath("rover-shadow-v17-upgrade");
        try (JdbcAgentStore first = new JdbcAgentStore(path)) {
            assertEquals(21, first.schemaVersion());
        }
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:file:" + path + ";DB_CLOSE_DELAY=0;AUTO_SERVER=TRUE", "sa", "");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM agent_schema_migrations WHERE version=18");
        }
        try (JdbcAgentStore upgraded = new JdbcAgentStore(path)) {
            assertEquals(21, upgraded.schemaVersion());
            assertTrue(upgraded.healthy());
            var scope = new com.rover.agent.core.model.ResourceScope("org", "ops", "staging");
            var subject = new com.rover.agent.core.model.ActionCertificationSubject(scope,
                    com.rover.agent.core.model.ActionType.ADJUST_ROUTE_TARGET_WEIGHT, "runbook", "1",
                    "adapter", "effect", "query", "gateway");
            long now = System.currentTimeMillis();
            var cert = new com.rover.agent.core.model.ActionCertification(subject, 1,
                    com.rover.agent.core.model.ActionCertification.State.ACTIVE, 3, "a".repeat(64),
                    "operator", now, now + 60_000, "operator", now);
            upgraded.actionCertifications().save(cert, 0);
            assertTrue(upgraded.audit("3:org3:ops7:staging", 0, 100).stream()
                    .anyMatch(item -> item.kind().equals("ACTION_CERTIFICATION_VERSIONED")));
        }
    }

    @Test
    void schemaFiveUpgradeLeavesUnknownHistoricalSourcesUnset() throws Exception {
        String path = tempPath("rover-alert-upgrade");
        try (JdbcAgentStore first = new JdbcAgentStore(path)) {
            openSession(first, session("s1"), incident("i1", "s1"));
        }
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:file:" + path + ";DB_CLOSE_DELAY=0;AUTO_SERVER=TRUE", "sa", "");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE agent_incident DROP COLUMN triggered_by");
            statement.executeUpdate("DELETE FROM agent_schema_migrations WHERE version >= 6");
        }
        try (JdbcAgentStore upgraded = new JdbcAgentStore(path)) {
            assertEquals(21, upgraded.schemaVersion());
            assertNull(upgraded.incidents().find("i1").orElseThrow().triggeredBy());
            assertEquals("alice", upgraded.sessions().find("s1").orElseThrow().userId());
        }
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
