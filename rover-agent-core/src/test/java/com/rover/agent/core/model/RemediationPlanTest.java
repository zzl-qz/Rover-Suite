package com.rover.agent.core.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.rover.agent.core.model.RemediationPlan.*;
import static org.junit.jupiter.api.Assertions.*;

class RemediationPlanTest {
    private static final ResourceScope SCOPE = new ResourceScope("org", "team", "production");
    private static final ResourceRef RESOURCE = new ResourceRef(SCOPE, "gateway", "GATEWAY_ROUTE", "orders", "uid-1", 1);
    private static final List<RequiredCheck> CHECKS = List.of(RequiredCheck.TARGET_IDENTITY,
            RequiredCheck.EXPECTED_REVISION, RequiredCheck.EXPECTED_BEFORE_WEIGHT);

    @Test void serverFactoryPreservesIntentWithoutClaimingCapacityOrBusinessRecovery() {
        AgentAction action = action(50, WeightRequestUnit.RAW_WEIGHT, RESOURCE);
        RemediationPlan plan = forRouteWeight(action, "plan-1", 1, 61_000);
        assertEquals(RiskLevel.R2, plan.riskAssessment().level());
        assertFalse(plan.riskAssessment().capacityEvidenceAvailable());
        assertEquals(ApprovalMode.REQUIRE_APPROVAL, plan.riskAssessment().approvalMode());
        assertEquals(VerificationMode.CONTROL_PLANE_ONLY, plan.verificationSpec().mode());
        assertNull(plan.verificationSpec().businessEffect());
        assertNull(plan.provenance().modelVersion());
        assertTrue(plan.evidenceRefs().isEmpty());
        assertFalse(plan.unresolvedAssumptions().isEmpty());
        RestoreWeightStep restore = plan.compensationSpec().restoreSteps().get(0);
        assertEquals(100, restore.restoreWeight());
        assertEquals(50, restore.expectedAfterWeight());
        assertEquals(VersionGuard.MATCH_ORIGINAL_APPLIED_REVISION, restore.versionGuard());
        assertEquals(CompensationMode.REQUIRE_SEPARATE_APPROVAL, plan.compensationSpec().mode());
        assertDoesNotThrow(() -> plan.requireMatches(action));
        assertDoesNotThrow(() -> plan.requireMatches(action.withVersion(8)));
    }

    @Test void canonicalHashIsStableAndChecksProvidedHashes() {
        RemediationPlan plan = plan();
        assertEquals(64, plan.planHash().length());
        assertEquals(plan, new Draft(plan).build(plan.planHash()));
        assertEquals(plan.planHash(), plan().planHash());
        assertThrows(IllegalArgumentException.class, () -> new Draft(plan).build("not-a-sha256"));
        assertThrows(IllegalArgumentException.class, () -> new Draft(plan).build("0".repeat(64)));
        Draft altered = new Draft(plan); altered.expiresAtMillis++;
        assertThrows(IllegalArgumentException.class, () -> altered.build(plan.planHash()));
    }

    @Test void hashBindsUnitResourceGenerationVersionTimeRiskAndProvenance() {
        RemediationPlan original = plan();
        assertNotEquals(original.planHash(), forRouteWeight(action(50, WeightRequestUnit.TRAFFIC_PERCENT, RESOURCE),
                "plan-1", 1, 61_000).planHash());
        assertNotEquals(original.planHash(), forRouteWeight(action(50, WeightRequestUnit.RAW_WEIGHT,
                new ResourceRef(SCOPE, "gateway", "GATEWAY_ROUTE", "orders", "uid-2", 1)), "plan-1", 1, 61_000).planHash());
        assertNotEquals(original.planHash(), forRouteWeight(action(50, WeightRequestUnit.RAW_WEIGHT,
                new ResourceRef(SCOPE, "gateway", "GATEWAY_ROUTE", "orders", "uid-1", 2)), "plan-1", 1, 61_000).planHash());
        assertNotEquals(original.planHash(), forRouteWeight(action(50, WeightRequestUnit.RAW_WEIGHT, RESOURCE),
                "plan-1", 2, 61_000).planHash());
        Draft expiry = new Draft(original); expiry.expiresAtMillis++;
        assertNotEquals(original.planHash(), expiry.build(null).planHash());
        Draft risk = new Draft(original);
        risk.risk = new RiskAssessment(RiskLevel.R3, ApprovalMode.REQUIRE_APPROVAL, false, List.of("更大影响"), "全路由");
        assertNotEquals(original.planHash(), risk.build(null).planHash());
        Draft provenance = new Draft(original);
        provenance.provenance = new Provenance("runbook", "v2", null, "policy-v2", "REVIEWED_RUNBOOK");
        assertNotEquals(original.planHash(), provenance.build(null).planHash());
    }

