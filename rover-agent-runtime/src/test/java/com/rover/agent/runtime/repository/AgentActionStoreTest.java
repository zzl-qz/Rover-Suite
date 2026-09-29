package com.rover.agent.runtime.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.model.ActionStatus;
import com.rover.agent.core.model.ActionType;
import com.rover.agent.core.model.AgentAction;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.SessionStatus;
import com.rover.agent.core.model.WeightRequestUnit;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 受控变更记录的持久化：一条变更在库里活多久、被谁动过、崩在哪一步。
 *
 * <p>这里守的三件事都不属于「业务能不能跑通」，而是「出事之后能不能说清」：
 * 变更逐字段可读回来、状态迁移是真 CAS（双击批准在存储层就只有一次成功）、
 * 重启时停在半路的变更会被挪到一个诚实的终态（提交前断的判失败，提交后断的判结果未知）。
 */
class AgentActionStoreTest {

    @Test
    void actionSurvivesRestartWithEveryField() throws Exception {
        String path = tempPath("rover-action-restart");
        // 已完成（终态）：重启不该动它，逐字段读回来必须一模一样
        AgentAction action = pendingAction("a1", "s1", ActionStatus.SUCCESS, "op-1", 18);

        try (JdbcAgentStore first = new JdbcAgentStore(path)) {
            first.sessions().save(session("s1"));
            first.actions().save(action);
        }

        try (JdbcAgentStore second = new JdbcAgentStore(path)) {
            AgentAction reloaded = second.actions().find("a1").orElseThrow();
            assertEquals(action, reloaded, "提议依据、幂等号与预览都要能读回来");
            assertEquals(18, reloaded.appliedRevision());
            assertEquals(List.of("MODIFIED /api/order：order-service@v2 权重 → 20"), reloaded.preview());
            assertEquals("order-service@v2", reloaded.targetLabel());
            // 请求口径与流量占比必须随变更一起落库：事后要能回答「批的到底是权重还是百分比」
            assertEquals(20, reloaded.requestedValue());
            assertEquals(WeightRequestUnit.RAW_WEIGHT, reloaded.requestedUnit());
            assertEquals(5.0, reloaded.beforeTrafficPercent(), 0.05);
            assertEquals(17.4, reloaded.desiredTrafficPercent(), 0.05);
        }
    }

    @Test
    void transitionIsCompareAndSet() throws Exception {
        String path = tempPath("rover-action-cas");
        try (JdbcAgentStore store = new JdbcAgentStore(path)) {
            store.sessions().save(session("s1"));
            store.actions().save(pendingAction("a1", "s1", ActionStatus.PENDING_APPROVAL, null, null));

            Optional<AgentAction> claimed = store.actions().transition("a1", ActionStatus.PENDING_APPROVAL,
                    current -> pendingAction("a1", "s1", ActionStatus.EXECUTING, null, null));
            Optional<AgentAction> second = store.actions().transition("a1", ActionStatus.PENDING_APPROVAL,
                    current -> pendingAction("a1", "s1", ActionStatus.EXECUTING, null, null));

            assertTrue(claimed.isPresent());
            assertTrue(second.isEmpty(), "状态已经不是 PENDING_APPROVAL，第二次迁移必须失败");
            assertEquals(ActionStatus.EXECUTING, store.actions().find("a1").orElseThrow().status());
            assertTrue(store.actions().transition("missing", ActionStatus.PENDING_APPROVAL,
                    current -> current).isEmpty());
        }
    }

    @Test
    void deletingASessionRemovesItsActions() throws Exception {
        String path = tempPath("rover-action-cascade");
        try (JdbcAgentStore store = new JdbcAgentStore(path)) {
            store.sessions().save(session("s1"));
            store.actions().save(pendingAction("a1", "s1", ActionStatus.PENDING_APPROVAL, null, null));

            store.sessions().remove("s1");

            assertTrue(store.actions().find("a1").isEmpty(),
                    "会话没了，挂在它上面的待审批变更不该留下——否则没人能处理它");
        }
    }

