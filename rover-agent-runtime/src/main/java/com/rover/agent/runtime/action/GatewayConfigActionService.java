package com.rover.agent.runtime.action;

import com.rover.agent.core.model.ConfigActionApproval;
import com.rover.agent.core.model.ConfigActionIntent;
import com.rover.agent.core.model.ConfigActionStatus;
import com.rover.agent.core.model.GatewayConfigAction;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.port.AgentAccess;
import com.rover.agent.core.port.AgentAccess.Permission;
import com.rover.agent.core.port.ConfigReadPort;
import com.rover.agent.core.port.RouteControlPort;
import com.rover.agent.core.repository.AgentSessionRepository;
import com.rover.agent.core.repository.AgentTaskRepository;
import com.rover.agent.core.repository.IncidentRepository;
import com.rover.agent.core.repository.GatewayConfigActionRepository;
import com.rover.agent.core.snapshot.ConfigEntrySnapshot;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Human-approved proposal and reconciliation service for the first allowlisted Gateway config action. */
public final class GatewayConfigActionService {
    public static final String POLICY_VERSION = "gateway-config-two-person-v1";
    private static final long PLAN_TTL_MILLIS = 10 * 60_000L;
    private final GatewayConfigActionRepository actions;
    private final AgentSessionRepository sessions;
    private final IncidentRepository incidents;
    private final AgentTaskRepository tasks;
    private final ConfigReadPort configs;
    private final RouteControlPort control;
    private final AgentAccess access;
    private final boolean asynchronous;
    private final java.util.function.BooleanSupplier durableReady;

    public GatewayConfigActionService(GatewayConfigActionRepository actions, AgentSessionRepository sessions,
            ConfigReadPort configs, RouteControlPort control, AgentAccess access, boolean asynchronous,
            java.util.function.BooleanSupplier durableReady) {
        this(actions, sessions, null, null, configs, control, access, asynchronous, durableReady);
    }

    public GatewayConfigActionService(GatewayConfigActionRepository actions, AgentSessionRepository sessions,
            IncidentRepository incidents, AgentTaskRepository tasks, ConfigReadPort configs, RouteControlPort control,
            AgentAccess access, boolean asynchronous, java.util.function.BooleanSupplier durableReady) {
        this.actions=actions; this.sessions=sessions; this.configs=configs; this.control=control;
        this.incidents=incidents; this.tasks=tasks;
        this.access=access; this.asynchronous=asynchronous; this.durableReady=durableReady;
    }

    public GatewayConfigAction propose(String sessionId, String incidentId, String taskId,
                                       String desiredValue, String actor) {
        access.require(actor, Permission.PROPOSE);
        if (!"0".equals(desiredValue) && !"1".equals(desiredValue)) {
            throw invalid("链路采样率目标值只能是 0 或 1");
        }
        Session session = requireSession(sessionId, actor, false);
        validateAuditLinks(session, incidentId, taskId);
        if (!durableReady.getAsBoolean() && access.managed())
            throw conflict("持久存储未就绪，不能登记可执行配置计划");
        if (control.resourceScope()==null || control.controlTargetId().isBlank()
                || !control.resourceScope().equals(session.scope())
                || !control.resourceNamespace().equals(session.targetNamespace()))
            throw conflict("会话与当前 Gateway 管理范围不一致");
        ConfigEntrySnapshot entry = configs.configs().stream()
                .filter(c -> ConfigActionIntent.ALLOWED_KEY.equals(c.key()) && "gateway".equals(c.component()))
                .findFirst().orElseThrow(() -> invalid("当前管理目标未公开受支持的 Gateway 配置"));
        if (entry.unavailable() || entry.value()==null || !validSampleRate(entry.value()))
            throw conflict("当前配置不可用或值超出已认证范围，不能生成计划");
        if (Double.parseDouble(entry.value()) == Double.parseDouble(desiredValue)) {
            throw invalid("目标值与当前值相同，无需生成配置计划");
        }
        ConfigActionIntent intent = new ConfigActionIntent(ConfigActionIntent.ALLOWED_KEY, entry.value(), desiredValue,
                session.scope(), control.controlTargetId(), session.targetNamespace());
        long now=System.currentTimeMillis();
        GatewayConfigAction action = new GatewayConfigAction(UUID.randomUUID().toString(), sessionId, incidentId,
                taskId, actor, intent, intent.intentHash(), POLICY_VERSION, List.of(), UUID.randomUUID().toString(),
                ConfigActionStatus.PENDING_APPROVAL, null, now, now, now+PLAN_TTL_MILLIS, 0);
        return actions.save(action);
    }