    @Test void verificationAndCompensationChangesInvalidatePreviousApprovalHash() {
        RemediationPlan original = plan();
        Draft business = new Draft(original);
        business.verification = new VerificationSpec(VerificationMode.BUSINESS_EFFECT,
                original.verificationSpec().controlChecks(), businessEffect(0.01));
        RemediationPlan withBusiness = business.build(null);
        assertNotEquals(original.planHash(), withBusiness.planHash());
        business.verification = new VerificationSpec(VerificationMode.BUSINESS_EFFECT,
                original.verificationSpec().controlChecks(), businessEffect(0.02));
        assertNotEquals(withBusiness.planHash(), business.build(null).planHash());
        RestoreWeightStep restore = original.compensationSpec().restoreSteps().get(0);
        List<RequiredCheck> extra = new ArrayList<>(restore.requiredChecks()); extra.add(RequiredCheck.CAPACITY_EVIDENCE);
        Draft compensation = new Draft(original);
        compensation.compensation = new CompensationSpec(CompensationMode.REQUIRE_SEPARATE_APPROVAL,
                List.of(new RestoreWeightStep(1, RESOURCE, "orders", "v2", 100, 50,
                        VersionGuard.MATCH_ORIGINAL_APPLIED_REVISION, extra)));
        assertNotEquals(original.planHash(), compensation.build(null).planHash());
    }

    @Test void unorderedEvidenceIsCanonicalButAlternativeOrderIsMeaningful() {
        Draft first = new Draft(plan()); first.evidence = List.of("ev-b", "ev-a", "ev-a");
        Draft second = new Draft(plan()); second.evidence = List.of("ev-a", "ev-b");
        assertEquals(first.build(null).planHash(), second.build(null).planHash());
        first.alternatives = List.of("先调查", "再执行"); second.alternatives = List.of("再执行", "先调查");
        assertNotEquals(first.build(null).planHash(), second.build(null).planHash());
    }

    @Test void everyCollectionIsDefensivelyCopied() {
        List<String> evidence = new ArrayList<>(List.of("ev-1"));
        List<String> alternatives = new ArrayList<>(List.of("人工处理"));
        Draft draft = new Draft(plan()); draft.evidence = evidence; draft.alternatives = alternatives;
        List<RouteWeightStep> steps = new ArrayList<>(draft.steps); draft.steps = steps;
        RemediationPlan built = draft.build(null);
        evidence.add("ev-2"); alternatives.clear(); steps.clear();
        assertEquals(List.of("ev-1"), built.evidenceRefs());
        assertEquals(List.of("人工处理"), built.alternatives());
        assertEquals(1, built.steps().size());
        assertThrows(UnsupportedOperationException.class, () -> built.steps().clear());
        assertThrows(UnsupportedOperationException.class, () -> built.steps().get(0).requiredChecks().clear());
        assertThrows(UnsupportedOperationException.class, () -> built.riskAssessment().reasons().clear());
        assertThrows(UnsupportedOperationException.class, () -> built.verificationSpec().controlChecks().clear());
        assertThrows(UnsupportedOperationException.class, () -> built.compensationSpec().restoreSteps().clear());
    }

    @Test void stepOrderAndReverseCompensationAreEnforced() {
        RemediationPlan first = multi("v2", "v3");
        RemediationPlan reverse = multi("v3", "v2");
        assertNotEquals(first.planHash(), reverse.planHash());
        assertEquals(2, first.steps().size());
        assertEquals(2, first.compensationSpec().restoreSteps().get(0).sourceSequence());
        Draft badSequence = new Draft(first);
        badSequence.steps = List.of(first.steps().get(1), first.steps().get(0));
        assertThrows(IllegalArgumentException.class, () -> badSequence.build(null));
        Draft wrongCompensation = new Draft(first);
        wrongCompensation.compensation = new CompensationSpec(CompensationMode.REQUIRE_SEPARATE_APPROVAL,
                List.of(first.compensationSpec().restoreSteps().get(1), first.compensationSpec().restoreSteps().get(0)));
        assertThrows(IllegalArgumentException.class, () -> wrongCompensation.build(null));
        assertThrows(IllegalArgumentException.class, () -> first.requireMatches(action(50, WeightRequestUnit.RAW_WEIGHT, RESOURCE)));
    }

