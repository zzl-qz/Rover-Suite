package com.rover.agent.runtime.action;

import com.rover.agent.core.model.ActionStatus;
import com.rover.agent.core.model.ActionEffectVerification;
import com.rover.agent.core.model.ActionApproval;
import com.rover.agent.core.model.ActionExecutionCommand;
import com.rover.agent.core.model.ActionType;
import com.rover.agent.core.model.AgentAction;
import com.rover.agent.core.model.RemediationPlan;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.WeightRequestUnit;
import com.rover.agent.core.port.RouteChangePreview;
import com.rover.agent.core.port.AgentAccess;
import com.rover.agent.core.port.AgentAccess.Permission;
import com.rover.agent.core.port.RouteControlPort;
import com.rover.agent.core.port.RouteControlRoute;
import com.rover.agent.core.port.RouteControlState;
import com.rover.agent.core.port.RouteControlTarget;
import com.rover.agent.core.port.MetricReadPort;
import com.rover.agent.core.port.TimelineReadPort;
import com.rover.agent.core.repository.AgentActionRepository;
import com.rover.agent.core.repository.ActionCommandRepository;
import com.rover.agent.core.repository.ActionUpdateConflictException;
import com.rover.agent.core.repository.AgentSessionRepository;
import com.rover.agent.core.repository.ActionEffectVerificationRepository;
import com.rover.agent.runtime.verification.ActionEffectVerificationService;
import com.rover.agent.core.util.Texts;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 管理变更提议、审批、拒绝、回滚、结果确认与用户归属。
 * 提议仅登记；批准和回滚通过 CAS 迁移状态后委托 {@link ActionExecutor}。
 * 非当前用户的变更按不存在处理。
 */
public final class AgentActionService {

    private static final Logger log = LoggerFactory.getLogger(AgentActionService.class);
    /** 待批方案的默认新鲜度窗口；过期后须重新读取资源生成版本。 */
    private static final long PLAN_LIFETIME_MILLIS = 10 * 60_000L;

    private final AgentActionRepository actions;
    private final AgentSessionRepository sessions;
    private final RouteControlPort control;
    private final ActionExecutorRegistry executors;
    private final AgentAccess access;
    private final RiskBasedApprovalPolicy approvalPolicy;
    private final ActionCommandRepository commands;
    private final boolean asynchronousExecution;
    private final ActionEffectVerificationService effectVerification;
    private final com.rover.agent.runtime.verification.BusinessEffectPolicy businessEffectPolicy;

    public AgentActionService(AgentActionRepository actions, AgentSessionRepository sessions,
                              RouteControlPort control, ActionExecutor executor) {
        this(actions, sessions, control, executor, AgentAccess.local());
    }

    public AgentActionService(AgentActionRepository actions, AgentSessionRepository sessions,
                              RouteControlPort control, ActionExecutor executor, AgentAccess access) {
        this(actions, sessions, control, executor, access, RiskBasedApprovalPolicy.defaults());
    }

    public AgentActionService(AgentActionRepository actions, AgentSessionRepository sessions,
                              RouteControlPort control, ActionExecutor executor, AgentAccess access,
                              RiskBasedApprovalPolicy approvalPolicy) {
        this(actions, sessions, control, new ActionExecutorRegistry(List.of(executor)), access, approvalPolicy,
                null, false, null);
    }

    public AgentActionService(AgentActionRepository actions, AgentSessionRepository sessions,
                              RouteControlPort control, ActionExecutorRegistry executors, AgentAccess access,
                              RiskBasedApprovalPolicy approvalPolicy, ActionCommandRepository commands,
                              boolean asynchronousExecution) {
        this(actions, sessions, control, executors, access, approvalPolicy, commands, asynchronousExecution, null);
    }

    public AgentActionService(AgentActionRepository actions, AgentSessionRepository sessions, RouteControlPort control,
                              ActionExecutorRegistry executors, AgentAccess access, RiskBasedApprovalPolicy approvalPolicy,
                              ActionCommandRepository commands, boolean asynchronousExecution,
                              ActionEffectVerificationService effectVerification) {
        this(actions, sessions, control, executors, access, approvalPolicy, commands, asynchronousExecution,
                effectVerification, null);
    }

    public AgentActionService(AgentActionRepository actions, AgentSessionRepository sessions, RouteControlPort control,
                              ActionExecutorRegistry executors, AgentAccess access, RiskBasedApprovalPolicy approvalPolicy,
                              ActionCommandRepository commands, boolean asynchronousExecution,
                              ActionEffectVerificationService effectVerification,
                              com.rover.agent.runtime.verification.BusinessEffectPolicy businessEffectPolicy) {
        this.actions = actions;
        this.sessions = sessions;
        this.control = control;
        this.executors = executors;
        this.access = access;
        this.approvalPolicy = approvalPolicy;
        this.commands = commands;
        this.asynchronousExecution = asynchronousExecution;
        this.effectVerification = effectVerification;
        this.businessEffectPolicy = businessEffectPolicy;
        if (asynchronousExecution && commands == null) {
            throw new IllegalArgumentException("asynchronous action execution requires a command repository");
        }
    }

    // ---------------------------------------------------------------- 提议

    /**
     * 读取路由与目标，换算请求值并登记待审批变更，不提交网关。
     * 单位须显式提供；缺失时要求澄清，百分比按整条路由权重换算。
     *
     * @param sessionId      会话（决定归属与审计）
     * @param taskId         产生这条建议的任务
     * @param incidentId     这条建议挂在哪个事件下；对话还没绑定事件时为空
     * @param route          请求路径或路由前缀，例如 {@code /api/order}
     * @param group          版本分组，例如 {@code v2}
     * @param requestedValue 请求数值（权重值 0~10000，或流量占比 0~100）
     * @param unit           {@code requestedValue} 的单位；为空表示语义不明，需要先澄清
     * @return 提议结果：产出的待审批变更，或一句「为什么不能提 / 需要先问清楚什么」
     */
    public synchronized ActionProposal propose(String sessionId, String taskId, String incidentId, String route, String group,
                                  Integer requestedValue, WeightRequestUnit unit) {
        return propose(sessionId, taskId, incidentId, route, group, requestedValue, unit,
                null, List.of(), false, false, null);
    }

    /**
     * 从通过运行时证据门槛的诊断登记人工审批的版本隔离候选。
     * 此入口只允许把目标版本权重降为 0，并要求留下可审计证据引用与正权重替代目标。
     */
    public synchronized ActionProposal proposeEvidenceGatedQuarantine(String sessionId, String taskId, String incidentId,
                                                         String route, String group, List<String> evidenceRefs) {
        if (evidenceRefs == null || evidenceRefs.isEmpty() || evidenceRefs.size() > 100
                || evidenceRefs.stream().anyMatch(ref -> ref == null || ref.isBlank() || ref.length() > 128)) {
            return ActionProposal.rejected("自动候选缺少有效的调查证据引用");
        }
        var provenance = new RemediationPlan.Provenance(null, null, null,
                "route-weight-evidence-gate-v1", "RULE_EVIDENCE_GATE");
        ActionProposal duplicate = existingAutomaticProposal(sessionId, incidentId, route, group);
        if (duplicate != null) { return duplicate; }
        return propose(sessionId, taskId, incidentId, route, group, 0, WeightRequestUnit.RAW_WEIGHT,
                null, List.copyOf(evidenceRefs), true, true, provenance);
    }

