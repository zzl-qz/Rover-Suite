package com.rover.agent.core.context;

import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TimeRange;

/**
 * 用户为一次提问手工补充的高级上下文（页面上的可折叠区域）。
 *
 * 全部字段可选：都不填时按问题文本解析目标、按数据源默认窗口取数。
 * 这是"用户明确指定"，因此优先级高于文本解析——但依然要经解析器落到可调查的路由上，
 * 指定了对象却找不到对应路由时同样要求澄清。
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