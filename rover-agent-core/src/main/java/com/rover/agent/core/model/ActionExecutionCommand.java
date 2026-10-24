package com.rover.agent.core.model;

import java.util.Objects;

/** Durable, typed request to apply, compensate, or reconcile one action for one managed target. */
public record ActionExecutionCommand(String commandId, String actionId, ActionType actionType, Phase phase,
                                     ResourceScope scope, String controlTargetId, String targetNamespace,
                                     int planVersion, String planHash, String policyVersion, String operationId, String actor,
                                     long createdAtMillis) {

    public ActionExecutionCommand {
        commandId = required(commandId, "commandId", 256);
        actionId = required(actionId, "actionId", 64);
        Objects.requireNonNull(actionType, "actionType");
        Objects.requireNonNull(phase, "phase");
        controlTargetId = controlTargetId == null ? "" : controlTargetId.trim();
        targetNamespace = targetNamespace == null ? "" : targetNamespace.trim();
        if (planVersion < ActionApproval.LEGACY_PLAN_VERSION) {
            throw new IllegalArgumentException("planVersion is invalid");
        }
        planHash = required(planHash, "planHash", 64);
        if (planVersion == ActionApproval.LEGACY_PLAN_VERSION
                ? !ActionApproval.LEGACY_PLAN_HASH.equals(planHash) : !planHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("plan binding is invalid");
        }
        policyVersion = required(policyVersion, "policyVersion", 128);
        operationId = operationId == null || operationId.isBlank() ? null : operationId.trim();
        actor = actor == null || actor.isBlank() ? null : actor.trim();
        if (createdAtMillis <= 0) { throw new IllegalArgumentException("createdAtMillis is invalid"); }
        if (scope != null && controlTargetId.isBlank()) {
            throw new IllegalArgumentException("managed action command requires a control target");
        }
    }

    public static ActionExecutionCommand from(AgentAction action, Phase phase, String actor,
                                              String targetNamespace, String policyVersion) {
        Objects.requireNonNull(action, "action");
        ResourceRef resource = action.resourceRef();
        int version = action.plan() == null ? ActionApproval.LEGACY_PLAN_VERSION : action.plan().planVersion();
        String hash = action.plan() == null ? ActionApproval.LEGACY_PLAN_HASH : action.plan().planHash();
        String operationId = phase == Phase.RECONCILE
                ? action.rollbackOperationId() != null ? action.rollbackOperationId() : action.applyOperationId()
                : null;
        String commandId = action.actionId() + ":" + phase + ":" + version + ":" + hash
                + ":" + policyVersion
                + (phase == Phase.RECONCILE ? ":" + Objects.toString(operationId, "none") : "");
        return new ActionExecutionCommand(commandId, action.actionId(), action.type(), phase,
                resource == null ? null : resource.scope(), resource == null ? "" : resource.controlTargetId(),
                targetNamespace, version, hash, policyVersion, operationId, actor, System.currentTimeMillis());
    }

    private static String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.trim();
    }

    public enum Phase { APPLY, ROLLBACK, RECONCILE }
}
