package com.rover.agent.runtime.action;

import com.rover.agent.core.model.ActionCertificationSubject;
import com.rover.agent.core.model.ActionShadowRun;
import com.rover.agent.core.model.AgentAction;
import com.rover.agent.core.model.RemediationPlan;
import com.rover.agent.core.port.RouteChangePreview;
import com.rover.agent.core.port.RouteControlPort;
import com.rover.agent.core.repository.ActionCertificationRepository;
import com.rover.agent.core.repository.AgentActionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** Runs deterministic, read/preview-only checks against the live plan binding and persists the result. */
public final class ActionShadowAssessmentService {
    private final AgentActionRepository actions;
    private final RouteControlPort control;
    private final ActionCertificationRepository repository;
    private final java.util.function.BooleanSupplier persistentHealthy;

    public ActionShadowAssessmentService(AgentActionRepository actions, RouteControlPort control,
            ActionCertificationRepository repository) {
        this(actions, control, repository, () -> true);
    }

    public ActionShadowAssessmentService(AgentActionRepository actions, RouteControlPort control,
            ActionCertificationRepository repository, java.util.function.BooleanSupplier persistentHealthy) {
        this.actions = actions; this.control = control; this.repository = repository; this.persistentHealthy = persistentHealthy;
    }

    public boolean ready() { return repository != null && persistentHealthy.getAsBoolean(); }

    public ActionShadowRun assess(String actionId, String actor) {
        if (!ready()) { throw new IllegalStateException("certification persistence is unavailable"); }
        if (actionId == null || actionId.isBlank() || actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("action ID and actor are required");
        }
        AgentAction action = actions.find(actionId).orElseThrow(() -> new IllegalArgumentException("action not found"));
        RemediationPlan plan = action.plan();
        if (plan == null) { throw new IllegalArgumentException("action does not have an immutable plan"); }
        ActionCertificationSubject subject = ActionCertificationSubject.from(plan);
        String detail = "影子检查通过：只读路由状态、校验计划版本和身份，并获取写入预览；没有调用变更接口";
        ActionShadowRun.Outcome outcome = ActionShadowRun.Outcome.PASS;
        int revision = -1;
        String previewHash = "";
        try {
            plan.requireMatches(action);
            if (plan.expiresAtMillis() <= System.currentTimeMillis()) {
                throw new IllegalStateException("plan has expired and must be refreshed");
            }
            if (!"RULE_EVIDENCE_GATE".equals(plan.provenance().source()) || plan.evidenceRefs().isEmpty()
                    || plan.riskAssessment().level() != RemediationPlan.RiskLevel.R2
                    || plan.riskAssessment().approvalMode() != RemediationPlan.ApprovalMode.REQUIRE_APPROVAL) {
                throw new IllegalStateException("plan lacks the approved evidence-gated shadow prerequisites");
            }
            var step = plan.steps().get(0);
            var state = control.state();
            revision = state.revision();
            var route = state.routes().stream().filter(item -> item.routeId().equals(step.routeId()))
                    .findFirst().orElse(null);
            if (route == null) {
                throw new IllegalStateException("managed route identity was removed");
            }
            if (!step.resourceRef().equals(route.resourceRef())) {
                throw new IllegalStateException("managed resource UID, generation, scope, or control target changed");
            }
            if (revision != step.expectedRevision()) {
                throw new IllegalStateException("managed route revision changed");
            }
            var target = route.targets().stream().filter(item -> item.serviceName().equals(step.serviceName())
                    && item.group().equals(step.group())).findFirst().orElse(null);
            if (target == null || target.weight() != step.beforeWeight() || route.targets().stream()
                    .filter(item -> !item.serviceName().equals(step.serviceName()) || !item.group().equals(step.group()))
                    .noneMatch(item -> item.weight() > 0)) {
                throw new IllegalStateException("target weight or alternate traffic capacity changed");
            }
            RouteChangePreview preview = control.shadowPreview(step.routeId(), step.serviceName(), step.group(), step.desiredWeight());
            if (preview.revision() != revision || preview.changes().isEmpty()) {
                throw new IllegalStateException("gateway preview is empty or based on a different revision");
            }
            previewHash = sha256(String.join("\n", preview.changes()));
        } catch (RuntimeException failed) {
            outcome = ActionShadowRun.Outcome.FAIL;
            detail = safe(failed);
        }
        long observedAt = Math.max(System.currentTimeMillis(), repository.recentShadowRuns(subject.fingerprint(), 1)
                .stream().mapToLong(ActionShadowRun::observedAtMillis).max().orElse(0L) + 1);
        ActionShadowRun run = new ActionShadowRun(UUID.randomUUID().toString(), subject, actionId, plan.planVersion(),
                plan.planHash(), outcome, detail, revision, previewHash, observedAt, actor);
        return repository.saveShadow(run);
    }

    private static String safe(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) { return failure.getClass().getSimpleName(); }
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
}
