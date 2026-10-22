package com.rover.agent.core.model;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 不可变处置意图；运行状态留在执行记录中。构造与反序列化都校验内容和摘要。
 * steps、alternatives、指标判据和补偿步骤有序；证据、假设、原因和检查项按集合规范化。
 * 哈希格式 remediation-plan-v1：显式字段顺序、UTF-8 长度前缀、固定宽度数字、显式 null。
 */
public record RemediationPlan(String planId, int planVersion, String sessionId, String incidentId,
                              String taskId, String requestedBy, ResourceScope scope, String goal,
                              List<String> alternatives, List<String> evidenceRefs,
                              List<String> unresolvedAssumptions, List<RouteWeightStep> steps,
                              RiskAssessment riskAssessment, ResourceBudget resourceBudget,
                              VerificationSpec verificationSpec, CompensationSpec compensationSpec,
                              long timeoutMillis, long createdAtMillis, long expiresAtMillis,
                              Provenance provenance, String planHash) {

    public static final String ROUTE_WEIGHT_ADAPTER_VERSION = "route-weight-v1";
    public static final String ADAPTER_VERSION = ROUTE_WEIGHT_ADAPTER_VERSION;
    public static final String POLICY_VERSION = "route-weight-manual-v1";
    public static final String CONTRACT_VERSION = "remediation-plan-v1";

    public RemediationPlan {
        planId = required(planId, "planId");
        sessionId = required(sessionId, "sessionId");
        incidentId = optional(incidentId);
        taskId = optional(taskId);
        requestedBy = optional(requestedBy);
        Objects.requireNonNull(scope, "scope");
        goal = required(goal, "goal");
        alternatives = textList(alternatives, false);
        evidenceRefs = textList(evidenceRefs, true);
        unresolvedAssumptions = textList(unresolvedAssumptions, true);
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        Objects.requireNonNull(riskAssessment, "riskAssessment");
        Objects.requireNonNull(resourceBudget, "resourceBudget");
        Objects.requireNonNull(verificationSpec, "verificationSpec");
        Objects.requireNonNull(compensationSpec, "compensationSpec");
        Objects.requireNonNull(provenance, "provenance");
        check(planVersion > 0, "planVersion 必须为正数");
        check(!steps.isEmpty() && steps.size() <= 100, "计划必须有 1~100 个有序步骤");
        check(createdAtMillis > 0 && expiresAtMillis > createdAtMillis && timeoutMillis > 0,
                "计划创建时间、有效期和超时无效");
        check(timeoutMillis <= expiresAtMillis - createdAtMillis, "超时不能超过计划有效期");
        check(resourceBudget.maxActions() >= steps.size(), "动作预算不足");
        double cumulativeShift = 0;
        for (int index = 0; index < steps.size(); index++) {
            RouteWeightStep step = steps.get(index);
            check(step.sequence() == index + 1, "步骤序号必须从 1 连续递增");
            check(scope.equals(step.resourceRef().scope()), "步骤资源不在计划范围内");
            cumulativeShift += step.beforeTrafficPercent() < 0 || step.desiredTrafficPercent() < 0
                    ? 100 : Math.abs(step.desiredTrafficPercent() - step.beforeTrafficPercent());
        }
        check(cumulativeShift <= resourceBudget.maxCumulativeTrafficShiftPercent() + 0.000001,
                "累计流量变化超出计划预算；未知占比按 100% 计算");
        check(compensationSpec.restoreSteps().size() == steps.size(), "补偿必须准确对应本计划步骤");
        for (int index = 0; index < steps.size(); index++) {
            RouteWeightStep original = steps.get(steps.size() - 1 - index);
            RestoreWeightStep restore = compensationSpec.restoreSteps().get(index);
            check(restore.sourceSequence() == original.sequence()
                    && restore.resourceRef().equals(original.resourceRef())
                    && restore.serviceName().equals(original.serviceName())
                    && restore.group().equals(original.group())
                    && restore.restoreWeight() == original.beforeWeight()
                    && restore.expectedAfterWeight() == original.desiredWeight(),
                    "补偿只能逆序恢复本计划触及的权重");
        }
        Canonical canonical = new Canonical();
        canonical.text(CONTRACT_VERSION).text(planId).number(planVersion).text(sessionId).text(incidentId)
                .text(taskId).text(requestedBy);
        writeScope(canonical, scope);
        canonical.text(goal).texts(alternatives).texts(evidenceRefs).texts(unresolvedAssumptions)
                .number(steps.size());
        steps.forEach(step -> step.write(canonical));
        riskAssessment.write(canonical);
        resourceBudget.write(canonical);
        verificationSpec.write(canonical);
        compensationSpec.write(canonical);
        canonical.number(timeoutMillis).number(createdAtMillis).number(expiresAtMillis);
        provenance.write(canonical);
        String expectedHash = canonical.finish();
        check(planHash == null || expectedHash.equals(planHash), "planHash 与不可变计划内容不匹配");
        planHash = expectedHash;
    }

    /** 现有单步动作的服务端计划入口；不凭空生成业务恢复阈值或容量证据。 */
    public static RemediationPlan forRouteWeight(AgentAction action, String planId, int version,
                                                 long expiresAtMillis) {
        return forRouteWeight(action, planId, version, expiresAtMillis, List.of(),
                new Provenance(null, null, null, POLICY_VERSION, "SERVER_ACTION_PREVIEW"));
    }

    /** 生成带调查证据引用的版本；证据 ID 与候选意图一并进入 planHash。 */
    public static RemediationPlan forRouteWeight(AgentAction action, String planId, int version,
                                                 long expiresAtMillis, List<String> evidenceRefs,
                                                 Provenance provenance) {
        return forRouteWeight(action, planId, version, expiresAtMillis, evidenceRefs, provenance, RiskLevel.R2);
    }

    /** Risk is classified by the server from the frozen impact estimate before the plan hash is calculated. */
    public static RemediationPlan forRouteWeight(AgentAction action, String planId, int version,
                                                 long expiresAtMillis, List<String> evidenceRefs,
                                                 Provenance provenance, RiskLevel riskLevel) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(riskLevel, "riskLevel");
        ResourceRef ref = Objects.requireNonNull(action.resourceRef(), "计划必须绑定资源 UID");
        RouteWeightStep step = new RouteWeightStep(1, action.type(), ADAPTER_VERSION, ref,
                action.routeId(), action.businessPrefix(), action.serviceName(), action.group(),
                action.beforeWeight(), action.desiredWeight(), action.requestedValue(), action.requestedUnit(),
                action.beforeTrafficPercent(), action.desiredTrafficPercent(), action.expectedRevision(),
                List.of(RequiredCheck.TARGET_IDENTITY, RequiredCheck.ROUTE_BINDING,
                        RequiredCheck.EXPECTED_REVISION, RequiredCheck.EXPECTED_BEFORE_WEIGHT),
                "将指定路由目标权重变更到期望值；服务恢复效果需独立观察");
        return new RemediationPlan(planId, version, action.sessionId(), action.incidentId(), action.taskId(),
                action.requestedBy(), ref.scope(), "调整 " + action.targetLabel() + " 的路由权重",
                List.of("保持当前权重并继续调查", "由人工核对容量与影响后决定是否执行"), evidenceRefs,
                List.of("尚未建立容量安全证据", "尚未配置业务恢复判据，控制面回读不代表服务恢复"),
                List.of(step), new RiskAssessment(riskLevel, ApprovalMode.REQUIRE_APPROVAL, false,
                        List.of("流量会在同一路由的目标之间重新分配",
                                riskLevel == RiskLevel.R3 ? "预计影响达到高影响阈值，要求独立双人审批"
                                        : "剩余容量尚未获得证据确认"),
                        "指定路由的目标权重及其承接流量"),
                new ResourceBudget(1, 1, 0, 100),
                new VerificationSpec(VerificationMode.CONTROL_PLANE_ONLY,
                        List.of(ControlCheck.TARGET_UID_AND_GENERATION, ControlCheck.EXPECTED_WEIGHT,
                                ControlCheck.APPLIED_REVISION), null),
                new CompensationSpec(CompensationMode.REQUIRE_SEPARATE_APPROVAL,
                        List.of(new RestoreWeightStep(1, ref, action.serviceName(), action.group(),
                                action.beforeWeight(), action.desiredWeight(),
                                VersionGuard.MATCH_ORIGINAL_APPLIED_REVISION,
                                List.of(RequiredCheck.TARGET_IDENTITY, RequiredCheck.EXPECTED_REVISION,
                                        RequiredCheck.EXPECTED_BEFORE_WEIGHT)))),
                Math.min(60_000, expiresAtMillis - action.createdAtMillis()), action.createdAtMillis(),
                expiresAtMillis, provenance, null);
    }

    /** Bind an explicit service-effect policy to the immutable plan without changing its action parameters. */
    public RemediationPlan withBusinessEffect(BusinessEffectSpec spec) {
        return new RemediationPlan(planId, planVersion, sessionId, incidentId, taskId, requestedBy, scope, goal,
                alternatives, evidenceRefs, unresolvedAssumptions, steps, riskAssessment, resourceBudget,
                new VerificationSpec(VerificationMode.BUSINESS_EFFECT, verificationSpec.controlChecks(), spec),
                compensationSpec, timeoutMillis, createdAtMillis, expiresAtMillis, provenance, null);
    }

    /** A preauthorization is part of the immutable plan hash and is rechecked by the execution worker. */
    public RemediationPlan withPreauthorization(String policyVersion) {
        String binding = required(policyVersion, "policyVersion");
        return new RemediationPlan(planId, planVersion, sessionId, incidentId, taskId, requestedBy, scope,
                goal, alternatives, evidenceRefs, unresolvedAssumptions, steps,
                new RiskAssessment(riskAssessment.level(), ApprovalMode.PREAUTHORIZED,
                        riskAssessment.capacityEvidenceAvailable(),
                        withReason(riskAssessment.reasons(), "命中精确范围、短期有效的预授权策略 " + binding),
                        riskAssessment.blastRadius()), resourceBudget, verificationSpec, compensationSpec,
                timeoutMillis, createdAtMillis, expiresAtMillis,
                new Provenance(provenance.runbookId(), provenance.runbookVersion(), provenance.modelVersion(),
                        binding, provenance.source()), null);
    }

    /** 防止兼容执行器读取到与批准计划不同的扁平参数；不读取 action.plan。 */
    public void requireMatches(AgentAction action) {
        Objects.requireNonNull(action, "action");
        check(steps.size() == 1, "当前动作执行器只支持单步计划");
        RouteWeightStep step = steps.get(0);
        check(Objects.equals(sessionId, action.sessionId()) && Objects.equals(incidentId, action.incidentId())
                && Objects.equals(taskId, action.taskId()) && Objects.equals(requestedBy, action.requestedBy())
                && createdAtMillis == action.createdAtMillis() && step.actionType() == action.type()
                && step.resourceRef().equals(action.resourceRef()) && step.routeId().equals(action.routeId())
                && step.businessPrefix().equals(action.businessPrefix())
                && step.serviceName().equals(action.serviceName()) && step.group().equals(normalGroup(action.group()))
                && step.beforeWeight() == action.beforeWeight() && step.desiredWeight() == action.desiredWeight()
                && step.requestedValue() == action.requestedValue() && step.requestedUnit() == action.requestedUnit()
                && Double.compare(step.beforeTrafficPercent(), action.beforeTrafficPercent()) == 0
                && Double.compare(step.desiredTrafficPercent(), action.desiredTrafficPercent()) == 0
                && step.expectedRevision() == action.expectedRevision(), "动作字段与不可变计划不匹配");
    }

    public enum RiskLevel { R2, R3 }
    public enum ApprovalMode { REQUIRE_APPROVAL, PREAUTHORIZED }
    public enum VerificationMode { CONTROL_PLANE_ONLY, BUSINESS_EFFECT }
    public enum CompensationMode { REQUIRE_SEPARATE_APPROVAL }
    public enum VersionGuard { MATCH_ORIGINAL_APPLIED_REVISION }
    public enum MissingDataPolicy { INCONCLUSIVE_STOP_WRITES }
    public enum RequiredCheck {
        TARGET_IDENTITY, ROUTE_BINDING, EXPECTED_REVISION, EXPECTED_BEFORE_WEIGHT,
        REMAINING_TARGET_READY, CAPACITY_EVIDENCE, EVIDENCE_FRESHNESS
    }
    public enum ControlCheck { TARGET_UID_AND_GENERATION, EXPECTED_WEIGHT, APPLIED_REVISION }

    public record RouteWeightStep(int sequence, ActionType actionType, String adapterVersion, ResourceRef resourceRef,
                                  String routeId, String businessPrefix, String serviceName, String group,
                                  int beforeWeight, int desiredWeight, int requestedValue, WeightRequestUnit requestedUnit,
                                  double beforeTrafficPercent, double desiredTrafficPercent, int expectedRevision,
                                  List<RequiredCheck> requiredChecks, String expectedEffect) {
        public RouteWeightStep {
            check(sequence > 0, "步骤序号必须为正数");
            check(actionType == ActionType.ADJUST_ROUTE_TARGET_WEIGHT, "动作类型未注册");
            adapterVersion = required(adapterVersion, "adapterVersion");
            Objects.requireNonNull(resourceRef, "resourceRef");
            routeId = required(routeId, "routeId");
            businessPrefix = required(businessPrefix, "businessPrefix");
            serviceName = required(serviceName, "serviceName");
            group = normalGroup(group);
            check(resourceRef.resourceId().equals(routeId), "路由 ID 必须与资源 ID 一致");
            weight(beforeWeight); weight(desiredWeight);
            check(beforeWeight != desiredWeight, "步骤不能是无变化操作");
            check(requestedUnit != null, "权重单位必须明确声明");
            if (requestedUnit == WeightRequestUnit.RAW_WEIGHT) {
                weight(requestedValue);
                check(requestedValue == desiredWeight, "原始权重请求必须与目标权重一致");
            } else {
                check(requestedValue >= 0 && requestedValue < 100, "流量百分比必须为 0~99");
                // Gateway 接受整数权重，实际占比会有舍入差异；两者必须都保留供审批。
                check(desiredTrafficPercent >= 0 && (requestedValue == 0 ? desiredWeight == 0 : desiredWeight > 0),
                        "百分比请求与实际权重不一致或缺少预计占比");
            }
            percent(beforeTrafficPercent); percent(desiredTrafficPercent);
            check(expectedRevision >= 0, "期望版本不能为负数");
            requiredChecks = enumSet(requiredChecks);
            check(requiredChecks.containsAll(List.of(RequiredCheck.TARGET_IDENTITY, RequiredCheck.EXPECTED_REVISION,
                    RequiredCheck.EXPECTED_BEFORE_WEIGHT)), "必须检查资源身份、版本和变更前值");
            expectedEffect = required(expectedEffect, "expectedEffect");
        }
        private void write(Canonical out) {
            out.number(sequence).text(actionType.name()).text(adapterVersion);
            writeResource(out, resourceRef);
            out.text(routeId).text(businessPrefix).text(serviceName).text(group).number(beforeWeight)
                    .number(desiredWeight).number(requestedValue).text(requestedUnit.name())
                    .decimal(beforeTrafficPercent).decimal(desiredTrafficPercent).number(expectedRevision)
                    .enums(requiredChecks).text(expectedEffect);
        }
    }

    public record RiskAssessment(RiskLevel level, ApprovalMode approvalMode, boolean capacityEvidenceAvailable,
                                  List<String> reasons, String blastRadius) {
        public RiskAssessment {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(approvalMode, "approvalMode");
            reasons = textList(reasons, true);
            check(!reasons.isEmpty(), "风险评估必须包含依据与未知项");
            blastRadius = required(blastRadius, "blastRadius");
        }
        private void write(Canonical out) {
            out.text(level.name()).text(approvalMode.name()).bool(capacityEvidenceAvailable).texts(reasons).text(blastRadius);
        }
    }

    public record ResourceBudget(int maxActions, int maxCompensations, long cooldownMillis,
                                  double maxCumulativeTrafficShiftPercent) {
        public ResourceBudget {
            check(maxActions > 0 && maxActions <= 100 && maxCompensations >= 0 && maxCompensations <= 100
                    && cooldownMillis >= 0, "资源预算无效");
            check(Double.isFinite(maxCumulativeTrafficShiftPercent) && maxCumulativeTrafficShiftPercent >= 0
                    && maxCumulativeTrafficShiftPercent <= 10_000, "累计流量预算无效");
        }
        private void write(Canonical out) {
            out.number(maxActions).number(maxCompensations).number(cooldownMillis).decimal(maxCumulativeTrafficShiftPercent);
        }
    }

    /** BUSINESS_EFFECT 的所有窗口/阈值必须显式提供；默认单步计划只验证控制面。 */
    public record VerificationSpec(VerificationMode mode, List<ControlCheck> controlChecks,
                                    BusinessEffectSpec businessEffect) {
        public VerificationSpec {
            Objects.requireNonNull(mode, "mode");
            controlChecks = enumSet(controlChecks);
            check(controlChecks.containsAll(List.of(ControlCheck.values())), "控制面验证不能省略身份、权重或版本");
            check((mode == VerificationMode.BUSINESS_EFFECT) == (businessEffect != null), "业务验证模式与判据不一致");
        }
        private void write(Canonical out) {
            out.text(mode.name()).enums(controlChecks).bool(businessEffect != null);
            if (businessEffect != null) businessEffect.write(out);
        }
    }

    public record BusinessEffectSpec(String ruleVersion, String queryTemplateVersion, String metricScope,
                                      long baselineMillis, long propagationGraceMillis, long windowMillis,
                                      int consecutiveWindows, long minimumRequestsPerWindow, double minimumCoverage,
                                      long maximumObservationMillis, List<MetricCriterion> criteria,
                                      List<MetricGuardrail> guardrails, MissingDataPolicy missingDataPolicy,
                                      String escalation) {
        public BusinessEffectSpec {
            ruleVersion = required(ruleVersion, "ruleVersion");
            queryTemplateVersion = required(queryTemplateVersion, "queryTemplateVersion");
            metricScope = required(metricScope, "metricScope");
            check(baselineMillis > 0 && propagationGraceMillis >= 0 && windowMillis > 0
                    && consecutiveWindows > 0 && minimumRequestsPerWindow > 0, "业务观察窗口或样本量无效");
            check(Double.isFinite(minimumCoverage) && minimumCoverage > 0 && minimumCoverage <= 1,
                    "最小覆盖率必须为 (0,1]");
            check(maximumObservationMillis > propagationGraceMillis
                    && maximumObservationMillis <= 7L * 24 * 3_600_000
                    && baselineMillis <= 7L * 24 * 3_600_000
                    && windowMillis <= (maximumObservationMillis - propagationGraceMillis) / consecutiveWindows,
                    "最长观察时间不能容纳所需窗口");
            check(baselineMillis >= windowMillis, "变更前基线时长不能短于一个观察窗口");
            criteria = List.copyOf(Objects.requireNonNull(criteria, "criteria"));
            guardrails = List.copyOf(Objects.requireNonNull(guardrails, "guardrails"));
            check(!criteria.isEmpty() && !guardrails.isEmpty(), "业务效果必须有恢复判据和护栏");
            Objects.requireNonNull(missingDataPolicy, "missingDataPolicy");
            escalation = required(escalation, "escalation");
        }
        private void write(Canonical out) {
            out.text(ruleVersion).text(queryTemplateVersion).text(metricScope).number(baselineMillis)
                    .number(propagationGraceMillis).number(windowMillis).number(consecutiveWindows)
                    .number(minimumRequestsPerWindow).decimal(minimumCoverage).number(maximumObservationMillis)
                    .number(criteria.size());
            criteria.forEach(value -> value.write(out));
            out.number(guardrails.size()); guardrails.forEach(value -> value.write(out));
            out.text(missingDataPolicy.name()).text(escalation);
        }
    }

    public record MetricCriterion(String metric, String unit, double recoveryUpperBound,
                                   double minimumRelativeImprovementPercent) {
        public MetricCriterion {
            metric = required(metric, "metric"); unit = required(unit, "unit");
            check(Double.isFinite(recoveryUpperBound) && recoveryUpperBound >= 0, "恢复阈值必须是非负有限数");
            check(Double.isFinite(minimumRelativeImprovementPercent) && minimumRelativeImprovementPercent >= 0
                    && minimumRelativeImprovementPercent <= 100, "改善比例必须为 0~100");
        }
        private void write(Canonical out) {
            out.text(metric).text(unit).decimal(recoveryUpperBound).decimal(minimumRelativeImprovementPercent);
        }
    }

    public record MetricGuardrail(String metric, String unit, Double minimumValue, Double maximumValue) {
        public MetricGuardrail {
            metric = required(metric, "metric"); unit = required(unit, "unit");
            check(minimumValue != null || maximumValue != null, "护栏至少需要一个边界");
            check(minimumValue == null || Double.isFinite(minimumValue), "护栏下界必须有限");
            check(maximumValue == null || Double.isFinite(maximumValue), "护栏上界必须有限");
            check(minimumValue == null || maximumValue == null || minimumValue <= maximumValue, "护栏上下界颠倒");
        }
        private void write(Canonical out) {
            out.text(metric).text(unit).nullableDecimal(minimumValue).nullableDecimal(maximumValue);
        }
    }

    public record CompensationSpec(CompensationMode mode, List<RestoreWeightStep> restoreSteps) {
        public CompensationSpec {
            Objects.requireNonNull(mode, "mode");
            restoreSteps = List.copyOf(Objects.requireNonNull(restoreSteps, "restoreSteps"));
            check(!restoreSteps.isEmpty(), "补偿步骤不能为空");
        }
        private void write(Canonical out) {
            out.text(mode.name()).number(restoreSteps.size());
            restoreSteps.forEach(step -> step.write(out));
        }
    }

    /** 版本来自原执行的已确认回执；不在提案时猜测 apply 后 revision。 */
    public record RestoreWeightStep(int sourceSequence, ResourceRef resourceRef, String serviceName, String group,
                                     int restoreWeight, int expectedAfterWeight, VersionGuard versionGuard,
                                     List<RequiredCheck> requiredChecks) {
        public RestoreWeightStep {
            check(sourceSequence > 0, "补偿来源步骤无效");
            Objects.requireNonNull(resourceRef, "resourceRef");
            serviceName = required(serviceName, "serviceName"); group = normalGroup(group);
            weight(restoreWeight); weight(expectedAfterWeight);
            Objects.requireNonNull(versionGuard, "versionGuard");
            requiredChecks = enumSet(requiredChecks);
            check(requiredChecks.containsAll(List.of(RequiredCheck.TARGET_IDENTITY, RequiredCheck.EXPECTED_REVISION,
                    RequiredCheck.EXPECTED_BEFORE_WEIGHT)), "补偿必须检查身份、原执行生效版本和期望后值");
        }
        private void write(Canonical out) {
            out.number(sourceSequence); writeResource(out, resourceRef);
            out.text(serviceName).text(group).number(restoreWeight).number(expectedAfterWeight)
                    .text(versionGuard.name()).enums(requiredChecks);
        }
    }

    /** modelVersion 为 null 表示此计划由服务端规则生成，不伪造模型来源。 */
    public record Provenance(String runbookId, String runbookVersion, String modelVersion,
                               String policyVersion, String source) {
        public Provenance {
            runbookId = optional(runbookId); runbookVersion = optional(runbookVersion); modelVersion = optional(modelVersion);
            check((runbookId == null) == (runbookVersion == null), "Runbook ID 和版本必须同时提供");
            policyVersion = required(policyVersion, "policyVersion"); source = required(source, "source");
        }
        private void write(Canonical out) {
            out.text(runbookId).text(runbookVersion).text(modelVersion).text(policyVersion).text(source);
        }
    }

    private static String required(String value, String field) {
        check(value != null && !value.isBlank() && value.length() <= 8192, field + " 必须为非空文本且不超过 8192 字符");
        return value.trim();
    }
    private static String optional(String value) { return value == null || value.isBlank() ? null : required(value, "optional"); }
    private static String normalGroup(String group) { return group == null ? "" : group.trim(); }
    private static List<String> withReason(List<String> reasons, String reason) {
        List<String> next = new ArrayList<>(reasons);
        next.add(reason);
        return List.copyOf(next);
    }
    private static void weight(int value) { check(value >= 0 && value <= AgentAction.MAX_WEIGHT, "权重必须为 0~10000"); }
    private static void percent(double value) {
        check(Double.isFinite(value) && (value == -1 || value >= 0 && value <= 100), "占比必须为 0~100 或 -1（未知）");
    }
    private static void check(boolean accepted, String message) {
        if (!accepted) throw new IllegalArgumentException(message);
    }
    private static List<String> textList(List<String> values, boolean set) {
        if (values == null) return List.of();
        List<String> result = values.stream().map(value -> required(value, "list item")).toList();
        return set ? result.stream().distinct().sorted().toList() : result;
    }
    private static <E extends Enum<E>> List<E> enumSet(List<E> values) {
        Objects.requireNonNull(values, "checks");
        List<E> copy = new ArrayList<>(new HashSet<>(List.copyOf(values)));
        copy.sort(Comparator.comparing(Enum::name));
        return List.copyOf(copy);
    }
    private static void writeScope(Canonical out, ResourceScope scope) {
        out.text(scope.organizationId()).text(scope.teamId()).text(scope.environmentId());
    }
    private static void writeResource(Canonical out, ResourceRef resource) {
        writeScope(out, resource.scope());
        out.text(resource.controlTargetId()).text(resource.kind()).text(resource.resourceId())
                .text(resource.resourceUid()).number(resource.generation());
    }

    private static final class Canonical {
        private final MessageDigest digest;
        private Canonical() {
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
        private Canonical number(long value) { digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array()); return this; }
        private Canonical bool(boolean value) { digest.update((byte) (value ? 1 : 0)); return this; }
        private Canonical decimal(double value) { return number(Double.doubleToLongBits(value)); }
        private Canonical nullableDecimal(Double value) { bool(value != null); if (value != null) decimal(value); return this; }
        private Canonical text(String value) {
            if (value == null) return number(-1);
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            number(bytes.length); digest.update(bytes); return this;
        }
        private Canonical texts(List<String> values) { number(values.size()); values.forEach(this::text); return this; }
        private Canonical enums(List<? extends Enum<?>> values) {
            number(values.size()); values.forEach(value -> text(value.name())); return this;
        }
        private String finish() { return HexFormat.of().formatHex(digest.digest()); }
    }
}
