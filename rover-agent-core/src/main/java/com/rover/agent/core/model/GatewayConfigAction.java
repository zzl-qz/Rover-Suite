package com.rover.agent.core.model;

import java.util.List;
import java.util.Objects;

/** Durable execution record; approval and runtime state never alter the frozen intent. */
public record GatewayConfigAction(String actionId, String sessionId, String incidentId, String taskId,
                                  String requestedBy, ConfigActionIntent intent, String intentHash,
                                  String policyVersion, List<ConfigActionApproval> approvals, String operationId,
                                  String compensatesActionId, ConfigActionStatus status, String errorMessage, long createdAtMillis,
                                  long updatedAtMillis, long expiresAtMillis, long version) {
    public GatewayConfigAction(String actionId, String sessionId, String incidentId, String taskId,
            String requestedBy, ConfigActionIntent intent, String intentHash, String policyVersion,
            List<ConfigActionApproval> approvals, String operationId, ConfigActionStatus status,
            String errorMessage, long createdAtMillis, long updatedAtMillis, long expiresAtMillis, long version) {
        this(actionId, sessionId, incidentId, taskId, requestedBy, intent, intentHash, policyVersion, approvals,
                operationId, null, status, errorMessage, createdAtMillis, updatedAtMillis, expiresAtMillis, version);
    }
    public GatewayConfigAction {
        actionId = required(actionId, "actionId", 64);
        sessionId = required(sessionId, "sessionId", 64);
        incidentId = optional(incidentId); taskId = optional(taskId); requestedBy = optional(requestedBy);
        compensatesActionId = optional(compensatesActionId);
        Objects.requireNonNull(intent, "intent");
        if (intentHash == null || !intentHash.matches("[0-9a-f]{64}") || !intent.intentHash().equals(intentHash)) {
            throw new IllegalArgumentException("配置动作摘要与冻结意图不匹配");
        }
        policyVersion = required(policyVersion, "policyVersion", 128);
        List<ConfigActionApproval> normalizedApprovals = approvals == null ? List.of() : List.copyOf(approvals);
        approvals = normalizedApprovals;
        operationId = required(operationId, "operationId", 128);
        if (actionId.equals(compensatesActionId)) throw new IllegalArgumentException("配置动作不能补偿自身");
        Objects.requireNonNull(status, "status");
        errorMessage = optional(errorMessage);
        if (createdAtMillis <= 0 || updatedAtMillis < createdAtMillis || expiresAtMillis <= createdAtMillis || version < 0) {
            throw new IllegalArgumentException("配置动作时间或版本无效");
        }
        for (ConfigActionApproval approval : normalizedApprovals) {
            if (!intentHash.equals(approval.intentHash()) || !policyVersion.equals(approval.policyVersion()))
                throw new IllegalArgumentException("审批必须绑定当前配置动作摘要与策略版本");
        }
        if (approvals.stream().map(ConfigActionApproval::approver).distinct().count() != approvals.size()) {
            throw new IllegalArgumentException("同一人员不能重复审批同一配置动作");
        }
    }

    public int requiredApprovals() { return 2; }
    public boolean approvalComplete() { return approvals.size() >= requiredApprovals(); }
    public GatewayConfigAction withVersion(long next) {
        return new GatewayConfigAction(actionId, sessionId, incidentId, taskId, requestedBy, intent, intentHash,
                policyVersion, approvals, operationId, compensatesActionId, status, errorMessage, createdAtMillis, updatedAtMillis, expiresAtMillis, next);
    }
    public GatewayConfigAction withStatus(ConfigActionStatus next, String error, long atMillis) {
        return new GatewayConfigAction(actionId, sessionId, incidentId, taskId, requestedBy, intent, intentHash,
                policyVersion, approvals, operationId, compensatesActionId, next, error, createdAtMillis, atMillis, expiresAtMillis, version);
    }
    public GatewayConfigAction withApproval(ConfigActionApproval approval, ConfigActionStatus next, long atMillis) {
        java.util.ArrayList<ConfigActionApproval> updated = new java.util.ArrayList<>(approvals);
        updated.add(approval);
        return new GatewayConfigAction(actionId, sessionId, incidentId, taskId, requestedBy, intent, intentHash,
                policyVersion, updated, operationId, compensatesActionId, next, null, createdAtMillis, atMillis, expiresAtMillis, version);
    }
    public void requireSameIntent(GatewayConfigAction previous) {
        if (!actionId.equals(previous.actionId) || !sessionId.equals(previous.sessionId)
                || !Objects.equals(incidentId, previous.incidentId) || !Objects.equals(taskId, previous.taskId)
                || !Objects.equals(requestedBy, previous.requestedBy) || !intent.equals(previous.intent)
                || !intentHash.equals(previous.intentHash) || !policyVersion.equals(previous.policyVersion)
                || !operationId.equals(previous.operationId) || !Objects.equals(compensatesActionId, previous.compensatesActionId)
                || actionId.equals(compensatesActionId) || createdAtMillis != previous.createdAtMillis
                || expiresAtMillis != previous.expiresAtMillis) {
            throw new IllegalArgumentException("配置动作意图不可原地修改");
        }
        if (!previous.approvals.equals(approvals)
                && (approvals.size() != previous.approvals.size() + 1
                    || !approvals.subList(0, previous.approvals.size()).equals(previous.approvals)
                    || previous.status != ConfigActionStatus.PENDING_APPROVAL)) {
            throw new IllegalArgumentException("审批只能在待审批状态中追加一名新审批人");
        }
        if (!canTransition(previous.status, status)) {
            throw new IllegalArgumentException("不允许的配置动作状态迁移：" + previous.status + " -> " + status);
        }
        if (previous.status == ConfigActionStatus.APPLIED || previous.status == ConfigActionStatus.PRECONDITION_FAILED
                || previous.status == ConfigActionStatus.FAILED || previous.status == ConfigActionStatus.REJECTED
                || previous.status == ConfigActionStatus.EXPIRED) {
            if (status != previous.status) { throw new IllegalArgumentException("终态配置动作不可迁移"); }
        }
    }
    private static boolean canTransition(ConfigActionStatus from, ConfigActionStatus to) {
        return switch (from) {
            case PENDING_APPROVAL -> to == ConfigActionStatus.PENDING_APPROVAL || to == ConfigActionStatus.QUEUED
                    || to == ConfigActionStatus.REJECTED || to == ConfigActionStatus.EXPIRED;
            case QUEUED -> to == ConfigActionStatus.EXECUTING || to == ConfigActionStatus.FAILED
                    || to == ConfigActionStatus.EXPIRED;
            case EXECUTING -> to == ConfigActionStatus.APPLIED || to == ConfigActionStatus.PRECONDITION_FAILED
                    || to == ConfigActionStatus.FAILED || to == ConfigActionStatus.UNKNOWN || to == ConfigActionStatus.EXPIRED;
            case UNKNOWN -> to == ConfigActionStatus.RECONCILE_QUEUED;
            case RECONCILE_QUEUED -> to == ConfigActionStatus.RECONCILING || to == ConfigActionStatus.UNKNOWN;
            case RECONCILING -> to == ConfigActionStatus.APPLIED || to == ConfigActionStatus.PRECONDITION_FAILED
                    || to == ConfigActionStatus.FAILED || to == ConfigActionStatus.UNKNOWN;
            case APPLIED, PRECONDITION_FAILED, FAILED, REJECTED, EXPIRED -> to == from;
        };
    }
    private static String required(String v, String n, int max) {
        if (v == null || v.isBlank() || v.length() > max) { throw new IllegalArgumentException(n + " is invalid"); }
        return v.trim();
    }
    private static String optional(String v) { return v == null || v.isBlank() ? null : v.trim(); }
}