    private void validateAuditLinks(Session session, String incidentId, String taskId) {
        // The legacy constructor is retained for isolated domain tests that do not assemble the record stores.
        if (incidents == null && tasks == null) return;
        if (incidentId != null && !incidentId.isBlank()) {
            if (!session.incidentIds().contains(incidentId) || incidents == null
                    || incidents.find(incidentId).filter(incident -> session.sessionId().equals(incident.sessionId())).isEmpty()) {
                throw invalid("事件不属于当前会话，不能关联配置动作");
            }
        }
        if (taskId != null && !taskId.isBlank()) {
            var task = tasks == null ? Optional.<com.rover.agent.core.model.TaskView>empty() : tasks.find(taskId);
            if (task.filter(view -> session.sessionId().equals(view.sessionId())
                    && (incidentId == null || incidentId.isBlank()
                    ? view.incidentId() == null || view.incidentId().isBlank()
                    : incidentId.equals(view.incidentId()))).isEmpty()) {
                throw invalid("任务不属于当前会话或与事件不匹配，不能关联配置动作");
            }
        }
    }

    /** Agent tool entry: proposer identity comes only from the persisted server-owned session. */
    public GatewayConfigAction proposeFromSession(String sessionId, String incidentId, String taskId, String desiredValue) {
        Session session = sessions.find(sessionId).orElseThrow(() ->
                new ActionRequestException(ActionRequestException.Code.NOT_FOUND, "找不到会话"));
        if (access.managed() && (session.userId() == null || session.userId().isBlank())) {
            throw new AgentAccess.Denied("未认证的会话不能创建配置动作提案");
        }
        return propose(sessionId, incidentId, taskId, desiredValue, session.userId());
    }

    /** Create a separately approved reverse intent; the original successful action and receipt remain immutable. */
    public GatewayConfigAction proposeCompensation(String actionId, String actor) {
        access.require(actor, Permission.PROPOSE);
        if (!durableReady.getAsBoolean() && access.managed())
            throw conflict("持久存储未就绪，不能登记补偿计划");
        GatewayConfigAction original = actions.find(actionId).orElseThrow(() ->
                new ActionRequestException(ActionRequestException.Code.NOT_FOUND, "找不到配置动作"));
        requireSession(original.sessionId(), actor, true);
        if (original.status() != ConfigActionStatus.APPLIED || original.compensatesActionId() != null)
            throw conflict("只有已生效的原配置动作可以发起一次补偿");
        if (actions.findCompensation(actionId).isPresent())
            throw conflict("该配置动作已有补偿计划");
        if (!targetsCurrent(original)) throw conflict("Gateway 管理目标已变化，不能补偿旧目标");
        ConfigEntrySnapshot current = configs.configs().stream()
                .filter(c -> ConfigActionIntent.ALLOWED_KEY.equals(c.key()) && "gateway".equals(c.component()))
                .findFirst().orElseThrow(() -> conflict("当前管理目标没有公开受支持的 Gateway 配置"));
        if (current.unavailable() || current.value() == null || !sameValue(current.value(), original.intent().desiredValue()))
            throw conflict("当前配置已不同于原动作目标值；为避免覆盖新变更，不创建补偿");
        String reverseValue = sameValue(original.intent().beforeValue(), "0") ? "0" : "1";
        ConfigActionIntent reverse = new ConfigActionIntent(ConfigActionIntent.ALLOWED_KEY, current.value(),
                reverseValue, original.intent().scope(), original.intent().controlTargetId(),
                original.intent().targetNamespace());
        long now = System.currentTimeMillis();
        GatewayConfigAction compensation = new GatewayConfigAction(UUID.randomUUID().toString(), original.sessionId(),
                original.incidentId(), original.taskId(), actor, reverse, reverse.intentHash(), POLICY_VERSION,
                List.of(), UUID.randomUUID().toString(), original.actionId(), ConfigActionStatus.PENDING_APPROVAL,
                null, now, now, now + PLAN_TTL_MILLIS, 0);
        try { return actions.save(compensation); }
        catch (com.rover.agent.core.repository.ActionUpdateConflictException conflict) {
            throw conflict("补偿计划已被并发创建");
        } catch (IllegalStateException persistenceFailure) {
            // JdbcAgentStore wraps SQL exceptions from unique indexes as IllegalStateException.
            // Do not turn a concurrent duplicate compensation into a 500 or hide other store faults.
            if (hasUniqueConstraintCause(persistenceFailure)) throw conflict("补偿计划已被并发创建");
            throw persistenceFailure;
        }
    }

