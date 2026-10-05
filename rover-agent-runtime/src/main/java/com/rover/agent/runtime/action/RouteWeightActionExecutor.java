package com.rover.agent.runtime.action;

import com.rover.agent.core.model.ActionStatus;
import com.rover.agent.core.model.ActionType;
import com.rover.agent.core.model.AgentAction;
import com.rover.agent.core.port.RouteChangePreview;
import com.rover.agent.core.port.RouteChangeResult;
import com.rover.agent.core.port.RouteControlException;
import com.rover.agent.core.port.RouteControlPort;
import com.rover.agent.core.port.RouteControlRoute;
import com.rover.agent.core.port.RouteControlState;
import com.rover.agent.core.port.RouteControlTarget;
import com.rover.agent.core.port.RouteOperation;
import com.rover.agent.core.repository.AgentActionRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 灰度权重执行器：预检、预览、保存幂等号、提交与回读验证。
 * 预检核对 revision、目标和原权重；不一致时不提交。
 * 回滚仅恢复目标权重，当前值须仍等于本次写入值；响应未知时按原 operationId 确认。
 */
public final class RouteWeightActionExecutor implements ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(RouteWeightActionExecutor.class);

    private final RouteControlPort control;
    private final AgentActionRepository actions;

    public RouteWeightActionExecutor(RouteControlPort control, AgentActionRepository actions) {
        this.control = control;
        this.actions = actions;
    }

    @Override
    public ActionType type() {
        return ActionType.ADJUST_ROUTE_TARGET_WEIGHT;
    }

    @Override
    public AgentAction execute(AgentAction action) {
        Precondition precondition = check(action);
        if (!precondition.passed()) {
            log.warn("变更 {} 预检未通过：{}", action.actionId(), precondition.message());
            return settle(action, ActionStatus.PRECONDITION_FAILED, precondition.message());
        }
        RouteChangePreview preview;
        try {
            preview = control.preview(action.routeId(), action.serviceName(), action.group(), action.desiredWeight());
        } catch (RuntimeException ex) {
            // 预览失败时尚未提交，不标记为结果未知。
            return settle(action, ActionStatus.FAILED,
                    "变更预览未通过，未提交任何写请求：" + failureText(ex));
        }
        List<String> changes = preview.changes().isEmpty() ? List.of(preview.message()) : preview.changes();

        String operationId = UUID.randomUUID().toString();
        AgentAction submitting = new ActionDraft(action).preview(changes).applyOperation(operationId)
                .move(ActionStatus.EXECUTING, null).toAction(now());
        actions.save(submitting);

        RouteChangeResult result;
        try {
            result = control.adjustTargetWeight(action.routeId(), action.serviceName(), action.group(),
                    action.desiredWeight(), action.expectedRevision(), operationId);
        } catch (RouteControlException ex) {
            return recoverOrFail(submitting, ex, false);
        } catch (RuntimeException ex) {
            // 非端口约定的异常同样按「没拿到结果」处理：比起谎称失败，宁可让人类去核实一次。
            return recoverOrFail(submitting,
                    new RouteControlException(RouteControlException.Kind.UNAVAILABLE, failureText(ex), ex), false);
        }
        return verifyApplied(submitting, result.revision(), action.desiredWeight(),
                ActionStatus.VERIFYING, ActionStatus.SUCCESS);
    }

    @Override
    public AgentAction rollback(AgentAction action) {
        RouteControlState state;
        try {
            state = control.state();
        } catch (RuntimeException ex) {
            return backToSuccess(action, "回滚未提交：读取 Gateway 路由失败（" + failureText(ex) + "），"
                    + "当前权重没有被改动，可以稍后重试回滚");
        }
        Located located = locate(state, action);
        if (!located.passed()) {
            return backToSuccess(action, "回滚未提交：" + located.message() + "，请人工确认当前状态");
        }
        int current = located.target().weight();
        if (current == action.beforeWeight()) {
            // 已经是补偿目标：再写一次不会让世界更好，只会多一条操作记录。
            return settle(action, ActionStatus.ROLLED_BACK, null);
        }
        if (current != action.desiredWeight()) {
            // 别人在这次变更之后改过同一个目标。写回 beforeWeight 会盖掉那次修改。
            return settle(action, ActionStatus.ROLLBACK_PRECONDITION_FAILED,
                    "回滚未提交：当前权重是 " + current + "，已经不是这次变更写入的 " + action.desiredWeight()
                            + "，不能自动补偿到 " + action.beforeWeight() + "。请人工确认后再决定是否另开一条变更。");
        }
        String operationId = UUID.randomUUID().toString();
        AgentAction submitting = new ActionDraft(action).rollbackOperation(operationId)
                .move(ActionStatus.ROLLING_BACK, null).toAction(now());
        actions.save(submitting);

        RouteChangeResult result;
        try {
            // 用「刚刚读到的版本」而不是提议时的版本：回滚面对的是当前世界，不是当初那张卡。
            result = control.adjustTargetWeight(action.routeId(), action.serviceName(), action.group(),
                    action.beforeWeight(), state.revision(), operationId);
        } catch (RouteControlException ex) {
            return recoverOrFail(submitting, ex, true);
        } catch (RuntimeException ex) {
            return recoverOrFail(submitting,
                    new RouteControlException(RouteControlException.Kind.UNAVAILABLE, failureText(ex), ex), true);
        }
        return verifyApplied(submitting, result.revision(), action.beforeWeight(),
                ActionStatus.ROLLING_BACK, ActionStatus.ROLLED_BACK);
    }

    @Override
    public AgentAction resolve(AgentAction action) {
        boolean rollbackPhase = action.rollbackOperationId() != null;
        String operationId = rollbackPhase ? action.rollbackOperationId() : action.applyOperationId();
        if (operationId == null) {
            return settle(action, ActionStatus.FAILED, "这条变更没有可回查的操作号，无法确认结果，请人工核对路由当前权重");
        }
        RouteOperation operation;
        try {
            operation = control.operation(operationId);
        } catch (RuntimeException ex) {
            return settle(action, ActionStatus.UNCERTAIN,
                    "回查仍未取到结果（" + failureText(ex) + "），稍后可以再次确认；不要重新执行这条变更");
        }
        if (operation.applied()) {
            int revision = operation.revision() > 0 ? operation.revision()
                    : (action.appliedRevision() == null ? 0 : action.appliedRevision());
            return verifyApplied(action, revision,
                    rollbackPhase ? action.beforeWeight() : action.desiredWeight(),
                    rollbackPhase ? ActionStatus.ROLLING_BACK : ActionStatus.VERIFYING,
                    rollbackPhase ? ActionStatus.ROLLED_BACK : ActionStatus.SUCCESS);
        }
        String detail = operation.message().isBlank() ? "网关未给出说明" : operation.message();
        return switch (operation.status()) {
            case CONFLICT -> rollbackPhase
                    ? backToSuccess(action, "回查确认：回滚因版本冲突没有生效（" + detail + "），可以稍后重试")
                    : settle(action, ActionStatus.PRECONDITION_FAILED,
                            "回查确认：变更因版本冲突没有生效，Gateway 未被改动（" + detail + "）");
            case REJECTED, FAILED -> rollbackPhase
                    ? backToSuccess(action, "回查确认：回滚没有生效（" + detail + "），当前权重仍是变更后的值")
                    : settle(action, ActionStatus.FAILED, "回查确认：变更没有生效（" + detail + "）");
            default -> settle(action, ActionStatus.UNCERTAIN,
                    "回查没有这条操作的记录（" + detail + "）：无法确认是否已生效，请人工核对当前权重后再决定是否回滚");
        };
    }

    // ---------------------------------------------------------------- 预检与定位

    /** 预检：revision、路由、目标、当前权重四件事全部对上才允许提交。 */
    private Precondition check(AgentAction action) {
        RouteControlState state;
        try {
            state = control.state();
        } catch (RuntimeException ex) {
            return Precondition.rejected("读取 Gateway 路由失败，未提交任何写请求：" + failureText(ex));
        }
        if (state.revision() != action.expectedRevision()) {
            return Precondition.rejected("路由已在审批期间发生变化（revision " + action.expectedRevision() + " → "
                    + state.revision() + "），这条变更基于旧状态，请重新生成变更计划");
        }
        Located located = locate(state, action);
        if (!located.passed()) {
            return Precondition.rejected(located.message());
        }
        if (located.target().weight() != action.beforeWeight()) {
            return Precondition.rejected("目标权重已变化（" + action.beforeWeight() + " → "
                    + located.target().weight() + "），这条变更的前提不再成立，请重新生成变更计划");
        }
        return Precondition.accepted();
    }

    /** 在给定路由表里找到这条变更指向的路由与版本目标。 */
    private static Located locate(RouteControlState state, AgentAction action) {
        RouteControlRoute route = RouteLocator.route(state, action.routeId(), action.businessPrefix());
        if (route == null) {
            return Located.missing("找不到路由 " + routeLabel(action) + "：它可能已被删除或改名");
        }
        if (route.targets().isEmpty()) {
            return Located.missing(route.staticUpstream()
                    ? "路由 " + route.display() + " 是静态上游地址，没有版本权重可调整"
                    : "路由 " + route.display() + " 没有配置版本目标，无法调整权重");
        }
        RouteControlTarget target = RouteLocator.target(route, action.serviceName(), action.group());
        if (target == null) {
            return Located.missing("路由 " + route.display() + " 上没有 " + action.targetLabel()
                    + " 这个版本目标（现有：" + RouteLocator.describeTargets(route) + "）");
        }
        return Located.found(route, target);
    }

    // ---------------------------------------------------------------- 提交后的两种收尾

    /** 提交后回读权重，一致才判定成功；读取失败时标记 UNCERTAIN。 */
    private AgentAction verifyApplied(AgentAction action, int appliedRevision, int expectedWeight,
                                     ActionStatus verifying, ActionStatus success) {
        AgentAction submitted = new ActionDraft(action).appliedRevision(appliedRevision)
                .move(verifying, null).toAction(now());
        actions.save(submitted);

        RouteControlState state;
        try {
            state = control.state();
        } catch (RuntimeException ex) {
            return settle(submitted, ActionStatus.UNCERTAIN,
                    "变更已提交（revision " + appliedRevision + "），但回读路由失败，结果待确认："
                            + failureText(ex) + "；可用同一操作号再次确认，不要重新执行");
        }
        Located located = locate(state, action);
        if (!located.passed()) {
            return settle(submitted, ActionStatus.UNCERTAIN,
                    "变更已提交（revision " + appliedRevision + "），但回读时" + located.message()
                            + "，无法确认当前权重：请人工核对");
        }
        int current = located.target().weight();
        if (current != expectedWeight) {
            return settle(submitted, ActionStatus.FAILED,
                    "回读确认不一致：当前权重为 " + current + "，期望 " + expectedWeight + "，变更没有按预期生效");
        }
        log.info("变更 {} 已生效并回读确认：{}，revision {}", action.actionId(), action.describe(), appliedRevision);
        return settle(submitted, success, null);
    }

    /** 写请求失败时记录对应结果，仅未收到响应时回查 operationId。 */
    private AgentAction recoverOrFail(AgentAction action, RouteControlException failure, boolean rollbackPhase) {
        String detail = failureText(failure);
        switch (failure.kind()) {
            case CONFLICT -> {
                String message = failure.currentRevision() >= 0
                        ? detail + "（网关当前 revision " + failure.currentRevision() + "）" : detail;
                return rollbackPhase
                        ? backToSuccess(action, "回滚没有生效：路由已被别人改动（" + message + "），可以稍后重试")
                        : settle(action, ActionStatus.PRECONDITION_FAILED,
                                "提交时版本冲突：这条变更没有生效，Gateway 未被改动（" + message + "）");
            }
            case REJECTED, FAILED -> {
                return rollbackPhase
                        ? backToSuccess(action, "回滚没有生效（" + detail + "），当前权重仍是变更后的值")
                        : settle(action, ActionStatus.FAILED, "变更没有生效（" + detail + "）");
            }
            default -> {
                // 落这里：没拿到响应。只能用原 operationId 回查，绝不换号重提。
            }
        }
        String operationId = rollbackPhase ? action.rollbackOperationId() : action.applyOperationId();
        RouteOperation operation;
        try {
            operation = control.operation(operationId);
        } catch (RuntimeException queryError) {
            return settle(action, ActionStatus.UNCERTAIN,
                    "写请求没有拿到响应（" + detail + "），用同一操作号回查也没有结果（" + failureText(queryError)
                            + "）：结果未知，请勿重新执行，稍后用同一操作号确认");
        }
        if (operation.applied()) {
            int revision = operation.revision() > 0 ? operation.revision()
                    : (action.appliedRevision() == null ? 0 : action.appliedRevision());
            return verifyApplied(action, revision,
                    rollbackPhase ? action.beforeWeight() : action.desiredWeight(),
                    rollbackPhase ? ActionStatus.ROLLING_BACK : ActionStatus.VERIFYING,
                    rollbackPhase ? ActionStatus.ROLLED_BACK : ActionStatus.SUCCESS);
        }
        return switch (operation.status()) {
            case CONFLICT -> rollbackPhase
                    ? backToSuccess(action, "回查确认：回滚因版本冲突没有生效，可以稍后重试")
                    : settle(action, ActionStatus.PRECONDITION_FAILED,
                            "回查确认：变更因版本冲突没有生效，Gateway 未被改动");
            case REJECTED, FAILED -> rollbackPhase
                    ? backToSuccess(action, "回查确认：回滚没有生效，当前权重仍是变更后的值")
                    : settle(action, ActionStatus.FAILED, "回查确认：变更没有生效");
            default -> settle(action, ActionStatus.UNCERTAIN,
                    "写请求没有拿到响应，回查也没有这条操作的记录：无法确认是否已生效，请人工核对当前权重");
        };
    }

    /** 补偿提交前失败时退回 SUCCESS 并记录原因，允许再次回滚。 */
    private AgentAction backToSuccess(AgentAction action, String message) {
        log.warn("变更 {} 的回滚未提交：{}", action.actionId(), message);
        return settle(action, ActionStatus.SUCCESS, message);
    }

    private AgentAction settle(AgentAction action, ActionStatus status, String error) {
        AgentAction settled = new ActionDraft(action).move(status, error).toAction(now());
        actions.save(settled);
        return settled;
    }

    private static String routeLabel(AgentAction action) {
        if (action.businessPrefix() != null && !action.businessPrefix().isBlank()) {
            return action.businessPrefix();
        }
        return action.routeId() == null || action.routeId().isBlank() ? "(未命名路由)" : action.routeId();
    }

    private static String failureText(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    /** 预检结果，包含是否允许执行及拒绝原因。 */
    private record Precondition(boolean passed, String message) {

        static Precondition accepted() {
            return new Precondition(true, "");
        }

        static Precondition rejected(String message) {
            return new Precondition(false, message);
        }
    }

    /** 定位结论。 */
    private record Located(boolean passed, RouteControlRoute route, RouteControlTarget target, String message) {

        static Located found(RouteControlRoute route, RouteControlTarget target) {
            return new Located(true, route, target, "");
        }

        static Located missing(String message) {
            return new Located(false, null, null, message);
        }
    }
}