    /** A new investigation task from the same still-open incident must not make another automatic approval card. */
    private ActionProposal existingAutomaticProposal(String sessionId, String incidentId, String route, String group) {
        if (sessionId == null || sessionId.isBlank() || route == null || group == null) { return null; }
        return actions.bySession(sessionId).stream()
                .filter(action -> Objects.equals(incidentId, action.incidentId()) && action.plan() != null
                        && "RULE_EVIDENCE_GATE".equals(action.plan().provenance().source())
                        && route.equals(action.businessPrefix()) && group.equals(action.group())
                        && action.status() != ActionStatus.SUPERSEDED)
                .findFirst().map(ActionProposal::created)
                .orElse(null);
    }

    private synchronized ActionProposal propose(String sessionId, String taskId, String incidentId, String route, String group,
                                   Integer requestedValue, WeightRequestUnit unit, RemediationPlan previousPlan) {
        return propose(sessionId, taskId, incidentId, route, group, requestedValue, unit, previousPlan,
                previousPlan == null ? List.of() : previousPlan.evidenceRefs(), false, false,
                previousPlan == null ? null : previousPlan.provenance());
    }

    private synchronized ActionProposal propose(String sessionId, String taskId, String incidentId, String route, String group,
                                   Integer requestedValue, WeightRequestUnit unit, RemediationPlan previousPlan,
                                   List<String> evidenceRefs, boolean requireAlternativeTarget,
                                   boolean requirePlanResourceIdentity,
                                   RemediationPlan.Provenance provenance) {
        String wantedRoute = Texts.orEmpty(route);
        String wantedGroup = Texts.orEmpty(group);
        if (wantedRoute.isBlank()) {
            return ActionProposal.rejected("需要给出要调整的路由：请求路径或路由前缀，例如 /api/order");
        }
        if (wantedGroup.isBlank()) {
            return ActionProposal.rejected("需要给出要调整的版本分组，例如 v2；不确定有哪些版本时，先查一次这条路由");
        }
        if (unit == null) {
            return ActionProposal.clarificationRequired("「" + (requestedValue == null ? "这个数值" : requestedValue)
                    + "」没有说明单位，不能替用户猜：权重值（路由表里的相对值，0~" + AgentAction.MAX_WEIGHT
                    + "）和流量占比（0~100%）是两种量纲，在当前权重分布下算出来的写入值可能相差两个数量级。"
                    + "请先向用户确认是要把【权重】调到这个值，还是把【流量占比】调到这个百分比，确认后再创建变更计划。");
        }
        if (requestedValue == null) {
            return ActionProposal.rejected("需要给出目标数值");
        }
        if (unit == WeightRequestUnit.RAW_WEIGHT
                && (requestedValue < 0 || requestedValue > AgentAction.MAX_WEIGHT)) {
            return ActionProposal.rejected("权重值必须是 0~" + AgentAction.MAX_WEIGHT
                    + " 之间的整数（0 表示这个版本不再接流量）");
        }
        if (unit == WeightRequestUnit.TRAFFIC_PERCENT && (requestedValue < 0 || requestedValue > 100)) {
            return ActionProposal.rejected("流量占比必须是 0~100 之间的整数");
        }
        Session session = sessions.find(sessionId).orElse(null);
        if (session == null) {
            return ActionProposal.rejected("找不到这个会话，无法登记变更");
        }
        if (!inScope(session)) {
            return ActionProposal.rejected("会话的组织/团队/环境或管理目标已改变，请在当前资产范围新建会话");
        }
        access.require(session.userId(), Permission.PROPOSE);
        RouteControlState state;
        try {
            state = control.state();
        } catch (RuntimeException ex) {
            return ActionProposal.rejected("读取 Gateway 路由失败，暂时不能生成变更计划：" + failureText(ex));
        }
        RouteControlRoute matched = RouteLocator.route(state, wantedRoute, null);
        if (matched == null) {
            return ActionProposal.rejected("Gateway 上没有匹配「" + wantedRoute + "」的路由，"
                    + "请先确认请求路径或路由前缀");
        }
        if (matched.targets().isEmpty()) {
            return ActionProposal.rejected(matched.staticUpstream()
                    ? "路由 " + matched.display() + " 是静态上游地址，没有版本权重可以调整"
                    : "路由 " + matched.display() + " 没有配置版本目标，无法调整权重");
        }
        RouteControlTarget target = RouteLocator.target(matched, "", wantedGroup);
        if (target == null) {
            return ActionProposal.rejected("路由 " + matched.display() + " 上没有「" + wantedGroup
                    + "」这个版本（现有：" + RouteLocator.describeTargets(matched) + "）");
        }
        int totalWeight = matched.targets().stream().mapToInt(RouteControlTarget::weight).sum();
        WeightResolution resolved = resolve(target.weight(), totalWeight, requestedValue, unit);
        if (!resolved.passed()) {
            // 占比无法只靠改这一个目标达成时，同样是「问清楚」，而不是挑一个近似值写下去。
            return ActionProposal.clarificationRequired(resolved.message());
        }
        int desiredWeight = resolved.weight();
        if (requireAlternativeTarget && desiredWeight == 0 && matched.targets().stream()
                .filter(candidate -> !candidate.serviceName().equals(target.serviceName())
                        || !candidate.group().equals(target.group()))
                .noneMatch(candidate -> candidate.weight() > 0)) {
            return ActionProposal.rejected("该版本之外没有正权重目标；归零会使路由失去承接流量的版本");
        }
        if ((control.requiresResourceIdentity() || requirePlanResourceIdentity) && matched.resourceRef() == null) {
            return ActionProposal.rejected("Gateway 未返回可信资源 UID，不能生成可执行变更；请先升级并核对资产身份");
        }
        if (matched.resourceRef() != null && ((session.scope() != null
                && !session.scope().equals(matched.resourceRef().scope()))
                || (control.resourceScope() != null && !control.resourceScope().equals(matched.resourceRef().scope()))
                || (!control.controlTargetId().isBlank()
                    && !control.controlTargetId().equals(matched.resourceRef().controlTargetId()))
                || !"GATEWAY_ROUTE".equals(matched.resourceRef().kind())
                || !matched.routeId().equals(matched.resourceRef().resourceId()))) {
            return ActionProposal.rejected("路由资源身份与当前管理范围不一致，不能生成处置计划");
        }
        if (previousPlan != null && !previousPlan.steps().get(0).resourceRef().equals(matched.resourceRef())) {
            return ActionProposal.rejected("资源身份已改变，请重新调查并创建独立计划");
        }
        if (target.weight() == desiredWeight) {
            return ActionProposal.rejected("「" + target.label() + "」当前权重已经是 " + desiredWeight
                    + "，不需要变更");
        }

        // 同一只读 Job 重启续跑时可再次到达 propose；返回已登记的相同意图，不复制审批卡。
        if (taskId != null && previousPlan == null) {
            AgentAction existing = actions.bySession(sessionId).stream().filter(candidate -> taskId.equals(candidate.taskId())
                    && candidate.status() != ActionStatus.SUPERSEDED
                    && (candidate.plan() == null || candidate.plan().expiresAtMillis() > System.currentTimeMillis())
                    && Objects.equals(candidate.resourceRef(), matched.resourceRef()) && candidate.routeId().equals(matched.routeId())
                    && candidate.group().equals(target.group()) && candidate.serviceName().equals(target.serviceName())
                    && candidate.requestedValue() == requestedValue && candidate.requestedUnit() == unit
                    && candidate.desiredWeight() == desiredWeight && candidate.expectedRevision() == state.revision())
                    .findFirst().orElse(null);
            if (existing != null) {
                if (evidenceRefs.isEmpty() || existing.plan() != null
                        && existing.plan().evidenceRefs().containsAll(evidenceRefs)) {
                    return ActionProposal.created(existing);
                }
                return ActionProposal.rejected("同一任务已有相同变更，但计划没有绑定本次证据；未重复创建审批卡");
            }
        }

        long now = System.currentTimeMillis();
        AgentAction action = new AgentAction(UUID.randomUUID().toString(), sessionId, incidentId, taskId,
                ActionType.ADJUST_ROUTE_TARGET_WEIGHT, ActionStatus.PENDING_APPROVAL,
                matched.routeId(), matched.businessPrefix(), target.serviceName(), target.group(),
                target.weight(), desiredWeight,
                requestedValue, unit,
                percentOf(target.weight(), totalWeight), percentOf(desiredWeight, totalWeight - target.weight() + desiredWeight),
                state.revision(),
                null, null, null,
                session.userId(), null, 0L,
                previewOf(matched, target, desiredWeight), impactOf(target, desiredWeight), null,
                now, now).withResourceRef(matched.resourceRef());
        try {
            if (action.resourceRef() != null) {
                RemediationPlan.RiskLevel riskLevel = approvalPolicy.classify(action);
                RemediationPlan plan = RemediationPlan.forRouteWeight(action,
                        previousPlan == null ? action.actionId() : previousPlan.planId(),
                        previousPlan == null ? 1 : Math.addExact(previousPlan.planVersion(), 1),
                        Math.addExact(now, PLAN_LIFETIME_MILLIS), evidenceRefs,
                        provenance == null ? new RemediationPlan.Provenance(null, null, null,
                                RemediationPlan.POLICY_VERSION, "SERVER_ACTION_PREVIEW") : provenance, riskLevel);
                if (businessEffectPolicy != null) { plan = businessEffectPolicy.attach(plan); }
                var preauthorization = access.automatedWriteAllowed()
                        ? approvalPolicy.preauthorizationFor(action.withPlan(plan), session.targetNamespace(), now) : null;
                if (preauthorization != null) { plan = plan.withPreauthorization(preauthorization.policyVersion()); }
                action = action.withPlan(plan);
            }
            action = actions.save(action);
            if (action.plan() != null
                    && action.plan().riskAssessment().approvalMode() == RemediationPlan.ApprovalMode.PREAUTHORIZED
                    && asynchronousExecution && access.automatedWriteAllowed()) {
                RiskBasedApprovalPolicy.Decision decision = currentPolicy(action);
                String queuedActionId = action.actionId();
                commands.enqueue(command(action, ActionExecutionCommand.Phase.APPLY,
                        "system:preauthorization", decision));
                action = actions.transition(queuedActionId, ActionStatus.PENDING_APPROVAL,
                        current -> new ActionDraft(current).approvedBy("system:preauthorization", System.currentTimeMillis())
                                        .move(ActionStatus.QUEUED, null)
                                        .toAction(System.currentTimeMillis()))
                        .orElseGet(() -> latest(queuedActionId));
            }
        } catch (RuntimeException ex) {
            log.warn("登记待审批变更失败: session={} route={}", sessionId, wantedRoute, ex);
            return ActionProposal.rejected("变更记录暂时无法登记：" + failureText(ex));
        }
        log.info("已登记待审批变更 {}：{}（请求：{}，预计流量占比 {}），期望 revision {}（尚未执行）",
                action.actionId(), action.describe(), action.requestText(), action.trafficPercentText(),
                action.expectedRevision());
        return ActionProposal.created(action);
    }

