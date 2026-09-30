package com.rover.agent.core.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.model.EvidenceType;
import com.rover.agent.core.port.LogEntry;
import com.rover.agent.core.port.LogQueryPort;
import com.rover.agent.core.port.SnapshotUnavailableException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 日志查询能力：走 LogQueryPort 取历史日志，产出 LOG 证据；数据不可用时降级为判断边界。
 *
 * <p>领域层测试不引 Mockito：queryLogs 只依赖 logs 端口，其余只读端口传 null（本用例不触及）。
 */
class CapabilityExecutorLogQueryTest {

    private CapabilityExecutor executor(LogQueryPort logs) {
        return new CapabilityExecutor(null, null, null, null, null, null, logs, null,
                CapabilityRegistry.standard());
    }

    @Test
    void queryLogsProducesLogEvidence() {
        LogQueryPort logs = req -> List.of(
                new LogEntry(1000L, "CONFIG_CHANGE", "/api/x", "{\"action\":\"saveRoute\"}"));
        CapabilityExecutor executor = executor(logs);

        CapabilityResult result = executor.queryLogs("/api/x", List.of("CONFIG_CHANGE"), null, null, "task-1");

        assertTrue(result.executed());
        assertEquals(1, result.evidence().size());
        assertEquals(EvidenceType.LOG, result.evidence().get(0).type());
        assertEquals("落盘历史日志", result.evidence().get(0).source());
        assertTrue(result.evidence().get(0).summary().contains("CONFIG_CHANGE"));
        assertEquals("", result.evidence().get(0).toolCallId(), "没有绑定工具调用时不能编一个号");
    }

    @Test
    void queryLogsStampsTheBoundToolCallId() {
        LogQueryPort logs = req -> List.of(new LogEntry(1000L, "ERROR", "/api/order", "timeout"));
        ToolCallTrace.bind("call-7");
        try {
            CapabilityResult result = executor(logs).queryLogs("/api/order", null, null, null, "task-1");
            assertEquals("call-7", result.evidence().get(0).toolCallId());
        } finally {
            ToolCallTrace.clear();
        }
        CapabilityResult later = executor(logs).queryLogs("/api/order", null, null, null, "task-1");
        assertEquals("", later.evidence().get(0).toolCallId(), "离开回调后不能把上一次的调用号借给下一次取数");
    }

    @Test
    void queryLogsDegradesToBoundaryWhenStorageUnavailable() {
        LogQueryPort logs = req -> {
            throw new SnapshotUnavailableException("存储不可用");
        };
        CapabilityExecutor executor = executor(logs);

        CapabilityResult result = executor.queryLogs(null, null, null, null, "task-1");

        assertTrue(result.executed());
        assertTrue(result.evidence().isEmpty());
        assertFalse(result.limitations().isEmpty());
        assertTrue(result.limitations().get(0).contains("历史日志不可用"));
    }
}
