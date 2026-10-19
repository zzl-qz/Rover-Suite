package com.rover.agent.core.model;

import java.util.Map;

/** 图调查和对话工具共用；权限范围只能由服务端任务/会话装配，不能接受模型填写。 */
public record InvestigationContext(String taskId, String incidentId, ResourceScope scope,
                                   String controlTargetId, String targetNamespace, ResourceTarget target,
                                   TimeRange requestedRange, Map<String, String> budgetSnapshot,
                                   String authorizationPolicy) {
    public InvestigationContext {
        target = target == null ? ResourceTarget.unknown() : target;
        requestedRange = requestedRange == null ? TimeRange.unspecified() : requestedRange;
        budgetSnapshot = budgetSnapshot == null ? Map.of() : Map.copyOf(budgetSnapshot);
    }

    public static InvestigationContext legacy(String taskId) {
        return new InvestigationContext(taskId, null, null, null, null, null, null, Map.of(), "UNKNOWN_LEGACY");
    }

    public InvestigationContext query(ResourceTarget resource, TimeRange range) {
        return new InvestigationContext(taskId, incidentId, scope, controlTargetId, targetNamespace,
                resource, range, budgetSnapshot, authorizationPolicy);
    }
}