    /**
     * 将请求值转换为目标权重：w' = o * p / (100 - p)，o 为其他目标权重合计。
     * 无解或超过权重上限时拒绝，不截断近似。
     */
    private static WeightResolution resolve(int currentWeight, int totalWeight, int requestedValue,
                                           WeightRequestUnit unit) {
        if (unit == WeightRequestUnit.RAW_WEIGHT) {
            return WeightResolution.ok(requestedValue);
        }
        int others = totalWeight - currentWeight;
        if (requestedValue >= 100) {
            return WeightResolution.blocked("要把流量占比提到 " + requestedValue
                    + "%，只调整这一个版本做不到：同路由上其他版本当前合计权重为 " + others
                    + "，必须同时把它们降到 0 才行。请先向用户确认是否要同时调整其他版本的权重，"
                    + "或者改为指定一个小于 100% 的占比。");
        }
        if (requestedValue == 0) {
            return WeightResolution.ok(0);
        }
        if (others == 0) {
            return WeightResolution.blocked("同路由上其他版本当前合计权重为 0，只调整这一个版本无法把流量占比改成 "
                    + requestedValue + "%。请先向用户确认是否要同时调整其他版本。");
        }
        long computed = Math.round((double) others * requestedValue / (100.0 - requestedValue));
        if (computed < 1 || computed > AgentAction.MAX_WEIGHT) {
            return WeightResolution.blocked("要把流量占比调到 " + requestedValue
                    + "%，只调整这一个版本做不到：按其他版本合计权重 " + others
                    + " 计算，需要权重 " + computed + "，单目标允许范围是 1~" + AgentAction.MAX_WEIGHT
                    + "。请改为一个当前权重约束下能达到的占比，或同时调整其他版本。");
        }
        return WeightResolution.ok((int) computed);
    }

    /** 某个目标在给定权重分布下的流量占比；保留一位小数，避免浮点噪音出现在审批卡上。 */
    private static double percentOf(int weight, int totalWeight) {
        if (totalWeight <= 0) {
            return 0.0;
        }
        return Math.round(weight * 1000.0 / totalWeight) / 10.0;
    }

    /** 请求值到权重的换算结果，包含权重或失败原因。 */
    private record WeightResolution(boolean passed, int weight, String message) {

        static WeightResolution ok(int weight) {
            return new WeightResolution(true, weight, "");
        }

        static WeightResolution blocked(String message) {
            return new WeightResolution(false, 0, message);
        }
    }

    /** 读取提议阶段的差异预览；失败时保留原因备注，不阻止登记提议。 */
    private List<String> previewOf(RouteControlRoute route, RouteControlTarget target, int desiredWeight) {
        try {
            RouteChangePreview preview = control.preview(route.routeId(), target.serviceName(),
                    target.group(), desiredWeight);
            return preview.changes().isEmpty() ? List.of(preview.message()) : preview.changes();
        } catch (RuntimeException ex) {
            return List.of("预览不可用：" + failureText(ex));
        }
    }

