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

    /** 失败必须按原因分开数：只记「失败了几次」判断不出是部署、链路还是提示词的问题。 */
    @Test
    void modelOutcomesAreCountedSeparatelyPerScene() {
        metrics.modelCall("DeepSeek-Chat @ api.deepseek.com", "目标解析", 800L, ModelCallOutcome.OK);
        metrics.modelCall("DeepSeek-Chat @ api.deepseek.com", "目标解析", 30L, ModelCallOutcome.TIMEOUT);
        metrics.modelCall("未配置模型", "调查规划", 5L, ModelCallOutcome.NOT_CONFIGURED);

        assertEquals(2, registry.get(MicrometerAgentMetrics.MODEL_DURATION)
                .tag("model", "deepseek-chat").tag("scene", "目标解析").timer().count());
        assertEquals(1.0, registry.get(MicrometerAgentMetrics.MODEL_CALLS)
                .tag("outcome", "ok").tag("scene", "目标解析").counter().count());
        assertEquals(1.0, registry.get(MicrometerAgentMetrics.MODEL_CALLS)
                .tag("outcome", "timeout").tag("scene", "目标解析").counter().count());
        // 描述不可用时用 unknown 兜底，场景照旧分开：两个维度都不允许出现空标签值。
        assertEquals(1.0, registry.get(MicrometerAgentMetrics.MODEL_CALLS)
                .tag("model", MicrometerAgentMetrics.UNKNOWN_MODEL)
                .tag("outcome", "not_configured").tag("scene", "调查规划").counter().count());
        assertNull(registry.find(MicrometerAgentMetrics.MODEL_CALLS)
                .tag("outcome", "ok").tag("scene", "调查规划").counter());
    }

    /** token 分输入与输出两条线累计；拿不到用量时不报，避免把「未知」记成 0。 */
    @Test
    void tokenUsageIsCountedByKindAndAbsentUsageIsNotRecorded() {
        metrics.modelTokens("gpt-4o-mini", "解读", 1200L, 300L);
        metrics.modelTokens("gpt-4o-mini", "调查规划", 0L, 0L);

        assertEquals(1200.0, registry.get(MicrometerAgentMetrics.MODEL_TOKENS)
                .tag("kind", "prompt").counter().count());
        assertEquals(300.0, registry.get(MicrometerAgentMetrics.MODEL_TOKENS)
                .tag("kind", "completion").counter().count());
        // 供应商没给用量（两个数都是 0）：不产生任何计数线，而不是记成 0。
        assertNull(registry.find(MicrometerAgentMetrics.MODEL_TOKENS).tag("scene", "调查规划").counter());
    }

    /** 场景名进标签前也会被夹紧：空白兜底、超长截断，防止异常值把注册表撑大。 */
    @Test
    void sceneTagIsBounded() {
        assertEquals("unknown", MicrometerAgentMetrics.normalizeScene(null));
        assertEquals("unknown", MicrometerAgentMetrics.normalizeScene("   "));
        assertEquals("目标解析", MicrometerAgentMetrics.normalizeScene(" 目标解析 "));
        assertTrue(MicrometerAgentMetrics.normalizeScene("场".repeat(50)).length() <= 24);
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