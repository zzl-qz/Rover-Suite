package com.rover.agent.runtime.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Agent 运行指标：口径与标签规范化。
 *
 * 这些数字是"容量配得对不对"的唯一依据，因此断言的是口径本身：
 * 活跃数能对上、等待输入不计成败、标签有限且已规范化（不出现会话/任务/路径这类高基数标签）。
 */
class MicrometerAgentMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerAgentMetrics metrics = new MicrometerAgentMetrics(registry);

    @Test
    void taskLifecycleBalancesCountsAndActiveGauge() {
        metrics.taskSubmitted();
        metrics.taskSubmitted();
        metrics.taskStarted();
        metrics.taskStarted();
        metrics.taskQueued(3);

        assertEquals(2.0, registry.counter(MicrometerAgentMetrics.TASK_SUBMITTED).count());
        assertEquals(2.0, registry.get(MicrometerAgentMetrics.TASK_ACTIVE).gauge().value());
        assertEquals(3.0, registry.get(MicrometerAgentMetrics.TASK_QUEUE_SIZE).gauge().value());

        metrics.taskSettled("COMPLETED", 1_500L);
        metrics.taskSettled("FAILED", 500L);

        assertEquals(0.0, registry.get(MicrometerAgentMetrics.TASK_ACTIVE).gauge().value());
        assertEquals(1.0, registry.get(MicrometerAgentMetrics.TASK_COMPLETED)
                .tag("status", "COMPLETED").counter().count());
        assertEquals(1.0, registry.get(MicrometerAgentMetrics.TASK_FAILED)
                .tag("status", "FAILED").counter().count());
        assertEquals(1, registry.get(MicrometerAgentMetrics.TASK_DURATION)
                .tag("status", "COMPLETED").timer().count());
        assertEquals(1.5, registry.get(MicrometerAgentMetrics.TASK_DURATION)
                .tag("status", "COMPLETED").timer().totalTime(TimeUnit.SECONDS), 0.001);
    }

    /** 等待输入既不算完成也不算失败：只留耗时，"完成 + 失败 + 仍在执行"才对得上登记数。 */
    @Test
    void waitingInputOnlyRecordsDuration() {
        metrics.taskStarted();
        metrics.taskSettled("WAITING_INPUT", 20L);

        assertEquals(1, registry.get(MicrometerAgentMetrics.TASK_DURATION)
                .tag("status", "WAITING_INPUT").timer().count());
        assertNull(registry.find(MicrometerAgentMetrics.TASK_COMPLETED).tag("status", "WAITING_INPUT").counter());
        assertNull(registry.find(MicrometerAgentMetrics.TASK_FAILED).tag("status", "WAITING_INPUT").counter());
    }

    @Test
    void rejectionReasonsAreUpperCasedAndBlankFallsBack() {
        metrics.taskRejected("queue");
        metrics.taskRejected("  ");

        assertEquals(1.0, registry.get(MicrometerAgentMetrics.TASK_REJECTED)
                .tag("reason", "QUEUE").counter().count());
        assertEquals(1.0, registry.get(MicrometerAgentMetrics.TASK_REJECTED)
                .tag("reason", "UNKNOWN").counter().count());
    }

    @Test
    void modelTagIsNormalizedAndErrorsAreCounted() {
        metrics.modelCall("DeepSeek-Chat @ api.deepseek.com", 800L, true);
        metrics.modelCall("未配置模型", 5L, false);

        assertEquals(1, registry.get(MicrometerAgentMetrics.MODEL_DURATION)
                .tag("model", "deepseek-chat").timer().count());
        assertEquals(1.0, registry.get(MicrometerAgentMetrics.MODEL_ERROR)
                .tag("model", "deepseek-chat").counter().count());
        // 描述不可用时用 unknown 兜底；只有失败才计错误数，未失败的调用不会凭空多出一个错误计数。
        assertEquals(1, registry.get(MicrometerAgentMetrics.MODEL_DURATION)
                .tag("model", MicrometerAgentMetrics.UNKNOWN_MODEL).timer().count());
        assertNull(registry.find(MicrometerAgentMetrics.MODEL_ERROR)
                .tag("model", MicrometerAgentMetrics.UNKNOWN_MODEL).counter());
    }

    @Test
    void sseConnectionsGaugeNeverGoesNegative() {
        metrics.sseConnected();
        metrics.sseConnected();
        metrics.sseDisconnected();
        assertEquals(1.0, registry.get(MicrometerAgentMetrics.SSE_CONNECTIONS).gauge().value());

        metrics.sseDisconnected();
        metrics.sseDisconnected();
        assertEquals(0.0, registry.get(MicrometerAgentMetrics.SSE_CONNECTIONS).gauge().value());
    }

    @Test
    void modelNameNormalizationKeepsTagValuesBounded() {
        assertEquals("deepseek-chat", MicrometerAgentMetrics.normalizeModel("DeepSeek-Chat @ api.deepseek.com"));
        assertEquals("gpt-4o-mini", MicrometerAgentMetrics.normalizeModel(" gpt-4o-mini "));
        assertEquals(MicrometerAgentMetrics.UNKNOWN_MODEL, MicrometerAgentMetrics.normalizeModel(null));
        assertEquals(MicrometerAgentMetrics.UNKNOWN_MODEL, MicrometerAgentMetrics.normalizeModel("未配置模型"));
        // 异常长的描述被截断，避免单个标签值把注册表撑大。
        assertTrue(MicrometerAgentMetrics.normalizeModel("m".repeat(200)).length() <= 48);
    }
}