package com.rover.agent.runtime.action;

import com.rover.agent.core.model.AgentAction;
import com.rover.agent.core.model.RemediationPlan;
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

    public RiskBasedApprovalPolicy(String policyId, double highImpactPercent, int r2Approvals, int r3Approvals) {
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
        RemediationPlan.RiskLevel classified = classify(action);
        if (action.plan() != null && action.plan().riskAssessment().level() != classified) {
            throw new PolicyChangedException("当前风险策略将此计划评为 " + classified
                    + "，与计划内冻结的 " + action.plan().riskAssessment().level() + " 不一致；请重新生成计划");
        }
        int approvals = classified == RemediationPlan.RiskLevel.R3 ? r3Approvals : r2Approvals;
        return new Decision(classified, approvals, classified != RemediationPlan.RiskLevel.R3,
                policyVersion, highImpactPercent);
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
