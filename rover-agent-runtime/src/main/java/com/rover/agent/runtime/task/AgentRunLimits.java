package com.rover.agent.runtime.task;

import java.time.Duration;

/** 每个任务共用的执行预算；模型重试和工具循环不会重置它。 */
public record AgentRunLimits(int maxModelCalls, int maxOutputTokens, long maxTotalTokens,
                             Duration taskTimeout, int maxRepeatedToolResults) {

    public AgentRunLimits {
        if (maxModelCalls <= 0 || maxOutputTokens <= 0 || maxTotalTokens <= 0
                || taskTimeout == null || taskTimeout.isNegative() || taskTimeout.isZero()
                || maxRepeatedToolResults < 2) {
            throw new IllegalArgumentException("Agent 执行限制必须为正数，重复结果阈值至少为 2");
        }
    }

    public static AgentRunLimits defaults() {
        return new AgentRunLimits(20, 4096, 100_000, Duration.ofSeconds(180), 3);
    }
}
