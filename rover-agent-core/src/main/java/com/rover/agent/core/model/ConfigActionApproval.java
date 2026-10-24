package com.rover.agent.core.model;

/** One human approval bound to the exact immutable intent and current policy. */
public record ConfigActionApproval(String approver, String intentHash, String policyVersion, long approvedAtMillis) {
    public ConfigActionApproval {
        if (approver == null || approver.isBlank() || approver.length() > 128
                || intentHash == null || !intentHash.matches("[0-9a-f]{64}")
                || policyVersion == null || policyVersion.isBlank() || policyVersion.length() > 128
                || approvedAtMillis <= 0) { throw new IllegalArgumentException("配置动作审批记录无效"); }
        approver = approver.trim(); policyVersion = policyVersion.trim();
    }
}