    @Test
    void restartMovesHalfFinishedActionsToAnHonestStatus() throws Exception {
        String path = tempPath("rover-action-recover");
        try (JdbcAgentStore store = new JdbcAgentStore(path)) {
            store.sessions().save(session("s1"));
            // 提交前就崩了：幂等号还没落库，网关一定没收到写请求
            store.actions().save(pendingAction("a-before", "s1", ActionStatus.EXECUTING, null, null));
            // 提交后崩了：幂等号已经在库里，写请求可能已经生效
            store.actions().save(pendingAction("a-after", "s1", ActionStatus.EXECUTING, "op-1", null));
            // 回滚前崩了：变更本身是生效的，只是补偿没发出去
            store.actions().save(pendingAction("a-rollback", "s1", ActionStatus.ROLLING_BACK, "op-2", 18));
        }

        try (JdbcAgentStore store = new JdbcAgentStore(path)) {
            AgentAction beforeSubmit = store.actions().find("a-before").orElseThrow();
            assertEquals(ActionStatus.FAILED, beforeSubmit.status());
            assertTrue(beforeSubmit.errorMessage().contains("Gateway 未被改动"));

            AgentAction afterSubmit = store.actions().find("a-after").orElseThrow();
            assertEquals(ActionStatus.UNCERTAIN, afterSubmit.status(),
                    "已经发出过写请求的，只能按「结果未知」处理");
            assertTrue(afterSubmit.errorMessage().contains("确认"), afterSubmit.errorMessage());

            AgentAction rollbackUnsent = store.actions().find("a-rollback").orElseThrow();
            assertEquals(ActionStatus.SUCCESS, rollbackUnsent.status(),
                    "回滚没发出去不等于变更没生效，必须还能再点一次回滚");
            assertTrue(rollbackUnsent.errorMessage().contains("重试回滚"));
        }
    }

    @Test
    void taskRetirementDetachesTheActionWithoutLosingIt() throws Exception {
        String path = tempPath("rover-action-task");
        try (JdbcAgentStore store = new JdbcAgentStore(path)) {
            store.sessions().save(session("s1"));
            store.actions().save(pendingAction("a1", "s1", ActionStatus.PENDING_APPROVAL, "op-1", 18));

            // 直接改库模拟「任务被淘汰」：外键是 SET NULL，变更记录必须留下来
            try (Connection connection = DriverManager.getConnection(
                    "jdbc:h2:file:" + path + ";DB_CLOSE_DELAY=0;AUTO_SERVER=TRUE", "sa", "");
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO agent_task (task_id, session_id, task_type, status, "
                        + "created_at, completed_at, updated_at, version) VALUES ('t1', 's1', 'INVESTIGATION', "
                        + "'COMPLETED', 1, 2, 3, 1)");
                statement.executeUpdate("UPDATE agent_action SET task_id = 't1' WHERE action_id = 'a1'");
            }

            store.tasks().remove("t1");

            AgentAction detached = store.actions().find("a1").orElseThrow();
            assertNull(detached.taskId(), "任务被淘汰时把链接置空");
            assertEquals(ActionStatus.PENDING_APPROVAL, detached.status(), "变更本身是审计记录，不能跟着消失");
        }
    }

    private static String tempPath(String prefix) throws Exception {
        return Files.createTempDirectory(prefix).resolve("rover").toString();
    }

    private static Session session(String sessionId) {
        return new Session(sessionId, "alice", "订单灰度", null, SessionStatus.ACTIVE, 1L, 1L, List.of());
    }

    /**
     * 一条内容填满的变更：幂等号与生效版本是唯一随时间变化的两项，因此作为参数给出来。
     *
     * <p>{@code taskId} 留空：它是指向 {@code agent_task} 的外键，只有真的挂在某个任务上时才写，
     * 「任务被淘汰后链接置空」那条用例会显式建一个任务再把它删掉。
     */
    private static AgentAction pendingAction(String actionId, String sessionId, ActionStatus status,
                                             String applyOperationId, Integer appliedRevision) {
        return new AgentAction(actionId, sessionId, "i1", null, ActionType.ADJUST_ROUTE_TARGET_WEIGHT, status,
                "order-api", "/api/order", "order-service", "v2", 5, 20,
                20, WeightRequestUnit.RAW_WEIGHT, 5.0, 17.4,
                17,
                applyOperationId, appliedRevision, null, "alice", "alice", 100L,
                List.of("MODIFIED /api/order：order-service@v2 权重 → 20"),
                "放量：order-service@v2 的灰度流量增加（5 → 20）", null, 100L, 150L);
    }
}
