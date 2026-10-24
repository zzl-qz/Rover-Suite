package com.rover.agent.core.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** The exact environment/action/runbook/adapter/effect contract assessed in shadow mode. */
public record ActionCertificationSubject(ResourceScope scope, ActionType actionType, String runbookId,
        String runbookVersion, String adapterVersion, String effectRuleVersion, String metricQueryVersion,
        String controlTargetId) {
    public ActionCertificationSubject {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(actionType, "actionType");
        runbookId = required(runbookId);
        runbookVersion = required(runbookVersion);
        adapterVersion = required(adapterVersion);
        effectRuleVersion = required(effectRuleVersion);
        metricQueryVersion = required(metricQueryVersion);
        controlTargetId = required(controlTargetId);
    }

    public static ActionCertificationSubject from(RemediationPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan.steps().size() != 1 || plan.verificationSpec().mode() != RemediationPlan.VerificationMode.BUSINESS_EFFECT) {
            throw new IllegalArgumentException("only single-step business-effect plans can be certified");
        }
        var provenance = plan.provenance();
        var step = plan.steps().get(0);
        var effect = plan.verificationSpec().businessEffect();
        return new ActionCertificationSubject(plan.scope(), step.actionType(),
                provenance.runbookId() == null ? provenance.source() : provenance.runbookId(),
                provenance.runbookVersion() == null ? provenance.policyVersion() : provenance.runbookVersion(),
                step.adapterVersion(), effect.ruleVersion(), effect.queryTemplateVersion(),
                step.resourceRef().controlTargetId());
    }

    public String fingerprint() {
        String canonical = String.join("", java.util.List.of(scope.organizationId(), scope.teamId(), scope.environmentId(),
                actionType.name(), runbookId, runbookVersion, adapterVersion, effectRuleVersion, metricQueryVersion,
                controlTargetId).stream().map(value -> value.length() + ":" + value).toList());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static String required(String value) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException("certification subject version fields are required and bounded");
        }
        return value.trim();
    }
}
