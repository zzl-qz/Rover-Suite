package com.rover.agent.core.context;

import com.rover.agent.core.model.AgentMessage;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TimeRange;
import java.util.List;

/**
 * 一次提问所携带的上下文：会话、当前事件、结构化目标、最近消息与关键证据。
 *
 * 上下文窗口刻意是有限的：{@code recentMessages} 只保留最近若干条（条数由配置决定），
 * {@code importantEvidence} 只保留当前事件最新一次调查的关键证据。历史不会被无限发送给模型。
 *
 * {@code currentTarget} 是结构化目标；{@code currentService} / {@code currentRoute} /
 * {@code currentInstance} 是它的展开视图，用于让「只看刚才那个服务」这类追问能被解析到具体对象。
 * 目标为 {@link com.rover.agent.core.model.TargetType#UNKNOWN} 或对应维度缺失时，展开值为 {@code null}。
 *
 * @param sessionId         会话 ID
 * @param activeIncidentId  当前事件 ID；没有当前事件时为 {@code null}
 * @param currentTarget     当前结构化目标；尚未确定时为 {@link ResourceTarget#unknown()}
 * @param currentService    当前服务名；未知时为 {@code null}
 * @param currentRoute      当前路由路径；未知时为 {@code null}
 * @param currentInstance   当前实例地址 {@code host:port}；未知时为 {@code null}
 * @param timeRange         当前调查时间范围；未指定时为 {@link TimeRange#unspecified()}
 * @param recentMessages    最近若干条消息（最早在前，不超过配置的条数上限）
 * @param importantEvidence 当前事件的关键证据（不超过上限，按取证顺序）
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