    /** 影响说明：给人一眼看懂「这一步是放量、缩量还是停推」。 */
    private static String impactOf(RouteControlTarget target, int desiredWeight) {
        if (desiredWeight == 0) {
            return "停推：" + target.label() + " 不再接流量（" + target.weight() + " → 0）";
        }
        if (target.weight() == 0) {
            return "恢复流量：" + target.label() + " 重新接流量（0 → " + desiredWeight + "）";
        }
        return (desiredWeight > target.weight() ? "放量：" : "缩量：") + target.label()
                + " 的灰度流量" + (desiredWeight > target.weight() ? "增加" : "减少")
                + "（" + target.weight() + " → " + desiredWeight + "）";
    }

    // ---------------------------------------------------------------- 查询

    /** 某个会话的变更记录（新的在前）；会话不存在或不属于当前用户时为空。 */
    public Optional<List<AgentAction>> bySession(String sessionId, String userId) {
        access.require(userId, Permission.READ);
        return session(sessionId, userId).map(owned -> actions.bySession(sessionId));
    }

    /** 按 ID 取一条变更记录；不存在或不属于当前用户时为空。 */
    public Optional<AgentAction> find(String actionId, String userId) {
        access.require(userId, Permission.READ);
        return actions.find(actionId).filter(action -> ownedBy(action, userId));
    }

    public List<AgentAction> pendingApprovals(String userId) {
        access.require(userId, Permission.APPROVE);
        return sessions.listAll().stream().filter(this::inScope)
                .flatMap(session -> actions.bySession(session.sessionId()).stream())
                .filter(action -> action.status().awaitingApproval() && ownedBy(action, userId)).toList();
    }

    /** System operators need a bounded reconciliation queue across sessions, scoped to this managed target. */
    public List<AgentAction> reconciliationQueue(String userId, int limit) {
        access.require(userId, Permission.MANAGE_SYSTEM);
        if (limit < 1 || limit > 1000) { throw new ActionRequestException(ActionRequestException.Code.INVALID_REQUEST,
                "对账队列条数必须在 1 到 1000 之间"); }
        return actions.byStatus(java.util.Set.of(ActionStatus.UNCERTAIN), limit).stream()
                .filter(action -> sessions.find(action.sessionId()).filter(this::inScope).isPresent()).toList();
    }

    /** System operator requests reconciliation only; RECONCILE uses the recorded original operationId. */
    public AgentAction reconcile(String actionId, String userId) {
        access.require(userId, Permission.MANAGE_SYSTEM);
        AgentAction action = actions.find(actionId).filter(value -> sessions.find(value.sessionId())
                        .filter(this::inScope).isPresent())
                .orElseThrow(() -> new ActionRequestException(ActionRequestException.Code.NOT_FOUND,
                        "找不到当前管理范围内的变更"));
        if (!action.status().resolvable()) {
            throw new ActionRequestException(ActionRequestException.Code.CONFLICT, "此变更当前不需要对账");
        }
        if (asynchronousExecution) {
            RiskBasedApprovalPolicy.Decision decision = currentPolicy(action);
            commands.enqueue(command(action, ActionExecutionCommand.Phase.RECONCILE, userId, decision));
            return actions.transition(actionId, ActionStatus.UNCERTAIN,
                            current -> new ActionDraft(current).move(ActionStatus.RECONCILING, null)
                                    .toAction(System.currentTimeMillis()))
                    .orElseGet(() -> latest(actionId));
        }
        return execute(action, userId);
    }

    /** 历史内容由仓储保留，外部读取仍须通过原会话的当前权限和范围。 */
    public Optional<List<RemediationPlan>> planVersions(String planId, String userId) {
        return find(planId, userId).filter(action -> action.plan() != null
                        && planId.equals(action.plan().planId()))
                .map(action -> actions.planVersions(planId));
    }

    public Optional<RemediationPlan> plan(String planId, int version, String userId) {
        return planVersions(planId, userId).flatMap(plans -> plans.stream()
                .filter(plan -> plan.planVersion() == version).findFirst());
    }

    /** 修订只接受用户意图；资源、换算、风险、有效期和摘要均重新由服务端确定。 */
    public AgentAction revisePlan(String planId, int expectedVersion, String expectedPlanHash,
                                  Integer requestedValue, WeightRequestUnit unit, String userId) {
        access.require(userId, Permission.PROPOSE);
        if (expectedVersion < 1 || expectedPlanHash == null || !expectedPlanHash.matches("[0-9a-f]{64}")
                || requestedValue == null || unit == null || requestedValue < 0
                || requestedValue > (unit == WeightRequestUnit.RAW_WEIGHT ? AgentAction.MAX_WEIGHT : 100)) {
            throw new ActionRequestException(ActionRequestException.Code.INVALID_REQUEST, "需要合法的版本、摘要、数值和明确单位");
        }
        List<RemediationPlan> history = planVersions(planId, userId).orElseThrow(() ->
                new ActionRequestException(ActionRequestException.Code.NOT_FOUND, "找不到处置计划"));
        RemediationPlan latest = history.get(history.size() - 1);
        if (latest.planVersion() != expectedVersion || !latest.planHash().equals(expectedPlanHash)) {
            throw new ActionRequestException(ActionRequestException.Code.CONFLICT, "计划已改变，请刷新后再修改");
        }
        AgentAction previous = actions.bySession(latest.sessionId()).stream()
                .filter(action -> action.plan() != null && latest.equals(action.plan())).findFirst()
                .orElseThrow(() -> new ActionRequestException(ActionRequestException.Code.CONFLICT, "计划对应的动作记录不存在"));
        if (!previous.status().awaitingApproval()) {
            throw new ActionRequestException(ActionRequestException.Code.CONFLICT, "只允许修订尚未批准的最新计划");
        }
        if (previous.requestedValue() == requestedValue && previous.requestedUnit() == unit
                && latest.expiresAtMillis() > System.currentTimeMillis()) {
            throw new ActionRequestException(ActionRequestException.Code.INVALID_REQUEST, "参数没有变化，无需创建新版本");
        }
        ActionProposal result = propose(previous.sessionId(), previous.taskId(), previous.incidentId(),
                previous.routeId(), previous.group(), requestedValue, unit, latest);
        if (!result.created()) {
            throw new ActionRequestException(ActionRequestException.Code.CONFLICT, result.reason());
        }
        return result.action();
    }

    // ---------------------------------------------------------------- 人工动作

    /** 通过 CAS 批准并立即执行，仅成功迁移状态的请求可提交。 */
    public AgentAction approve(String actionId, String userId) {
        AgentAction current = require(actionId, userId);
        return approve(actionId, userId, current.plan() == null ? null : current.plan().planVersion(),
                current.plan() == null ? null : current.plan().planHash());
    }

