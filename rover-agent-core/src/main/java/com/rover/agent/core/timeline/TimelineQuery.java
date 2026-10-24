package com.rover.agent.core.timeline;

import com.rover.agent.core.model.InvestigationContext;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TimeRange;

/** Scope comes exclusively from the trusted investigation context. */
public record TimelineQuery(InvestigationContext context, ResourceTarget target, TimeRange range, int limit) {
    public TimelineQuery {
        if (context == null || context.scope() == null || context.controlTargetId() == null
                || context.targetNamespace() == null || target == null || range == null || range.isUnspecified()
                || target.type() == null || target.type() == com.rover.agent.core.model.TargetType.UNKNOWN
                || target.value().isBlank() || target.value().length() > 255
                || range.toMillis() - range.fromMillis() > 7L * 24 * 3_600_000 || limit < 1 || limit > 200) {
            throw new IllegalArgumentException("时间线需要可信范围、明确目标及最多七天的窗口，条数为 1~200");
        }
    }
}
