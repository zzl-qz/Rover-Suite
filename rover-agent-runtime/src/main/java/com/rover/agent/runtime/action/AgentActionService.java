package com.rover.agent.runtime.action;

import com.rover.agent.core.model.ActionStatus;
import com.rover.agent.core.model.ActionType;
import com.rover.agent.core.model.AgentAction;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.WeightRequestUnit;
import com.rover.agent.core.port.RouteChangePreview;
import com.rover.agent.core.port.RouteControlPort;
import com.rover.agent.core.port.RouteControlRoute;
import com.rover.agent.core.port.RouteControlState;
import com.rover.agent.core.port.RouteControlTarget;
import com.rover.agent.core.repository.AgentActionRepository;
import com.rover.agent.core.repository.AgentSessionRepository;
import com.rover.agent.core.util.Texts;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 受控变更的应用入口：提议、批准、拒绝、回滚、结果确认，以及「谁能动这一条」。
 *
 * <p>这一层负责的是<b>流程与权限</b>，不是技术执行：它把「谁在什么状态下能做什么」钉死，
 * 把「怎么把 Gateway 改对」交给 {@link ActionExecutor}。两者分开的好处是，
 * 想加一种新变更时只需要写一个新的执行器，流程与权限不用再改一遍、也不会两处不一致。
 *
 * <p>三条刻意的设计：
 *
 * <ol>
 *   <li><b>提议不执行</b>：{@link #propose} 只登记，一行写请求都不会发出去；
 *       模型能到达的最远处就是这里。</li>
 *   <li><b>状态迁移用 CAS</b>：批准与回滚都先做原子状态迁移，抢不到就说明别人先动了，
 *       于是「连点三下批准」只会有一次真实执行。</li>
 *   <li><b>归属即权限</b>：变更属于创建它的会话所属用户；不是本人创建的变更，对当前用户一律按不存在处理。
 *       审批对象必须对应用户自己看到的状态，这一点不接受「帮忙代批」。</li>
 * </ol>
 */
public final class AgentActionService {

    private static final Logger log = LoggerFactory.getLogger(AgentActionService.class);

    private final AgentActionRepository actions;
    private final AgentSessionRepository sessions;
    private final RouteControlPort control;
    private final ActionExecutor executor;

    public AgentActionService(AgentActionRepository actions, AgentSessionRepository sessions,
                              RouteControlPort control, ActionExecutor executor) {
        this.actions = actions;
        this.sessions = sessions;
        this.control = control;
        this.executor = executor;
    }

    // ---------------------------------------------------------------- 提议

    /**
     * 提议一条灰度权重变更：读当前路由、定位版本、把请求值换算成真实权重、登记一条待审批记录。
     *
     * <p>这是模型唯一能触达的写入口，而它做的事情只有「读 + 记一条建议」：
     * Gateway 的路由表不会因为这里被调用而改变一个字节。
     *
     * <p><b>单位必须由调用方显式给出。</b>「权重值」与「流量占比」是两种量纲，
     * 在一条权重为 95/5 的路由上，「20」既可能是权重值 20，也可能是 20% 流量（换算后约 2000），
     * 两者相差两个数量级。让模型在两者之间挑一个等于把一次真实变更押在语气判断上，
     * 因此 {@code unit} 缺失时这里不猜，直接返回 {@link ActionProposal#clarificationRequired}，
     * 让对话回到用户那里问清楚。
     *
     * <p>百分比到权重的换算也由这里完成：占比取决于整条路由的权重分布，
     * 只有代码读得到全部目标，模型手里只有它想改的那一个。
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
    public ActionProposal propose(String sessionId, String taskId, String incidentId, String route, String group,
                                  Integer requestedValue, WeightRequestUnit unit) {
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
        if (target.weight() == desiredWeight) {
            return ActionProposal.rejected("「" + target.label() + "」当前权重已经是 " + desiredWeight
                    + "，不需要变更");
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
                now, now);
        try {
            actions.save(action);
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
     * 把「请求值 + 单位」换成真正要写进路由表的权重。
     *
     * <p>占比换权重：设目标当前权重 {@code w}、同路由其他目标权重合计 {@code o}，
     * 要让目标承接 {@code p}% 流量，需要 {@code w' / (o + w') = p / 100}，即 {@code w' = o * p / (100 - p)}。
     * 这是一次只改一个目标的前提下的唯一解。解不存在，或者算出来的权重超出单目标上限时，
     * 这里不截断成一个近似值，而是让调用方回去说明「只改这一个版本做不到」。
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

    /** 请求值 → 真实权重的换算结论：要么给出一个权重，要么给出一句「为什么算不出来」。 */
    private record WeightResolution(boolean passed, int weight, String message) {

        static WeightResolution ok(int weight) {
            return new WeightResolution(true, weight, "");
        }

        static WeightResolution blocked(String message) {
            return new WeightResolution(false, 0, message);
        }
    }

    /**
     * 提议阶段的预览：只是把差异提前摊开给人看。
     *
     * <p>预览失败不阻止提议——真正的把关点在批准之后（预检 + 预览 + 乐观锁，三道都在），
     * 这里失败就把原因写成一行备注，别让「预览接口不可用」把「提个建议」也一起挡住。
     */
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
        return session(sessionId, userId).map(owned -> actions.bySession(sessionId));
    }

    /** 按 ID 取一条变更记录；不存在或不属于当前用户时为空。 */
    public Optional<AgentAction> find(String actionId, String userId) {
        return actions.find(actionId).filter(action -> ownedBy(action, userId));
    }

    // ---------------------------------------------------------------- 人工动作

    /** 批准并<b>立即执行</b>：抢到「待审批 → 执行中」的那一次请求才会真正提交变更。 */
    public AgentAction approve(String actionId, String userId) {
        AgentAction pending = require(actionId, userId);
        if (!pending.status().awaitingApproval()) {
            throw new ActionRequestException(ActionRequestException.Code.CONFLICT,
                    "这条变更当前是「" + pending.status().label() + "」，不能再次批准");
        }
        long now = System.currentTimeMillis();
        String approver = normalize(userId);
        AgentAction claimed = actions.transition(actionId, ActionStatus.PENDING_APPROVAL,
                        current -> new ActionDraft(current).approvedBy(approver, now)
                                .move(ActionStatus.EXECUTING, null).toAction(now))
                .orElseThrow(() -> new ActionRequestException(ActionRequestException.Code.CONFLICT,
                        "这条变更已经被处理过了，不会重复执行"));
        log.info("变更 {} 已批准，开始执行：{}（批准人 {}）", actionId, claimed.describe(), approver);
        return execute(claimed);
    }

    /** 拒绝：不执行，也不会执行。 */
    public AgentAction reject(String actionId, String userId) {
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
        AgentAction applied = require(actionId, userId);
        if (!applied.status().reversible()) {
            throw new ActionRequestException(ActionRequestException.Code.CONFLICT,
                    "这条变更当前是「" + applied.status().label() + "」，不能回滚");
        }
        long now = System.currentTimeMillis();
        AgentAction claimed = actions.transition(actionId, ActionStatus.SUCCESS,
                        current -> new ActionDraft(current).move(ActionStatus.ROLLING_BACK, null).toAction(now))
                .orElseThrow(() -> new ActionRequestException(ActionRequestException.Code.CONFLICT,
                        "这条变更已经被处理过了，不会重复回滚"));
        log.info("变更 {} 开始补偿回滚：{}", actionId, claimed.describe());
        return execute(claimed);
    }

    /** 结果确认：对「结果未知」的变更，用原 operationId 再查一次到底生效了没有。 */
    public AgentAction resolve(String actionId, String userId) {
        AgentAction action = require(actionId, userId);
        if (!action.status().resolvable()) {
            throw new ActionRequestException(ActionRequestException.Code.CONFLICT,
                    "这条变更当前是「" + action.status().label() + "」，不需要确认结果");
        }
        // 不加 CAS：确认本身就是一次幂等回查，重复点击最多多读一次网关的操作记录，不会造成第二次写入。
        return execute(action);
    }

    /** 按变更类型选执行器；目前只有一种，越界直接拒绝而不是「随便找个执行器试试」。 */
    private AgentAction execute(AgentAction action) {
        if (action.type() != executor.type()) {
            throw new ActionRequestException(ActionRequestException.Code.CONFLICT,
                    "没有可以执行「" + action.type().label() + "」的执行器");
        }
        try {
            return switch (action.status()) {
                case EXECUTING -> executor.execute(action);
                case ROLLING_BACK -> executor.rollback(action);
                default -> executor.resolve(action);
            };
        } catch (RuntimeException ex) {
            // 执行器约定不抛业务异常；真抛出来时也要留痕，否则库里会永远停在「执行中」。
            log.error("执行变更 {} 时出现未预期的异常", action.actionId(), ex);
            long now = System.currentTimeMillis();
            AgentAction failed = new ActionDraft(action).appliedRevision(null)
                    .move(ActionStatus.UNCERTAIN, "执行过程中出现未预期的异常，结果未知：" + failureText(ex))
                    .toAction(now);
            actions.save(failed);
            return failed;
        }
    }

    // ---------------------------------------------------------------- 归属

    private AgentAction require(String actionId, String userId) {
        return find(actionId, userId).orElseThrow(() -> new ActionRequestException(
                ActionRequestException.Code.NOT_FOUND, "找不到这条变更，或它不属于当前用户"));
    }

    private Optional<Session> session(String sessionId, String userId) {
        return sessions.find(sessionId).filter(found -> Objects.equals(found.userId(), normalize(userId)));
    }

    /** 变更的归属就是提议会话的归属；未启用登录时双方都为空，视为同一主体。 */
    private static boolean ownedBy(AgentAction action, String userId) {
        return Objects.equals(action.requestedBy(), normalize(userId));
    }

    private static String normalize(String userId) {
        return userId == null || userId.isBlank() ? null : userId.trim();
    }


    private static String failureText(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