    /** HTTP 审批必须提交审批人看过的版本和摘要，不能默认批准服务器最新内容。 */
    public AgentAction approve(String actionId, String userId, Integer planVersion, String planHash) {
        access.require(userId, Permission.APPROVE);
        AgentAction pending = require(actionId, userId);
        if (!pending.status().awaitingApproval()) {
            throw new ActionRequestException(ActionRequestException.Code.CONFLICT,
                    "这条变更当前是「" + pending.status().label() + "」，不能再次批准");
        }
        requireApprovablePlan(pending, planVersion, planHash);
        RiskBasedApprovalPolicy.Decision decision = currentPolicy(pending);
        requireCanApprove(pending, userId, decision);
        ActionApproval approval = approval(pending, ActionApproval.Stage.APPLY, decision, userId);
        try {
            actions.recordApproval(approval);
        } catch (ActionUpdateConflictException ex) {
            throw new ActionRequestException(ActionRequestException.Code.CONFLICT,
                    "计划已变化或已被处理，审批没有登记");
        }
        List<ActionApproval> validApprovals = currentApprovals(pending, ActionApproval.Stage.APPLY, decision);
        if (validApprovals.size() < decision.requiredApprovals()) {
            return pending;
        }
        if (asynchronousExecution) {
            commands.enqueue(command(pending, ActionExecutionCommand.Phase.APPLY, userId, decision));
        }
        long now = System.currentTimeMillis();
        String approver = normalize(userId);
        AgentAction claimed = actions.transition(actionId, ActionStatus.PENDING_APPROVAL,
                        current -> {
                            requireApprovablePlan(current, planVersion, planHash);
                            requireSamePolicy(current, decision);
                            if (currentApprovals(current, ActionApproval.Stage.APPLY, decision).size()
                                    < decision.requiredApprovals()) {
                                throw new ActionRequestException(ActionRequestException.Code.CONFLICT,
                                        "审批人数或实时授权已变化，尚不能执行");
                            }
                            return new ActionDraft(current).approvedBy(approver, now)
                                    .move(asynchronousExecution ? ActionStatus.QUEUED : ActionStatus.EXECUTING,
                                            null).toAction(now);
                        })
                .orElseGet(() -> asynchronousExecution ? latest(actionId) : null);
        if (claimed == null) {
            throw new ActionRequestException(ActionRequestException.Code.CONFLICT,
                    "这条变更已经被处理过了，不会重复执行");
        }
        if (asynchronousExecution) { return claimed; }
        log.info("变更 {} 已批准，开始执行：{}（批准人 {}）", actionId, claimed.describe(), approver);
        return execute(claimed, userId);
    }

    private void requireApprovablePlan(AgentAction action, Integer planVersion, String planHash) {
        RemediationPlan plan = action.plan();
        if (plan == null) {
            if (control.requiresResourceIdentity() || access.managed()) {
                throw new ActionRequestException(ActionRequestException.Code.CONFLICT,
                        "旧提案没有不可变计划，请重新生成后批准");
            }
            return;
        }
        if (!Objects.equals(planVersion, plan.planVersion()) || !Objects.equals(planHash, plan.planHash())) {
            throw new ActionRequestException(ActionRequestException.Code.CONFLICT, "审批版本或摘要不一致，请重新查看计划");
        }
        if (plan.expiresAtMillis() <= System.currentTimeMillis()) {
            throw new ActionRequestException(ActionRequestException.Code.CONFLICT, "计划已过期，请刷新资源并生成新版本");
        }
        List<RemediationPlan> history = actions.planVersions(plan.planId());
        if (history.isEmpty() || !history.get(history.size() - 1).equals(plan)) {
            throw new ActionRequestException(ActionRequestException.Code.CONFLICT, "只能批准最新的已保存计划版本");
        }
        try {
            plan.requireMatches(action);
            RouteWeightActionExecutor.requireSupportedPlan(action);
        } catch (IllegalArgumentException ex) {
            throw new ActionRequestException(ActionRequestException.Code.CONFLICT, ex.getMessage());
        }
    }

    /** Current risk and approval rules are recomputed at every approval boundary. */
    public ApprovalProgress approvalProgress(String actionId, String userId, ActionApproval.Stage stage) {
        access.require(userId, Permission.APPROVE);
        AgentAction action = require(actionId, userId);
        if (stage == ActionApproval.Stage.APPLY && !action.status().awaitingApproval()
                || stage == ActionApproval.Stage.ROLLBACK && !action.status().reversible()) {
            throw new ActionRequestException(ActionRequestException.Code.CONFLICT,
                    "该变更当前没有此阶段的待审批操作");
        }
        return approvalProgress(action, stage);
    }

    public List<ApprovalProgress> approvalProgressBySession(String sessionId, String userId) {
        access.require(userId, Permission.READ);
        List<AgentAction> sessionActions = bySession(sessionId, userId).orElseThrow(() ->
                new ActionRequestException(ActionRequestException.Code.NOT_FOUND, "找不到这个会话"));
        boolean maySeeApprovers = access.permissions(userId).contains(Permission.APPROVE);
        return sessionActions.stream()
                .filter(action -> action.status().awaitingApproval() || action.status().reversible())
                .map(action -> approvalProgress(action, action.status().awaitingApproval()
                        ? ActionApproval.Stage.APPLY : ActionApproval.Stage.ROLLBACK, maySeeApprovers))
                .toList();
    }

    private ApprovalProgress approvalProgress(AgentAction action, ActionApproval.Stage stage) {
        return approvalProgress(action, stage, true);
    }

    private ApprovalProgress approvalProgress(AgentAction action, ActionApproval.Stage stage,
                                              boolean maySeeApprovers) {
        RiskBasedApprovalPolicy.Decision decision = currentPolicy(action, stage);
        List<ActionApproval> approvals = currentApprovals(action, stage, decision);
        return new ApprovalProgress(action.actionId(), stage, bindingVersion(action), bindingHash(action),
                decision.riskLevel(), decision.policyVersion(), decision.requiredApprovals(), approvals.size(),
                Math.max(0, decision.requiredApprovals() - approvals.size()), maySeeApprovers ? approvals : List.of());
    }

    private RiskBasedApprovalPolicy.Decision currentPolicy(AgentAction action) {
        return currentPolicy(action, ActionApproval.Stage.APPLY);
    }

    private RiskBasedApprovalPolicy.Decision currentPolicy(AgentAction action, ActionApproval.Stage stage) {
        try {
            return approvalPolicy.decisionFor(action, stage);
        } catch (RiskBasedApprovalPolicy.PolicyChangedException ex) {
            throw new ActionRequestException(ActionRequestException.Code.CONFLICT, ex.getMessage());
        }
    }

    private void requireSamePolicy(AgentAction action, RiskBasedApprovalPolicy.Decision expected) {
        RiskBasedApprovalPolicy.Decision current = currentPolicy(action);
        if (!expected.equals(current)) {
            throw new ActionRequestException(ActionRequestException.Code.CONFLICT,
                    "风险或审批策略在审批期间发生变化，请重新检查计划");
        }
    }

    private void requireCanApprove(AgentAction action, String userId, RiskBasedApprovalPolicy.Decision decision) {
        String principal = normalize(userId);
        if (!decision.requesterMayApprove() && Objects.equals(principal, action.requestedBy())) {
            throw new AgentAccess.Denied("高风险计划要求审批人与提议人分离");
        }
        if (!access.writeAllowed(principal)) {
            throw new AgentAccess.Denied("当前审批身份已撤权，或系统处于停写状态");
        }
    }

