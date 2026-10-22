package com.rover.agent.runtime.action;

import com.rover.agent.core.model.AgentAction;
import com.rover.agent.core.model.RemediationPlan;
import com.rover.agent.core.model.AutoExecutionPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Deterministic risk classification and approval thresholds. The fingerprint changes with every rule change. */
public final class RiskBasedApprovalPolicy {

    private final String policyId;
    private final double highImpactPercent;
    private final int r2Approvals;
    private final int r3Approvals;
    private final String policyVersion;
    private final java.util.function.Supplier<java.util.List<AutoExecutionPolicy>> preauthorizations;

    public RiskBasedApprovalPolicy(String policyId, double highImpactPercent, int r2Approvals, int r3Approvals) {
        this(policyId, highImpactPercent, r2Approvals, r3Approvals,
                (java.util.function.Supplier<java.util.List<AutoExecutionPolicy>>) java.util.List::of);
    }

    public RiskBasedApprovalPolicy(String policyId, double highImpactPercent, int r2Approvals, int r3Approvals,
                                   java.util.List<AutoExecutionPolicy> preauthorizations) {
        if (policyId == null || !policyId.matches("[A-Za-z0-9._-]{1,32}")) {
            throw new IllegalArgumentException("policyId is invalid");
        }
        if (!Double.isFinite(highImpactPercent) || highImpactPercent <= 0 || highImpactPercent > 100) {
            throw new IllegalArgumentException("highImpactPercent must be in (0,100]");
        }
        if (r2Approvals < 1 || r2Approvals > 10 || r3Approvals < 2 || r3Approvals > 10) {
            throw new IllegalArgumentException("approval counts are outside supported bounds");
        }
        this.policyId = policyId;
        this.highImpactPercent = highImpactPercent;
        this.r2Approvals = r2Approvals;
        this.r3Approvals = r3Approvals;
        this.policyVersion = policyId + "@" + fingerprint();
        java.util.List<AutoExecutionPolicy> policies = preauthorizations == null
                ? java.util.List.of() : java.util.List.copyOf(preauthorizations);
        this.preauthorizations = () -> policies;
    }

    public RiskBasedApprovalPolicy(String policyId, double highImpactPercent, int r2Approvals, int r3Approvals,
                                   java.util.function.Supplier<java.util.List<AutoExecutionPolicy>> preauthorizations) {
        if (policyId == null || !policyId.matches("[A-Za-z0-9._-]{1,32}")
                || !Double.isFinite(highImpactPercent) || highImpactPercent <= 0 || highImpactPercent > 100
                || r2Approvals < 1 || r2Approvals > 10 || r3Approvals < 2 || r3Approvals > 10) {
            throw new IllegalArgumentException("approval policy is outside supported bounds");
        }
        this.policyId = policyId;
        this.highImpactPercent = highImpactPercent;
        this.r2Approvals = r2Approvals;
        this.r3Approvals = r3Approvals;
        this.policyVersion = policyId + "@" + fingerprint();
        this.preauthorizations = preauthorizations == null ? java.util.List::of : preauthorizations;
    }

    public static RiskBasedApprovalPolicy defaults() {
        return new RiskBasedApprovalPolicy("risk-approval-v1", 25, 1, 2);
    }

    public RemediationPlan.RiskLevel classify(AgentAction action) {
        Objects.requireNonNull(action, "action");
        double change = Math.abs(action.desiredTrafficPercent() - action.beforeTrafficPercent());
        return action.beforeTrafficPercent() >= highImpactPercent || change >= highImpactPercent
                ? RemediationPlan.RiskLevel.R3 : RemediationPlan.RiskLevel.R2;
    }

    /** Existing immutable plans whose risk no longer matches policy must be regenerated. */
    public Decision decisionFor(AgentAction action) {
        return decisionFor(action, com.rover.agent.core.model.ActionApproval.Stage.APPLY);
    }

    public Decision decisionFor(AgentAction action, com.rover.agent.core.model.ActionApproval.Stage stage) {
        RemediationPlan.RiskLevel classified = classify(action);
        if (action.plan() != null && action.plan().riskAssessment().level() != classified) {
            throw new PolicyChangedException("当前风险策略将此计划评为 " + classified
                    + "，与计划内冻结的 " + action.plan().riskAssessment().level() + " 不一致；请重新生成计划");
        }
        boolean preauthorizationPlan = stage == com.rover.agent.core.model.ActionApproval.Stage.APPLY
                && action.plan() != null
                && action.plan().riskAssessment().approvalMode() == RemediationPlan.ApprovalMode.PREAUTHORIZED;
        boolean preauthorized = preauthorizationPlan && currentPreauthorization(action, System.currentTimeMillis()) != null;
        if (preauthorizationPlan && !preauthorized) {
            throw new PolicyChangedException("预授权策略已过期、撤销或范围不再匹配；该计划不能执行或改为人工审批，请重新调查并生成计划");
        }
        int approvals = classified == RemediationPlan.RiskLevel.R3 ? r3Approvals : r2Approvals;
        return new Decision(classified, preauthorized ? 0 : approvals,
                !preauthorized && classified != RemediationPlan.RiskLevel.R3,
                policyVersion, highImpactPercent);
    }

    public boolean matchesPreauthorization(AgentAction action, String namespace, long nowMillis) {
        if (action == null || action.plan() == null
                || action.plan().riskAssessment().approvalMode() != RemediationPlan.ApprovalMode.PREAUTHORIZED) {
            return false;
        }
        String binding = action.plan().provenance().policyVersion();
        return preauthorizations.get().stream().anyMatch(policy -> policy.policyVersion().equals(binding)
                && policy.permits(action, nowMillis)
                && policy.matches(action, namespace == null ? policy.targetNamespace() : namespace, nowMillis));
    }

    public AutoExecutionPolicy preauthorizationFor(AgentAction action, String namespace, long nowMillis) {
        if (action == null || action.plan() == null) { return null; }
        return preauthorizations.get().stream().filter(policy -> policy.matches(action, namespace, nowMillis))
                .findFirst().orElse(null);
    }

    private AutoExecutionPolicy currentPreauthorization(AgentAction action, long nowMillis) {
        if (action == null || action.plan() == null
                || action.plan().riskAssessment().approvalMode() != RemediationPlan.ApprovalMode.PREAUTHORIZED) {
            return null;
        }
        String version = action.plan().provenance().policyVersion();
        return preauthorizations.get().stream().filter(policy -> policy.policyVersion().equals(version)
                && policy.permits(action, nowMillis)).findFirst().orElse(null);
    }

    public String policyVersion() { return policyVersion; }

    private String fingerprint() {
        String canonical = policyId + "|highImpactPercent=" + Double.toString(highImpactPercent)
                + "|r2Approvals=" + r2Approvals + "|r3Approvals=" + r3Approvals
                + "|r3RequesterMayApprove=false";
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public record Decision(RemediationPlan.RiskLevel riskLevel, int requiredApprovals,
                           boolean requesterMayApprove, String policyVersion, double highImpactPercent) { }

    public static final class PolicyChangedException extends RuntimeException {
        public PolicyChangedException(String message) { super(message); }
    }
}