    private static boolean hasUniqueConstraintCause(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    private boolean targetsCurrent(GatewayConfigAction action) {
        var intent = action.intent();
        return intent.scope().equals(control.resourceScope()) && intent.controlTargetId().equals(control.controlTargetId())
                && intent.targetNamespace().equals(control.resourceNamespace());
    }

    private static boolean sameValue(String left, String right) {
        try { return Double.parseDouble(left) == Double.parseDouble(right); }
        catch (RuntimeException invalid) { return false; }
    }

    private static boolean validSampleRate(String value) {
        if (value == null || value.length() > 32) return false;
        try { double rate = Double.parseDouble(value); return Double.isFinite(rate) && (rate == 0 || rate == 1); }
        catch (NumberFormatException invalid) { return false; }
    }

    public List<GatewayConfigAction> bySession(String sessionId, String actor) {
        access.require(actor, Permission.READ);
        requireSession(sessionId, actor, true);
        return actions.bySession(sessionId);
    }

    /** Server-authoritative button eligibility; the POST endpoint repeats all checks before creating the proposal. */
    public List<String> compensationEligibleActionIds(String sessionId, String actor) {
        access.require(actor, Permission.READ);
        requireSession(sessionId, actor, true);
        if (!access.permissions(actor).contains(Permission.PROPOSE)
                || !durableReady.getAsBoolean() && access.managed()) return List.of();
        return actions.bySession(sessionId).stream().filter(action -> eligibleForCompensation(action))
                .map(GatewayConfigAction::actionId).toList();
    }

    private boolean eligibleForCompensation(GatewayConfigAction action) {
        if (action.status() != ConfigActionStatus.APPLIED || action.compensatesActionId() != null
                || actions.findCompensation(action.actionId()).isPresent() || !targetsCurrent(action)) return false;
        return configs.configs().stream()
                .filter(c -> ConfigActionIntent.ALLOWED_KEY.equals(c.key()) && "gateway".equals(c.component()))
                .findFirst().filter(c -> !c.unavailable() && c.value() != null
                        && sameValue(c.value(), action.intent().desiredValue())).isPresent();
    }

    public GatewayConfigAction approve(String actionId, String expectedHash, String actor) {
        access.require(actor, Permission.APPROVE);
        if (!access.writeAllowed(actor)) throw new AgentAccess.Denied("写入停用、授权撤销或存储不健康");
        GatewayConfigAction current = requireAction(actionId, actor);
        if (current.status()!=ConfigActionStatus.PENDING_APPROVAL)
            throw conflict("配置动作当前不在待审批状态");
        if (expectedHash==null || !current.intentHash().equals(expectedHash))
            throw conflict("计划摘要已变化，请刷新后重新确认");
        if (current.expiresAtMillis()<=System.currentTimeMillis()) {
            actions.transition(actionId, ConfigActionStatus.PENDING_APPROVAL,
                    a -> a.withStatus(ConfigActionStatus.EXPIRED, "配置计划已过期，未提交 Gateway 写入", System.currentTimeMillis()));
            throw conflict("配置计划已过期");
        }
        if (Objects.equals(current.requestedBy(), actor) || current.approvals().stream().anyMatch(a -> a.approver().equals(actor)))
            throw conflict("配置动作要求两名不同于提议人的审批人");
        ConfigActionApproval approval = new ConfigActionApproval(actor, current.intentHash(), current.policyVersion(), System.currentTimeMillis());
        return actions.transition(actionId, ConfigActionStatus.PENDING_APPROVAL, a -> {
            GatewayConfigAction with = a.withApproval(approval, ConfigActionStatus.PENDING_APPROVAL, System.currentTimeMillis());
            if (with.approvals().size() >= 2) return with.withStatus(ConfigActionStatus.QUEUED, null, System.currentTimeMillis());
            return with;
        }).orElseThrow(() -> conflict("配置动作已被其他审批处理"));
    }

    public GatewayConfigAction reject(String actionId, String actor) {
        access.require(actor, Permission.APPROVE);
        GatewayConfigAction current=requireAction(actionId,actor);
        if (current.status()!=ConfigActionStatus.PENDING_APPROVAL) throw conflict("配置动作当前不在待审批状态");
        return actions.transition(actionId, ConfigActionStatus.PENDING_APPROVAL,
                a -> a.withStatus(ConfigActionStatus.REJECTED, "由审批人拒绝", System.currentTimeMillis()))
                .orElseThrow(() -> conflict("配置动作已被其他人处理"));
    }

    public GatewayConfigAction reconcile(String actionId, String actor) {
        access.require(actor, Permission.APPROVE);
        GatewayConfigAction current=requireAction(actionId,actor);
        if (current.status()!=ConfigActionStatus.UNKNOWN) throw conflict("仅结果未知的配置动作可以对账");
        return actions.transition(actionId, ConfigActionStatus.UNKNOWN,
                a -> a.withStatus(ConfigActionStatus.RECONCILE_QUEUED, null, System.currentTimeMillis()))
                .orElseThrow(() -> conflict("配置动作状态已变化"));
    }

    public Optional<GatewayConfigAction> find(String actionId, String actor) {
        access.require(actor, Permission.READ);
        return actions.find(actionId).filter(a -> visible(a, actor));
    }
    public boolean canReadManagedActions(String actor) {
        return access.managed() && access.permissions(actor).contains(Permission.APPROVE);
    }
    private GatewayConfigAction requireAction(String id, String actor) {
        return actions.find(id).filter(a -> visible(a, actor)).orElseThrow(() ->
                new ActionRequestException(ActionRequestException.Code.NOT_FOUND,"找不到这条配置动作，或它不属于当前范围"));
    }
    private boolean visible(GatewayConfigAction action,String actor) {
        return requireSession(action.sessionId(), actor, true)!=null;
    }
    private Session requireSession(String id,String actor,boolean teamRead) {
        Session session=sessions.find(id).orElseThrow(() -> new ActionRequestException(ActionRequestException.Code.NOT_FOUND,"找不到会话"));
        boolean owner=Objects.equals(session.userId(), actor==null?null:actor.trim());
        boolean team=access.managed() && access.permissions(actor).contains(Permission.APPROVE);
        if ((!owner && !(teamRead && team)) || control.resourceScope()!=null && !control.resourceScope().equals(session.scope())
                || !control.resourceNamespace().equals(session.targetNamespace()))
            throw new ActionRequestException(ActionRequestException.Code.NOT_FOUND,"找不到会话");
        return session;
    }
    private static ActionRequestException invalid(String message) { return new ActionRequestException(ActionRequestException.Code.INVALID_REQUEST,message); }
    private static ActionRequestException conflict(String message) { return new ActionRequestException(ActionRequestException.Code.CONFLICT,message); }
}