    private List<ActionApproval> currentApprovals(AgentAction action, ActionApproval.Stage stage,
                                                   RiskBasedApprovalPolicy.Decision decision) {
        List<ActionApproval> stored = actions.approvals(action.actionId(), stage, bindingVersion(action),
                bindingHash(action), decision.policyVersion());
        return stored.stream().filter(approval -> (decision.requesterMayApprove()
                        || !Objects.equals(approval.approver(), action.requestedBy()))
                && access.permissions(approval.approver()).contains(Permission.APPROVE)
                && access.writeAllowed(approval.approver())).toList();
    }

    private static ActionApproval approval(AgentAction action, ActionApproval.Stage stage,
                                           RiskBasedApprovalPolicy.Decision decision, String userId) {
        return new ActionApproval(action.actionId(), stage, bindingVersion(action), bindingHash(action),
                decision.policyVersion(), normalize(userId), System.currentTimeMillis());
    }

    private ActionExecutionCommand command(AgentAction action, ActionExecutionCommand.Phase phase,
                                           String actor, RiskBasedApprovalPolicy.Decision decision) {
        String targetNamespace = sessions.find(action.sessionId()).map(Session::targetNamespace).orElse("");
        return ActionExecutionCommand.from(action, phase, actor, targetNamespace, decision.policyVersion());
    }

    private static int bindingVersion(AgentAction action) {
        return action.plan() == null ? ActionApproval.LEGACY_PLAN_VERSION : action.plan().planVersion();
    }

    private static String bindingHash(AgentAction action) {
        return action.plan() == null ? ActionApproval.LEGACY_PLAN_HASH : action.plan().planHash();
    }

    public record ApprovalProgress(String actionId, ActionApproval.Stage stage, int planVersion, String planHash,
                                  RemediationPlan.RiskLevel riskLevel, String policyVersion, int requiredApprovals,
                                  int currentApprovals, int remainingApprovals, List<ActionApproval> approvals) {
        public ApprovalProgress { approvals = List.copyOf(approvals); }
    }

    /** 拒绝：不执行，也不会执行。 */
    public AgentAction reject(String actionId, String userId) {
        access.require(userId, Permission.APPROVE);
        AgentAction pending = require(actionId, userId);
        if (!pending.status().awaitingApproval()) {
            throw new ActionRequestException(ActionRequestException.Code.CONFLICT,
                    "这条变更当前是「" + pending.status().label() + "」，不能拒绝");
        }
        long now = System.currentTimeMillis();
        // 拒绝不留错误说明：状态与卡片上的进度行已经说清「没执行」，再加一条像是出了故障的告警反而误导
        return actions.transition(actionId, ActionStatus.PENDING_APPROVAL,
                        current -> new ActionDraft(current).move(ActionStatus.REJECTED, null).toAction(now))
                .orElseThrow(() -> new ActionRequestException(ActionRequestException.Code.CONFLICT,
                        "这条变更已经被处理过了"));
    }

    /** 回滚：把这次改过的那个版本的权重补偿回变更前的值。 */
    public AgentAction rollback(String actionId, String userId) {
        AgentAction current = require(actionId, userId);
        return rollback(actionId, userId, current.plan() == null ? null : current.plan().planVersion(),
                current.plan() == null ? null : current.plan().planHash());
    }

    /** Compensation approval is separately bound to the exact plan and follows the same risk rules. */
    public AgentAction rollback(String actionId, String userId, Integer planVersion, String planHash) {
        access.require(userId, Permission.APPROVE);
        AgentAction applied = require(actionId, userId);
        if (!applied.status().reversible()) {
            throw new ActionRequestException(ActionRequestException.Code.CONFLICT,
                    "这条变更当前是「" + applied.status().label() + "」，不能回滚");
        }
        Integer currentPlanVersion = applied.plan() == null ? null : applied.plan().planVersion();
        String currentPlanHash = applied.plan() == null ? null : applied.plan().planHash();
        if (!Objects.equals(planVersion, currentPlanVersion)
                || !Objects.equals(planHash, currentPlanHash)) {
            throw new ActionRequestException(ActionRequestException.Code.CONFLICT,
                    "回滚审批的计划版本或摘要不一致，请重新查看计划");
        }
        RiskBasedApprovalPolicy.Decision decision = currentPolicy(applied);
        requireCanApprove(applied, userId, decision);
        try {
            actions.recordApproval(approval(applied, ActionApproval.Stage.ROLLBACK, decision, userId));
        } catch (ActionUpdateConflictException ex) {
            throw new ActionRequestException(ActionRequestException.Code.CONFLICT,
                    "计划已变化或已被处理，回滚审批没有登记");
        }
        if (currentApprovals(applied, ActionApproval.Stage.ROLLBACK, decision).size()
                < decision.requiredApprovals()) {
            return applied;
        }
        if (asynchronousExecution) {
            commands.enqueue(command(applied, ActionExecutionCommand.Phase.ROLLBACK, userId, decision));
        }
        long now = System.currentTimeMillis();
        AgentAction claimed = actions.transition(actionId, ActionStatus.SUCCESS,
                        current -> {
                            if (!Objects.equals(current.plan() == null ? null : current.plan().planVersion(), planVersion)
                                    || !Objects.equals(current.plan() == null ? null : current.plan().planHash(), planHash)) {
                                throw new ActionRequestException(ActionRequestException.Code.CONFLICT,
                                        "回滚计划在审批期间发生变化");
                            }
                            requireSamePolicy(current, decision);
                            if (currentApprovals(current, ActionApproval.Stage.ROLLBACK, decision).size()
                                    < decision.requiredApprovals()) {
                                throw new ActionRequestException(ActionRequestException.Code.CONFLICT,
                                        "回滚审批人数或实时授权已变化，尚不能执行");
                            }
                            return new ActionDraft(current).move(asynchronousExecution
                                    ? ActionStatus.ROLLBACK_QUEUED : ActionStatus.ROLLING_BACK, null).toAction(now);
                        })
                .orElseGet(() -> asynchronousExecution ? latest(actionId) : null);
        if (claimed == null) {
            throw new ActionRequestException(ActionRequestException.Code.CONFLICT,
                    "这条变更已经被处理过了，不会重复回滚");
        }
        if (asynchronousExecution) { return claimed; }
        log.info("变更 {} 开始补偿回滚：{}", actionId, claimed.describe());
        return execute(claimed, userId);
    }

    /** 结果确认：对「结果未知」的变更，用原 operationId 再查一次到底生效了没有。 */
    public AgentAction resolve(String actionId, String userId) {
        AgentAction action = require(actionId, userId);
        if (!action.status().resolvable()) {
            throw new ActionRequestException(ActionRequestException.Code.CONFLICT,
                    "这条变更当前是「" + action.status().label() + "」，不需要确认结果");
        }
        if (asynchronousExecution) {
            RiskBasedApprovalPolicy.Decision decision = currentPolicy(action);
            commands.enqueue(command(action, ActionExecutionCommand.Phase.RECONCILE, userId, decision));
            return actions.transition(actionId, ActionStatus.UNCERTAIN,
                            current -> new ActionDraft(current).move(ActionStatus.RECONCILING, null)
                                    .toAction(System.currentTimeMillis()))
                    .orElseGet(() -> latest(actionId));
        }
        // 回查可并行读取；执行器的每次写回均检查行版本，迟到结果不能覆盖执行或回滚的新状态。
        return execute(action, userId);
    }