    @Test void badActionTypeUnitsValuesResourceAndNonFiniteNumbersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> step(1, "v2", null, 50, WeightRequestUnit.RAW_WEIGHT, 50, RESOURCE));
        assertThrows(IllegalArgumentException.class, () -> ActionType.valueOf("EXECUTE_SHELL"));
        assertThrows(IllegalArgumentException.class, () -> step(1, "v2", ActionType.ADJUST_ROUTE_TARGET_WEIGHT, 50, null, 50, RESOURCE));
        assertThrows(IllegalArgumentException.class, () -> step(1, "v2", ActionType.ADJUST_ROUTE_TARGET_WEIGHT, 49, WeightRequestUnit.RAW_WEIGHT, 50, RESOURCE));
        assertThrows(IllegalArgumentException.class, () -> step(1, "v2", ActionType.ADJUST_ROUTE_TARGET_WEIGHT, 100, WeightRequestUnit.TRAFFIC_PERCENT, 50, RESOURCE));
        assertThrows(IllegalArgumentException.class, () -> step(1, "v2", ActionType.ADJUST_ROUTE_TARGET_WEIGHT, 50, WeightRequestUnit.RAW_WEIGHT, Double.NaN, RESOURCE));
        assertThrows(IllegalArgumentException.class, () -> step(1, "v2", ActionType.ADJUST_ROUTE_TARGET_WEIGHT, 50, WeightRequestUnit.RAW_WEIGHT, Double.POSITIVE_INFINITY, RESOURCE));
        assertThrows(IllegalArgumentException.class, () -> step(1, "v2", ActionType.ADJUST_ROUTE_TARGET_WEIGHT, 50, WeightRequestUnit.RAW_WEIGHT, 50,
                new ResourceRef(SCOPE, "gateway", "GATEWAY_ROUTE", "another-route", "uid", 1)));
        assertThrows(NullPointerException.class, () -> forRouteWeight(action(50, WeightRequestUnit.RAW_WEIGHT, null), "plan", 1, 61_000));
    }

    @Test void controlsCannotBeRemovedAndBusinessModeNeedsRealCriteria() {
        assertThrows(IllegalArgumentException.class, () -> new VerificationSpec(VerificationMode.CONTROL_PLANE_ONLY, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new VerificationSpec(VerificationMode.BUSINESS_EFFECT,
                List.of(ControlCheck.values()), null));
        assertThrows(IllegalArgumentException.class, () -> new VerificationSpec(VerificationMode.CONTROL_PLANE_ONLY,
                List.of(ControlCheck.values()), businessEffect(0.01)));
        assertThrows(IllegalArgumentException.class, () -> new MetricCriterion("error_rate", "fraction", Double.NaN, 20));
        assertThrows(IllegalArgumentException.class, () -> new MetricGuardrail("requests", "count", null, null));
        assertThrows(IllegalArgumentException.class, () -> new MetricGuardrail("latency", "ms", 100.0, 10.0));
        assertThrows(IllegalArgumentException.class, () -> new RestoreWeightStep(1, RESOURCE, "orders", "v2", 100, 50,
                VersionGuard.MATCH_ORIGINAL_APPLIED_REVISION, List.of(RequiredCheck.TARGET_IDENTITY)));
    }

    @Test void flattenedIntentCannotBeChangedAfterPlanWasCreated() {
        RemediationPlan plan = plan();
        assertThrows(IllegalArgumentException.class, () -> plan.requireMatches(action(60, WeightRequestUnit.RAW_WEIGHT, RESOURCE)));
        assertThrows(IllegalArgumentException.class, () -> plan.requireMatches(action(50, WeightRequestUnit.TRAFFIC_PERCENT, RESOURCE)));
        assertThrows(IllegalArgumentException.class, () -> plan.requireMatches(action(50, WeightRequestUnit.RAW_WEIGHT,
                new ResourceRef(SCOPE, "gateway", "GATEWAY_ROUTE", "orders", "replacement", 2))));
    }

    @Test void scopedIdentityBudgetsAndValidityCannotBeBypassed() {
        Draft expired = new Draft(plan()); expired.expiresAtMillis = expired.base.createdAtMillis();
        assertThrows(IllegalArgumentException.class, () -> expired.build(null));
        Draft underBudget = new Draft(plan()); underBudget.budget = new ResourceBudget(1, 1, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> underBudget.build(null));
        Draft otherScope = new Draft(plan()); otherScope.scope = new ResourceScope("org", "another-team", "production");
        assertThrows(IllegalArgumentException.class, () -> otherScope.build(null));
        assertThrows(IllegalArgumentException.class, () -> new ResourceBudget(1, 1, 0, Double.POSITIVE_INFINITY));
        Draft unrelatedRestore = new Draft(plan());
        unrelatedRestore.compensation = new CompensationSpec(CompensationMode.REQUIRE_SEPARATE_APPROVAL,
                List.of(new RestoreWeightStep(1, RESOURCE, "orders", "other-version", 100, 50,
                        VersionGuard.MATCH_ORIGINAL_APPLIED_REVISION, CHECKS)));
        assertThrows(IllegalArgumentException.class, () -> unrelatedRestore.build(null));
    }

    private static RemediationPlan plan() { return forRouteWeight(action(50, WeightRequestUnit.RAW_WEIGHT, RESOURCE), "plan-1", 1, 61_000); }
    private static AgentAction action(int request, WeightRequestUnit unit, ResourceRef ref) {
        return new AgentAction("action", "session", "incident", "task", ActionType.ADJUST_ROUTE_TARGET_WEIGHT,
                ActionStatus.PENDING_APPROVAL, "orders", "/orders", "orders", "v2", 100, request,
                request, unit, 66.7, 50, 10, null, null, null, "operator", null, 0,
                List.of("preview"), "impact", null, 1000, 1000).withResourceRef(ref);
    }
    private static RouteWeightStep step(int sequence, String group, ActionType type, int requestedValue,
                                        WeightRequestUnit unit, double percent, ResourceRef ref) {
        return new RouteWeightStep(sequence, type, ROUTE_WEIGHT_ADAPTER_VERSION, ref, "orders", "/orders", "orders", group,
                100, 50, requestedValue, unit, 66.7, percent, 10, CHECKS, "变更权重");
    }
    private static RemediationPlan multi(String firstGroup, String secondGroup) {
        Draft draft = new Draft(plan());
        draft.steps = List.of(step(1, firstGroup, ActionType.ADJUST_ROUTE_TARGET_WEIGHT, 50, WeightRequestUnit.RAW_WEIGHT, 50, RESOURCE),
                step(2, secondGroup, ActionType.ADJUST_ROUTE_TARGET_WEIGHT, 50, WeightRequestUnit.RAW_WEIGHT, 50, RESOURCE));
        draft.budget = new ResourceBudget(2, 2, 0, 200);
        draft.compensation = new CompensationSpec(CompensationMode.REQUIRE_SEPARATE_APPROVAL,
                List.of(new RestoreWeightStep(2, RESOURCE, "orders", secondGroup, 100, 50,
                                VersionGuard.MATCH_ORIGINAL_APPLIED_REVISION, CHECKS),
                        new RestoreWeightStep(1, RESOURCE, "orders", firstGroup, 100, 50,
                                VersionGuard.MATCH_ORIGINAL_APPLIED_REVISION, CHECKS)));
        return draft.build(null);
    }
    private static BusinessEffectSpec businessEffect(double upperBound) {
        return new BusinessEffectSpec("orders-policy-v3", "route-errors-v2", "route:orders:all-serving-gateways",
                300_000, 30_000, 60_000, 2, 200, 0.95, 600_000,
                List.of(new MetricCriterion("error_rate", "fraction", upperBound, 50)),
                List.of(new MetricGuardrail("p99", "milliseconds", null, 500.0),
                        new MetricGuardrail("throughput", "requests/second", 10.0, null)),
                MissingDataPolicy.INCONCLUSIVE_STOP_WRITES, "通知值班人员并停止后续写入");
    }
    private static final class Draft {
        private final RemediationPlan base;
        private ResourceScope scope;
        private List<String> alternatives, evidence;
        private List<RouteWeightStep> steps;
        private RiskAssessment risk;
        private ResourceBudget budget;
        private VerificationSpec verification;
        private CompensationSpec compensation;
        private Provenance provenance;
        private long expiresAtMillis;
        private Draft(RemediationPlan base) {
            this.base = base; scope = base.scope(); alternatives = base.alternatives(); evidence = base.evidenceRefs();
            steps = base.steps(); risk = base.riskAssessment(); budget = base.resourceBudget();
            verification = base.verificationSpec(); compensation = base.compensationSpec();
            provenance = base.provenance(); expiresAtMillis = base.expiresAtMillis();
        }
        private RemediationPlan build(String hash) {
            return new RemediationPlan(base.planId(), base.planVersion(), base.sessionId(), base.incidentId(), base.taskId(),
                    base.requestedBy(), scope, base.goal(), alternatives, evidence, base.unresolvedAssumptions(), steps,
                    risk, budget, verification, compensation, base.timeoutMillis(), base.createdAtMillis(), expiresAtMillis,
                    provenance, hash);
        }
    }
}
