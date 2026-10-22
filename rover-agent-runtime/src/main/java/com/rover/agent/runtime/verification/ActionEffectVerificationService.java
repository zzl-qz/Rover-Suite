package com.rover.agent.runtime.verification;

import com.rover.agent.core.model.ActionEffectVerification;
import com.rover.agent.core.model.ActionStatus;
import com.rover.agent.core.model.AgentAction;
import com.rover.agent.core.model.EvidenceQuality;
import com.rover.agent.core.model.RemediationPlan;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.core.port.MetricReadPort;
import com.rover.agent.core.repository.ActionEffectVerificationRepository;
import com.rover.agent.core.repository.AgentActionRepository;
import com.rover.agent.core.repository.AgentSessionRepository;
import com.rover.agent.core.timeline.TimelineQuery;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TargetType;
import com.rover.agent.core.port.TimelineReadPort;
import com.rover.agent.core.model.InvestigationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Persistent, deterministic service-effect observation, independent of control-plane action status. */
public final class ActionEffectVerificationService {
    private static final Logger log = LoggerFactory.getLogger(ActionEffectVerificationService.class);
    public static final String EFFECT_POLICY_VERSION = "route-final-response-v1";
    public static final String METRIC_QUERY_VERSION = "gateway-final-window-v1";
    private static final int MAX_WINDOWS = 20;
    private final AgentActionRepository actions;
    private final AgentSessionRepository sessions;
    private final ActionEffectVerificationRepository verifications;
    private final MetricReadPort metrics;
    private final TimelineReadPort timeline;
    private final BusinessEffectEvaluator evaluator;
    private final AtomicBoolean pollerStarted = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private java.util.concurrent.ScheduledExecutorService poller;
    private final java.util.Set<String> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ActionEffectVerificationService(AgentActionRepository actions, AgentSessionRepository sessions,
            ActionEffectVerificationRepository verifications, MetricReadPort metrics, TimelineReadPort timeline) {
        this(actions, sessions, verifications, metrics, timeline, new BusinessEffectEvaluator());
    }

    ActionEffectVerificationService(AgentActionRepository actions, AgentSessionRepository sessions,
            ActionEffectVerificationRepository verifications, MetricReadPort metrics, TimelineReadPort timeline,
            BusinessEffectEvaluator evaluator) {
        this.actions = actions; this.sessions = sessions; this.verifications = verifications;
        this.metrics = metrics; this.timeline = timeline; this.evaluator = evaluator;
    }

    public ActionEffectVerificationRepository repository() { return verifications; }