    /** Runs one persisted command. The command envelope is checked against the immutable action before dispatch. */
    public AgentAction executeCommand(ActionExecutionCommand command) {
        AgentAction action = latest(command.actionId());
        if (command.actionType() != action.type() || command.planVersion() != bindingVersion(action)
                || !command.planHash().equals(bindingHash(action))
                || !Objects.equals(command.scope(), action.resourceRef() == null ? null : action.resourceRef().scope())
                || !Objects.equals(command.controlTargetId(), action.resourceRef() == null
                        ? "" : action.resourceRef().controlTargetId())
                || !Objects.equals(command.targetNamespace(),
                        sessions.find(action.sessionId()).map(Session::targetNamespace).orElse(""))) {
            return stopMismatchedCommand(action, "持久动作命令与动作的管理目标或计划版本不一致");
        }

        AgentAction result = switch (command.phase()) {
            case APPLY -> runApplyCommand(action, command);
            case ROLLBACK -> runRollbackCommand(action, command);
            case RECONCILE -> runReconcileCommand(action, command);
        };
        if (command.phase() == ActionExecutionCommand.Phase.APPLY && result.status() == ActionStatus.SUCCESS) {
            scheduleEffectVerification(result, ActionEffectVerification.Phase.APPLY);
        } else if (command.phase() == ActionExecutionCommand.Phase.ROLLBACK && result.status() == ActionStatus.ROLLED_BACK) {
            scheduleEffectVerification(result, ActionEffectVerification.Phase.COMPENSATION);
        }
        return result;
    }

    private AgentAction runApplyCommand(AgentAction action, ActionExecutionCommand command) {
        if (action.status() == ActionStatus.PENDING_APPROVAL) {
            if (!executionAuthorized(action, command, ActionApproval.Stage.APPLY)) {
                if (action.plan() != null && action.plan().riskAssessment().approvalMode()
                        == RemediationPlan.ApprovalMode.PREAUTHORIZED) {
                    return actions.transition(action.actionId(), ActionStatus.PENDING_APPROVAL,
                                    current -> new ActionDraft(current).move(ActionStatus.PRECONDITION_FAILED,
                                            "预授权已撤销、过期或写入被停用，未提交 Gateway 写请求")
                                            .toAction(System.currentTimeMillis()))
                            .orElseGet(() -> latest(command.actionId()));
                }
                return action;
            }
            long now = System.currentTimeMillis();
            String queuedActionId = action.actionId();
            action = actions.transition(queuedActionId, ActionStatus.PENDING_APPROVAL,
                    current -> new ActionDraft(current).approvedBy(command.actor(), now)
                            .move(ActionStatus.QUEUED, null).toAction(now)).orElseGet(() -> latest(command.actionId()));
        }
        if (action.status() == ActionStatus.QUEUED) {
            if (!executionAuthorized(action, command, ActionApproval.Stage.APPLY)) {
                if (action.plan() != null && action.plan().riskAssessment().approvalMode()
                        == RemediationPlan.ApprovalMode.PREAUTHORIZED) {
                    return actions.transition(action.actionId(), ActionStatus.QUEUED,
                                    current -> new ActionDraft(current).move(ActionStatus.PRECONDITION_FAILED,
                                            "预授权已撤销、过期或写入被停用，未提交 Gateway 写请求")
                                            .toAction(System.currentTimeMillis()))
                            .orElseGet(() -> latest(command.actionId()));
                }
                return actions.transition(action.actionId(), ActionStatus.QUEUED,
                                current -> new ActionDraft(current).move(ActionStatus.PENDING_APPROVAL,
                                        "排队期间审批权限或策略发生变化，请重新检查并批准")
                                        .toAction(System.currentTimeMillis()))
                        .orElseGet(() -> latest(command.actionId()));
            }
            action = actions.transition(action.actionId(), ActionStatus.QUEUED,
                    current -> new ActionDraft(current).move(ActionStatus.EXECUTING, null)
                            .toAction(System.currentTimeMillis())).orElseGet(() -> latest(command.actionId()));
        }
        if (action.status() != ActionStatus.EXECUTING) { return action; }
        ActionExecutor executor = executors.require(action.type());
        return action.applyOperationId() == null ? executor.execute(action, command.actor()) : executor.resolve(action);
    }

    private AgentAction runRollbackCommand(AgentAction action, ActionExecutionCommand command) {
        if (action.status() == ActionStatus.SUCCESS) {
            if (!executionAuthorized(action, command, ActionApproval.Stage.ROLLBACK)) {
                return action;
            }
            action = actions.transition(action.actionId(), ActionStatus.SUCCESS,
                    current -> new ActionDraft(current).move(ActionStatus.ROLLBACK_QUEUED, null)
                            .toAction(System.currentTimeMillis())).orElseGet(() -> latest(command.actionId()));
        }
        if (action.status() == ActionStatus.ROLLBACK_QUEUED) {
            if (!executionAuthorized(action, command, ActionApproval.Stage.ROLLBACK)) {
                return actions.transition(action.actionId(), ActionStatus.ROLLBACK_QUEUED,
                                current -> new ActionDraft(current).move(ActionStatus.SUCCESS,
                                        "排队期间回滚审批权限或策略发生变化，请重新检查并批准回滚")
                                        .toAction(System.currentTimeMillis()))
                        .orElseGet(() -> latest(command.actionId()));
            }
            action = actions.transition(action.actionId(), ActionStatus.ROLLBACK_QUEUED,
                    current -> new ActionDraft(current).move(ActionStatus.ROLLING_BACK, null)
                            .toAction(System.currentTimeMillis())).orElseGet(() -> latest(command.actionId()));
        }
        if (action.status() != ActionStatus.ROLLING_BACK) { return action; }
        ActionExecutor executor = executors.require(action.type());
        return action.rollbackOperationId() == null ? executor.rollback(action, command.actor())
                : executor.resolve(action);
    }

    private boolean executionAuthorized(AgentAction action, ActionExecutionCommand command,
                                        ActionApproval.Stage stage) {
        if (command.actor() == null) { return false; }
        boolean preauthorized = stage == ActionApproval.Stage.APPLY
                && approvalPolicy.matchesPreauthorization(action, command.targetNamespace(), System.currentTimeMillis());
        if (preauthorized ? !access.automatedWriteAllowed() : !access.writeAllowed(command.actor())) { return false; }
        RiskBasedApprovalPolicy.Decision decision;
        try { decision = approvalPolicy.decisionFor(action, stage); }
        catch (RiskBasedApprovalPolicy.PolicyChangedException ex) { return false; }
        return decision.policyVersion().equals(command.policyVersion())
                && (preauthorized || currentApprovals(action, stage, decision).size() >= decision.requiredApprovals());
    }

