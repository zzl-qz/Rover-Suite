package com.rover.agent.core.model;

import java.util.Objects;

/** A narrowly scoped, revocable authorization for one exact route target and one bounded action. */
public record AutoExecutionPolicy(String policyId, long version, boolean enabled, String targetNamespace,
                                  ResourceRef resource, String serviceName, String group, int desiredWeight,
                                  double maxBeforeTrafficPercent, double maxTrafficShiftPercent,
                                  double maxCumulativeTrafficShiftPercent, long cooldownMillis, long budgetWindowMillis,
                                  long expiresAtMillis, String createdBy, long createdAtMillis,
                                  String updatedBy, long updatedAtMillis) {
    public AutoExecutionPolicy {
        policyId = required(policyId, "policyId", 64);
        targetNamespace = required(targetNamespace, "targetNamespace", 128);
        Objects.requireNonNull(resource, "resource");
        serviceName = required(serviceName, "serviceName", 256);
        group = group == null ? "" : group.trim();
        // Policies created before schema v17 receive the safe defaults when their old JSON is read.
        if (maxCumulativeTrafficShiftPercent == 0 && cooldownMillis == 0 && budgetWindowMillis == 0) {
            maxCumulativeTrafficShiftPercent = 10;
            cooldownMillis = java.time.Duration.ofMinutes(15).toMillis();
            budgetWindowMillis = java.time.Duration.ofHours(24).toMillis();
        }
        if (version < 1 || desiredWeight != 0 || !Double.isFinite(maxBeforeTrafficPercent)
                || maxBeforeTrafficPercent <= 0 || maxBeforeTrafficPercent > 10
                || !Double.isFinite(maxTrafficShiftPercent) || maxTrafficShiftPercent <= 0
                || maxTrafficShiftPercent > 10 || expiresAtMillis <= createdAtMillis
                || !Double.isFinite(maxCumulativeTrafficShiftPercent)
                || maxCumulativeTrafficShiftPercent <= 0 || maxCumulativeTrafficShiftPercent > 10
                || maxCumulativeTrafficShiftPercent < maxTrafficShiftPercent
                || budgetWindowMillis != java.time.Duration.ofHours(24).toMillis()
                || cooldownMillis < java.time.Duration.ofMinutes(15).toMillis()
                || cooldownMillis > java.time.Duration.ofHours(24).toMillis()
                || enabled && expiresAtMillis <= updatedAtMillis
                || createdAtMillis <= 0 || updatedAtMillis < createdAtMillis) {
            throw new IllegalArgumentException("preauthorization policy is outside the supported safety bounds");
        }
        createdBy = required(createdBy, "createdBy", 128);
        updatedBy = required(updatedBy, "updatedBy", 128);
    }

    public String policyVersion() { return "preauth:" + policyId + "@" + version; }

    public boolean matches(AgentAction action, String namespace, long nowMillis) {
        return matchesCommon(action, namespace, nowMillis);
    }

    public boolean matchesCommon(AgentAction action, String namespace, long nowMillis) {
        return enabled && expiresAtMillis > nowMillis && targetNamespace.equals(namespace)
                && action != null && action.plan() != null && action.plan().expiresAtMillis() > nowMillis
                && resource.equals(action.resourceRef()) && serviceName.equals(action.serviceName())
                && group.equals(action.group()) && action.type() == ActionType.ADJUST_ROUTE_TARGET_WEIGHT
                && action.desiredWeight() == desiredWeight && action.beforeTrafficPercent() >= 0
                && action.beforeTrafficPercent() <= maxBeforeTrafficPercent
                && Math.abs(action.desiredTrafficPercent() - action.beforeTrafficPercent()) <= maxTrafficShiftPercent
                && action.plan().riskAssessment().level() == RemediationPlan.RiskLevel.R2
                && action.plan().verificationSpec().mode() == RemediationPlan.VerificationMode.BUSINESS_EFFECT
                && !action.plan().evidenceRefs().isEmpty()
                && "RULE_EVIDENCE_GATE".equals(action.plan().provenance().source());
    }

    public boolean permits(AgentAction action, long nowMillis) {
        return action != null && action.plan() != null && matches(action, targetNamespace, nowMillis)
                && policyVersion().equals(action.plan().provenance().policyVersion())
                && action.plan().riskAssessment().approvalMode() == RemediationPlan.ApprovalMode.PREAUTHORIZED
                && policyVersion().matches("preauth:[A-Za-z0-9._-]{1,64}@[1-9][0-9]*");
    }

    public static AutoExecutionPolicy create(String id, String namespace, ResourceRef resource, String service,
                                              String group, double maxBeforePercent, double maxShiftPercent,
                                              long expires, String actor, long now) {
        return create(id, namespace, resource, service, group, maxBeforePercent, maxShiftPercent,
                10, java.time.Duration.ofMinutes(15).toMillis(), expires, actor, now);
    }

    public static AutoExecutionPolicy create(String id, String namespace, ResourceRef resource, String service,
                                              String group, double maxBeforePercent, double maxShiftPercent,
                                              double maxCumulativeShiftPercent, long cooldownMillis,
                                              long expires, String actor, long now) {
        return new AutoExecutionPolicy(id, 1, false, namespace, resource, service, group, 0, maxBeforePercent,
                maxShiftPercent, maxCumulativeShiftPercent, cooldownMillis,
                java.time.Duration.ofHours(24).toMillis(), expires, actor, now, actor, now);
    }

    public AutoExecutionPolicy revise(boolean nextEnabled, double maxBeforePercent, double maxShiftPercent,
                                      long expires, String actor, long now) {
        return new AutoExecutionPolicy(policyId, version + 1, nextEnabled, targetNamespace, resource, serviceName,
                group, desiredWeight, maxBeforePercent, maxShiftPercent, maxCumulativeTrafficShiftPercent,
                cooldownMillis, budgetWindowMillis, expires, createdBy, createdAtMillis, actor, now);
    }

    public boolean currentFor(String namespace, long nowMillis) {
        return enabled && targetNamespace.equals(namespace) && expiresAtMillis > nowMillis;
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.trim();
    }
}
