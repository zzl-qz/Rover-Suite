package com.rover.agent.core.context;

import com.rover.agent.core.model.AgentMessage;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TimeRange;
import java.util.List;

/**
 * 一次提问所携带的上下文：会话、当前事件、结构化目标、最近消息与关键证据。
 *
 * 窗口刻意有限：{@code recentMessages} 只保留最近若干条，{@code importantEvidence} 只保留当前事件的
 * 关键证据，历史不会被无限发给模型。
 */
public record AgentContext(String sessionId, String activeIncidentId, ResourceTarget currentTarget,
                           String currentService, String currentRoute, String currentInstance,
                           TimeRange timeRange, List<AgentMessage> recentMessages,
                           List<Evidence> importantEvidence) {

    public AgentContext {
        currentTarget = currentTarget == null ? ResourceTarget.unknown() : currentTarget;
        timeRange = timeRange == null ? TimeRange.unspecified() : timeRange;
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        importantEvidence = importantEvidence == null ? List.of() : List.copyOf(importantEvidence);
    }

    /** 是否已挂在某个事件上；没有事件说明这是新问题的第一轮。 */
    public boolean hasActiveIncident() {
        return activeIncidentId != null && !activeIncidentId.isBlank();
    }
}