    private AgentAction runReconcileCommand(AgentAction action, ActionExecutionCommand command) {
        String operationId = action.rollbackOperationId() == null
                ? action.applyOperationId() : action.rollbackOperationId();
        if (!Objects.equals(command.operationId(), operationId)) { return action; }
        if (action.status() == ActionStatus.UNCERTAIN) {
            action = actions.transition(action.actionId(), ActionStatus.UNCERTAIN,
                    current -> new ActionDraft(current).move(ActionStatus.RECONCILING, null)
                            .toAction(System.currentTimeMillis())).orElseGet(() -> latest(command.actionId()));
        }
        if (action.status() != ActionStatus.RECONCILING) { return action; }
        ActionStatus phase = action.rollbackOperationId() == null
                ? ActionStatus.VERIFYING : ActionStatus.ROLLING_BACK;
        action = actions.transition(action.actionId(), ActionStatus.RECONCILING,
                current -> new ActionDraft(current).move(phase, null).toAction(System.currentTimeMillis()))
                .orElseGet(() -> latest(command.actionId()));
        return executors.require(action.type()).resolve(action);
    }

    public void scheduleEffectVerification(AgentAction action, ActionEffectVerification.Phase phase) {
        if (effectVerification != null) { effectVerification.schedule(action, phase, System.currentTimeMillis()); }
    }

    public void scheduleEffectVerification(String actionId, ActionEffectVerification.Phase phase) {
        if (effectVerification != null) {
            AgentAction action = actions.find(actionId).orElseThrow(() -> new IllegalStateException("action disappeared before verification scheduling"));
            effectVerification.schedule(action, phase, System.currentTimeMillis());
        }
    }

    public java.util.Optional<ActionEffectVerification> effectVerification(String actionId, String userId,
                                                                           ActionEffectVerification.Phase phase) {
        AgentAction action = require(actionId, userId);
        ActionEffectVerificationRepository repository = effectVerification == null ? null : effectVerification.repository();
        return repository == null ? java.util.Optional.empty() : repository.find(action.actionId(), phase);
    }

    public List<ActionEffectVerification> effectVerifications(String sessionId, String userId) {
        if (bySession(sessionId, userId).isEmpty()) { return List.of(); }
        ActionEffectVerificationRepository repository = effectVerification == null ? null : effectVerification.repository();
        if (repository == null) { return List.of(); }
        return actions.bySession(sessionId).stream().flatMap(action -> java.util.Arrays.stream(ActionEffectVerification.Phase.values())
                .map(phase -> repository.find(action.actionId(), phase).orElse(null)))
                .filter(Objects::nonNull).toList();
    }

    private AgentAction stopMismatchedCommand(AgentAction action, String reason) {
        ActionStatus status;
        ActionStatus expected = action.status();
        if (expected == ActionStatus.EXECUTING || expected == ActionStatus.QUEUED) {
            status = action.applyOperationId() == null ? ActionStatus.PRECONDITION_FAILED : ActionStatus.UNCERTAIN;
        } else if (expected == ActionStatus.ROLLING_BACK || expected == ActionStatus.ROLLBACK_QUEUED) {
            status = action.rollbackOperationId() == null ? ActionStatus.SUCCESS : ActionStatus.UNCERTAIN;
        } else if (expected == ActionStatus.RECONCILING) {
            status = ActionStatus.UNCERTAIN;
        } else {
            return action;
        }
        return actions.transition(action.actionId(), expected,
                current -> new ActionDraft(current).move(status, reason).toAction(System.currentTimeMillis()))
                        .orElseGet(() -> latest(action.actionId()));
    }

    /** 按变更类型选择执行器，不支持的类型直接拒绝。 */
    private AgentAction execute(AgentAction action, String actor) {
        ActionExecutor executor;
        try { executor = executors.require(action.type()); }
        catch (IllegalArgumentException ex) {
            throw new ActionRequestException(ActionRequestException.Code.CONFLICT,
                    "没有可以执行「" + action.type().label() + "」的执行器");
        }
        try {
            AgentAction result = switch (action.status()) {
                case EXECUTING -> executor.execute(action, actor);
                case ROLLING_BACK -> executor.rollback(action, actor);
                default -> executor.resolve(action);
            };
            if (effectVerification != null && result.status() != action.status()) {
                if (result.status() == ActionStatus.SUCCESS) {
                    scheduleEffectVerification(result, ActionEffectVerification.Phase.APPLY);
                } else if (result.status() == ActionStatus.ROLLED_BACK) {
                    scheduleEffectVerification(result, ActionEffectVerification.Phase.COMPENSATION);
                }
            }
            return result;
        } catch (ActionUpdateConflictException ex) {
            return latest(action.actionId());
        } catch (RuntimeException ex) {
            // 记录未预期的执行异常，避免状态停留在执行中。
            log.error("执行变更 {} 时出现未预期的异常", action.actionId(), ex);
            long now = System.currentTimeMillis();
            AgentAction current = latest(action.actionId());
            // 旧执行/回查的异常不得影响已经开始的补偿阶段。
            boolean rollbackAdvanced = action.status() != ActionStatus.ROLLING_BACK
                    && (current.status() == ActionStatus.ROLLING_BACK
                    || !Objects.equals(action.rollbackOperationId(), current.rollbackOperationId()));
            if (current.status().settled() || rollbackAdvanced) {
                return current;
            }
            AgentAction failed = new ActionDraft(current)
                    .move(ActionStatus.UNCERTAIN, "执行过程中出现未预期的异常，结果未知：" + failureText(ex))
                    .toAction(now);
            try {
                return actions.save(failed);
            } catch (ActionUpdateConflictException conflict) {
                return latest(action.actionId());
            }
        }
    }

    private AgentAction latest(String actionId) {
        return actions.find(actionId).orElseThrow(() -> new ActionRequestException(
                ActionRequestException.Code.NOT_FOUND, "变更记录已删除，已停止写回"));
    }

    // ---------------------------------------------------------------- 归属

    private AgentAction require(String actionId, String userId) {
        return find(actionId, userId).orElseThrow(() -> new ActionRequestException(
                ActionRequestException.Code.NOT_FOUND, "找不到这条变更，或它不属于当前用户"));
    }

    private Optional<Session> session(String sessionId, String userId) {
        return sessions.find(sessionId).filter(this::inScope).filter(found -> Objects.equals(found.userId(), normalize(userId))
                || teamApprover(userId));
    }

    /** 变更的归属就是提议会话的归属；未启用登录时双方都为空，视为同一主体。 */
    private boolean ownedBy(AgentAction action, String userId) {
        return (Objects.equals(action.requestedBy(), normalize(userId)) || teamApprover(userId))
                && sessions.find(action.sessionId()).filter(this::inScope).isPresent();
    }

    private boolean teamApprover(String userId) {
        return access.managed() && access.permissions(userId).contains(Permission.APPROVE);
    }

    private boolean inScope(Session session) {
        return control.resourceScope() == null || (control.resourceScope().equals(session.scope())
                && control.resourceNamespace().equals(session.targetNamespace()));
    }

    private static String normalize(String userId) {
        return userId == null || userId.isBlank() ? null : userId.trim();
    }


    private static String failureText(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
