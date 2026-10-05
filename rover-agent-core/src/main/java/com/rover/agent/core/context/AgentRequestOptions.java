package com.rover.agent.core.context;

import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TimeRange;

/**
 * 提问的可选上下文，显式目标优先于文本解析；无法定位时要求澄清。
 *
 * @param target          手工指定的调查对象；未指定时为 {@link ResourceTarget#unknown()}
 * @param timeRange       手工指定的时间范围；未指定时为 {@link TimeRange#unspecified()}
 * @param recallSessionId 用户点名的旧会话；没点时为空
 */
public record AgentRequestOptions(ResourceTarget target, TimeRange timeRange, String recallSessionId) {

    public AgentRequestOptions {
        target = target == null ? ResourceTarget.unknown() : target;
        timeRange = timeRange == null ? TimeRange.unspecified() : timeRange;
        recallSessionId = recallSessionId == null || recallSessionId.isBlank() ? null : recallSessionId.trim();
    }

    public AgentRequestOptions(ResourceTarget target, TimeRange timeRange) {
        this(target, timeRange, null);
    }

    /** 没有手工上下文：全部按默认策略解析。 */
    public static AgentRequestOptions none() {
        return new AgentRequestOptions(ResourceTarget.unknown(), TimeRange.unspecified(), null);
    }
}