    public void start(int batchSize, long pollMillis) {
        if (batchSize < 1 || batchSize > 1000 || pollMillis < 100 || pollMillis > 60_000) {
            throw new IllegalArgumentException("business verification worker settings are outside supported bounds");
        }
        if (pollerStarted.compareAndSet(false, true)) {
            poller = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "rover-agent-effect-verifier");
                thread.setDaemon(true);
                return thread;
            });
            poller.scheduleWithFixedDelay(() -> poll(batchSize), 0, pollMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    public void resumeDue() { poll(100); }

    public void close() {
        stopped.set(true);
        if (poller != null) { poller.shutdownNow(); }
    }

    private void poll(int batchSize) {
        if (stopped.get()) { return; }
        try {
            for (ActionEffectVerification item : verifications.due(System.currentTimeMillis(), batchSize)) {
                String key = item.actionId() + "|" + item.phase();
                if (!inFlight.add(key)) { continue; }
                try { runDue(item, System.currentTimeMillis()); }
                catch (RuntimeException failure) { log.warn("Business effect window retained for retry: action={} phase={}", item.actionId(), item.phase(), failure); }
                finally { inFlight.remove(key); }
            }
        } catch (RuntimeException failure) { log.error("Business effect scheduler read failed; retrying on next poll", failure); }
    }

    /** Called after a control-plane apply or compensation is durably confirmed. */
    public void schedule(AgentAction action, ActionEffectVerification.Phase phase, long nowMillis) {
        if (!ready(action, phase)) { return; }
        if (action.plan() == null || action.plan().verificationSpec().mode() != RemediationPlan.VerificationMode.BUSINESS_EFFECT) { return; }
        RemediationPlan.BusinessEffectSpec spec = action.plan().verificationSpec().businessEffect();
        ActionEffectVerification existing = verifications.find(action.actionId(), phase).orElse(null);
        if (existing != null) { return; }
        long deadline = Math.addExact(nowMillis, spec.maximumObservationMillis());
        long appliedAt = action.updatedAtMillis();
        long baselineFrom = Math.max(1, action.updatedAtMillis() - spec.baselineMillis());
        try { verifications.save(new ActionEffectVerification(action.actionId(), phase, action.plan().planVersion(),
                action.plan().planHash(), ActionEffectVerification.State.PREPARED,
                ActionEffectVerification.Outcome.PENDING, baselineFrom, 0, 0, deadline, 0, 0,
                Map.of(), Map.of(), List.of(), List.of(), 0)); }
        catch (IllegalStateException racedInsert) {
            if (verifications.find(action.actionId(), phase).isEmpty()) { throw racedInsert; }
        }
    }

    /** Run one due verification; errors become explicit missing-data outcomes, never healthy zeros. */
    public ActionEffectVerification runDue(ActionEffectVerification verification, long nowMillis) {
        AgentAction action = actions.find(verification.actionId()).orElse(null);
        if (action == null || action.plan() == null || action.plan().planVersion() != verification.planVersion()
                || !action.plan().planHash().equals(verification.planHash())) {
            return complete(verification, ActionEffectVerification.Outcome.NOT_APPLIED,
                    "计划不存在或已变化，验证结果不能关联到当前动作");
        }
        RemediationPlan.BusinessEffectSpec spec = action.plan().verificationSpec().businessEffect();
        if (spec == null || !EFFECT_POLICY_VERSION.equals(spec.ruleVersion())
                || !METRIC_QUERY_VERSION.equals(spec.queryTemplateVersion())) {
            return complete(verification, ActionEffectVerification.Outcome.MISSING_DATA,
                    "当前程序不支持计划绑定的业务验证规则或查询版本");
        }
        if (!ready(action, verification.phase())) {
            return complete(verification, ActionEffectVerification.Outcome.NOT_APPLIED,
                    "控制面动作尚未以回读确认方式完成");
        }
        Session session = sessions.find(action.sessionId()).orElse(null);
        if (session == null || session.scope() == null || action.resourceRef() == null
                || !session.scope().equals(action.resourceRef().scope())
                || !Objects.equals(session.targetNamespace(), action.resourceRef().scope().environmentId())) {
            return finishWithMissing(verification, nowMillis, "会话或资源范围已失效，无法安全读取效果指标");
        }
        long appliedAt = appliedAt(action, verification, spec);
        long windowFrom = verification.state() == ActionEffectVerification.State.PREPARED
                ? Math.addExact(appliedAt, spec.propagationGraceMillis())
                : Math.subtractExact(verification.nextWindowAtMillis(), spec.windowMillis());
        long windowTo = verification.state() == ActionEffectVerification.State.PREPARED
                ? Math.addExact(windowFrom, spec.windowMillis()) : verification.nextWindowAtMillis();
        if (nowMillis >= verification.deadlineAtMillis() && windowTo > verification.deadlineAtMillis()) {
            ActionEffectVerification.Outcome timeoutOutcome = verification.windows().stream()
                    .anyMatch(item -> item.outcome() == ActionEffectVerification.Outcome.IMPROVED)
                    ? ActionEffectVerification.Outcome.IMPROVED : ActionEffectVerification.Outcome.NOT_RECOVERED;
            return complete(verification, timeoutOutcome, "业务效果观察期限已到，未收集到足够连续的恢复窗口");
        }
        Map<String, Double> baseline = verification.baselineValues();
        if (verification.state() == ActionEffectVerification.State.PREPARED) {
            try {
                var before = metrics.routeWindow(action.routeId(), new TimeRange(verification.baselineAtMillis(), appliedAt));
                baseline = evaluator.baseline(before, spec, action.routeId(), verification.baselineAtMillis(), appliedAt);
            } catch (BusinessEffectEvaluator.InsufficientSampleException insufficient) {
                return complete(verification, ActionEffectVerification.Outcome.INSUFFICIENT_SAMPLE, safe(insufficient));
            } catch (RuntimeException failure) {
                return finishWithMissing(verification, nowMillis, "读取变更前指标失败：" + safe(failure));
            }
            if (evaluator.baselineAlreadyHealthy(baseline, spec)) {
                return complete(verification, ActionEffectVerification.Outcome.BASELINE_ALREADY_HEALTHY,
                        "变更前指标已经满足计划阈值，不能把当前健康状态归功于本次动作");
            }
        }
        if (verification.state() == ActionEffectVerification.State.OBSERVING && verification.baselineValues().isEmpty()) {
            return finishWithMissing(verification, nowMillis, "观察记录缺少持久化基线，停止自动归因");
        }
        if (nowMillis < windowTo) {
            ActionEffectVerification waiting = new ActionEffectVerification(verification.actionId(), verification.phase(),
                    verification.planVersion(), verification.planHash(), ActionEffectVerification.State.OBSERVING,
                    ActionEffectVerification.Outcome.PENDING, verification.baselineAtMillis(), appliedAt, windowTo,
                    verification.deadlineAtMillis(), verification.windowsSeen(), verification.consecutiveRecoveredWindows(),
                    baseline, verification.latestValues(), verification.windows(), verification.limitations(), verification.version());
            return verifications.save(waiting);
        }
        TimelineCheck timelineCheck = contamination(action, session, windowFrom, windowTo);
        if (!timelineCheck.available()) {
            ActionEffectVerification.Window window = new ActionEffectVerification.Window(windowFrom, windowTo,
                    ActionEffectVerification.Outcome.MISSING_DATA, 0, 0, Map.of(),
                    timelineCheck.limitations());
            return appendWindow(verification, baseline, window, nowMillis, spec, true);
        }
        if (!timelineCheck.changes().isEmpty()) {
            ActionEffectVerification.Window window = new ActionEffectVerification.Window(windowFrom, windowTo,
                    ActionEffectVerification.Outcome.CONTAMINATED, 0, 0, Map.of(), timelineCheck.changes());
            return appendWindow(verification, baseline, window, nowMillis, spec, true);
        }
        ActionEffectVerification.Window observed;
        try {
            var snapshot = metrics.routeWindow(action.routeId(), new TimeRange(windowFrom, windowTo));
            observed = evaluator.evaluate(snapshot, spec, action.routeId(), windowFrom, windowTo, baseline);
            if (!timelineCheck.limitations().isEmpty()) {
                observed = new ActionEffectVerification.Window(observed.fromMillis(), observed.toMillis(), observed.outcome(),
                        observed.requests(), observed.coverage(), observed.values(),
                        merge(observed.limitations(), timelineCheck.limitations()));
            }
        } catch (RuntimeException failure) {
            observed = new ActionEffectVerification.Window(windowFrom, windowTo,
                    ActionEffectVerification.Outcome.MISSING_DATA, 0, 0, Map.of(), List.of("读取业务效果窗口失败：" + safe(failure)));
        }
        return appendWindow(verification, baseline, observed, nowMillis, spec, false);
    }

    private ActionEffectVerification appendWindow(ActionEffectVerification old, Map<String, Double> baseline,
            ActionEffectVerification.Window window, long now, RemediationPlan.BusinessEffectSpec spec, boolean terminal) {
        List<ActionEffectVerification.Window> windows = new ArrayList<>(old.windows());
        if (windows.size() == MAX_WINDOWS) { windows.remove(0); }
        windows.add(window);
        if (!old.windows().isEmpty()) {
            ActionEffectVerification.Window previous = old.windows().get(old.windows().size() - 1);
            if (previous.fromMillis() == window.fromMillis() && previous.toMillis() == window.toMillis()) { return old; }
            if (window.fromMillis() < previous.toMillis()) {
                return complete(old, ActionEffectVerification.Outcome.MISSING_DATA, "观察窗口发生重叠或倒退，停止自动归因");
            }
        }
        int seen = old.windowsSeen() + 1;
        int consecutive = window.outcome() == ActionEffectVerification.Outcome.RECOVERED
                ? old.consecutiveRecoveredWindows() + 1 : 0;
        ActionEffectVerification.Outcome outcome = window.outcome();
        boolean finalState = terminal || outcome == ActionEffectVerification.Outcome.WORSENED
                || outcome == ActionEffectVerification.Outcome.MISSING_DATA
                || outcome == ActionEffectVerification.Outcome.INSUFFICIENT_SAMPLE
                || outcome == ActionEffectVerification.Outcome.CONTAMINATED
                || consecutive >= spec.consecutiveWindows() || seen >= MAX_WINDOWS || now >= old.deadlineAtMillis();
        if (consecutive >= spec.consecutiveWindows()) { outcome = ActionEffectVerification.Outcome.RECOVERED; }
        else if (!finalState) { outcome = ActionEffectVerification.Outcome.PENDING; }
        else if (seen >= MAX_WINDOWS || now >= old.deadlineAtMillis()) {
            outcome = old.windows().stream().anyMatch(item -> item.outcome() == ActionEffectVerification.Outcome.IMPROVED)
                    || window.outcome() == ActionEffectVerification.Outcome.IMPROVED
                    ? ActionEffectVerification.Outcome.IMPROVED : ActionEffectVerification.Outcome.NOT_RECOVERED;
        }
        ActionEffectVerification next = new ActionEffectVerification(old.actionId(), old.phase(), old.planVersion(), old.planHash(),
                finalState ? ActionEffectVerification.State.COMPLETE : ActionEffectVerification.State.OBSERVING, outcome,
                old.baselineAtMillis(), old.appliedAtMillis() == 0 ? now : old.appliedAtMillis(),
                finalState ? 0 : Math.addExact(window.toMillis(), spec.windowMillis()), old.deadlineAtMillis(),
                seen, consecutive, baseline, window.values(), windows, merge(old.limitations(), window.limitations()), old.version());
        return verifications.save(next);
    }

    private ActionEffectVerification finishWithMissing(ActionEffectVerification old, long now, String reason) {
        return complete(old, ActionEffectVerification.Outcome.MISSING_DATA, reason);
    }

    private ActionEffectVerification complete(ActionEffectVerification old, ActionEffectVerification.Outcome outcome, String reason) {
        return verifications.save(new ActionEffectVerification(old.actionId(), old.phase(), old.planVersion(), old.planHash(),
                ActionEffectVerification.State.COMPLETE, outcome, old.baselineAtMillis(), old.appliedAtMillis(), 0,
                old.deadlineAtMillis(), old.windowsSeen(), old.consecutiveRecoveredWindows(), old.baselineValues(),
                old.latestValues(), old.windows(), merge(old.limitations(), List.of(reason)), old.version()));
    }

    private boolean ready(AgentAction action, ActionEffectVerification.Phase phase) {
        return phase == ActionEffectVerification.Phase.APPLY
                ? action.status() == ActionStatus.SUCCESS && action.appliedRevision() != null
                : action.status() == ActionStatus.ROLLED_BACK && action.rollbackOperationId() != null;
    }

    private static long appliedAt(AgentAction action, ActionEffectVerification verification,
                                  RemediationPlan.BusinessEffectSpec spec) {
        return verification.phase() == ActionEffectVerification.Phase.APPLY
                ? Math.addExact(verification.baselineAtMillis(), spec.baselineMillis()) : action.updatedAtMillis();
    }

    private TimelineCheck contamination(AgentAction action, Session session, long from, long to) {
        try {
            if (timeline == null) { return new TimelineCheck(false, List.of(), List.of("未配置同期变更时间线")); }
            var context = new InvestigationContext(action.taskId(), action.incidentId(), session.scope(),
                    action.resourceRef().controlTargetId(), session.targetNamespace(), ResourceTarget.route(action.businessPrefix()),
                    new TimeRange(from, to), Map.of(), "LIVE_EXECUTION_VERIFICATION_V1");
            var result = timeline.query(new TimelineQuery(context, new ResourceTarget(TargetType.ROUTE, action.routeId()),
                    new TimeRange(from, to), 100));
            List<String> incomplete = result.limitations().stream().filter(item -> item.contains("尚无采集记录")
                    || item.contains("采集失败") || item.contains("已过期") || item.contains("采集空档")
                    || item.contains("首次成功采集晚于")).toList();
            if (result.truncated() || !incomplete.isEmpty()) {
                return new TimelineCheck(false, List.of(), merge(incomplete,
                        result.truncated() ? List.of("同期变更时间线已截断") : List.of()));
            }
            List<String> changes = result.events().stream().filter(event -> event.sourceId().startsWith("release:")
                    || Set.of("ROUTE_CHANGED", "ROUTE_APPEARED", "ROUTE_REMOVED", "ROUTE_CHANGES", "ROUTE_APPEARS", "ROUTE_REMOVES").contains(event.kind()))
                    .map(event -> event.kind() + " from " + event.sourceId() + " at " + event.occurrenceRange()).toList();
            List<String> limits = result.limitations().stream()
                    .filter(item -> item.contains("未接入或未推送的发布不可见")
                            || item.contains("空列表不证明没有发布或变更"))
                    .toList();
            return new TimelineCheck(true, changes, limits);
        } catch (RuntimeException failure) {
            return new TimelineCheck(false, List.of(), List.of("同期变更时间线读取失败：" + safe(failure)));
        }
    }

    private record TimelineCheck(boolean available, List<String> changes, List<String> limitations) { }
    private static List<String> merge(List<String> left, List<String> right) {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>(left); all.addAll(right); return List.copyOf(all);
    }
    private static String safe(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) { return failure.getClass().getSimpleName(); }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
