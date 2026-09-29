package com.rover.agent.core.model;

import java.util.List;

/**
 * 一条运维变更记录：Agent 提议、人批准、确定性执行器落地、结果可回读、必要时可补偿。
 *
 * <p><b>它是「建议」的载体，不是「已经做过」的证明。</b>只有 {@link ActionStatus#SUCCESS} /
 * {@link #ROLLED_BACK} 才代表 Gateway 上确实发生过一次被回读确认过的变更；
 * 其余状态要么没写、要么写完不确定，字段本身就是给人和后续回查用的证据。
 *
 * <p>字段分成四组，各回答一个问题：
 *
 * <ul>
 *   <li><b>改什么</b>：{@code type} / {@code routeId} / {@code serviceName} / {@code group} /
 *       {@code beforeWeight} / {@code desiredWeight}。变更的对象与两端取值全部显式落库，
 *       因此「这次到底想改什么」不依赖任何对话上下文。</li>
 *   <li><b>凭什么改</b>：{@code expectedRevision} 是提议时读到的路由版本，批准时会重新比对；
 *       {@code preview} 是网关给出的差异预览（不落盘、不生效），{@code impact} 是给人看的一句话影响说明。</li>
 *   <li><b>怎么追踪</b>：{@code applyOperationId} / {@code rollbackOperationId} 是两次写请求的幂等号，
 *       {@code appliedRevision} 是提交后网关生效的版本。它们让「超时了到底改没改」这件事可查，而不是靠重试去赌。</li>
 *   <li><b>谁动的手</b>：{@code requestedBy}（提议人，来自会话归属）与 {@code approvedBy} / {@code approvedAtMillis}
 *       （批准人），两者都必须能在事后回答「这条变更经谁的手生效」。</li>
 * </ul>
 *
 * @param actionId            变更 ID
 * @param sessionId           所属会话
 * @param incidentId          所属事件；提议时还没有事件绑定时为空
 * @param taskId              产生该提议的任务
 * @param type                变更类型
 * @param status              当前状态
 * @param routeId             路由标识（Gateway 的 route id，缺失时退化为业务前缀）
 * @param businessPrefix      路由匹配前缀，用于展示与二次核对
 * @param serviceName         目标服务名
 * @param group               目标版本分组（灰度版本，如 v2）
 * @param beforeWeight        提议时读到的权重，也是补偿回滚的目标值
 * @param desiredWeight       目标权重（{@code requestedValue} 换算后的真实写入值）
 * @param requestedValue      请求方给出的原始数值（可能是权重值，也可能是流量百分比）
 * @param requestedUnit       {@code requestedValue} 的单位；为空表示历史数据或未声明
 * @param beforeTrafficPercent 变更前该目标承接的流量占比（0~100，保留一位小数）
 * @param desiredTrafficPercent 变更后该目标预计承接的流量占比（0~100，保留一位小数）
 * @param expectedRevision    提议时读到的路由版本（乐观锁的期望值）
 * @param applyOperationId    提交变更的幂等号；提交前先落库，超时后用同一个号回查
 * @param appliedRevision     提交后网关生效的版本；未提交或结果未知时为空
 * @param rollbackOperationId 补偿回滚的幂等号
 * @param requestedBy         提议人（会话归属用户）；无认证上下文时为空
 * @param approvedBy          批准人；未批准时为空
 * @param approvedAtMillis    批准时刻；未批准时为 0
 * @param preview             变更预览（逐条差异文本），空表示还没有拿到预览
 * @param impact              给人看的影响说明
 * @param errorMessage        失败或不确定的原因说明；成功时为空
 * @param createdAtMillis     创建时刻
 * @param updatedAtMillis     最近一次状态变化时刻
 */
public record AgentAction(String actionId, String sessionId, String incidentId, String taskId,
                          ActionType type, ActionStatus status,
                          String routeId, String businessPrefix, String serviceName, String group,
                          int beforeWeight, int desiredWeight,
                          int requestedValue, WeightRequestUnit requestedUnit,
                          double beforeTrafficPercent, double desiredTrafficPercent,
                          int expectedRevision,
                          String applyOperationId, Integer appliedRevision, String rollbackOperationId,
                          String requestedBy, String approvedBy, long approvedAtMillis,
                          List<String> preview, String impact, String errorMessage,
                          long createdAtMillis, long updatedAtMillis) {

    /**
     * 权重上限。
     *
     * <p>与 Gateway {@code RouteTarget.MAX_WEIGHT} 同口径。领域层不能依赖网关实现，
     * 因此这里显式再声明一次：两边漂移时，网关会拒掉越界值，而这里负责在提议阶段就拦下它。
     */
    public static final int MAX_WEIGHT = 10_000;

    public AgentAction {
        incidentId = blankToNull(incidentId);
        taskId = blankToNull(taskId);
        applyOperationId = blankToNull(applyOperationId);
        rollbackOperationId = blankToNull(rollbackOperationId);
        requestedBy = blankToNull(requestedBy);
        approvedBy = blankToNull(approvedBy);
        preview = preview == null ? List.of() : List.copyOf(preview);
        impact = impact == null ? "" : impact;
        errorMessage = blankToNull(errorMessage);
    }

    /** 目标标签：{@code service@group}，与网关的集群键同一口径。 */
    public String targetLabel() {
        return group == null || group.isBlank() ? serviceName : serviceName + "@" + group;
    }

    /** 是否已经落定。 */
    public boolean settled() {
        return status.settled();
    }

    /** 是否已经提交过写请求（无论结果是否确认）。 */
    public boolean submitted() {
        return status.submitted() || appliedRevision != null;
    }

    /** 是否已经产生过对 Gateway 的真实写入窗口（用于 UI 提示「这条可能已经生效」）。 */
    public boolean everApplied() {
        return appliedRevision != null;
    }

    /** 一句话说明「改什么」：{@code order-service@v2 5 → 20}。 */
    public String describe() {
        return targetLabel() + " " + beforeWeight + " → " + desiredWeight;
    }

    /** 流量占比变化：{@code 5.0% → 17.4%}；占比未知时退化为权重文本，不编造数字。 */
    public String trafficPercentText() {
        if (beforeTrafficPercent < 0 || desiredTrafficPercent < 0) {
            return describe();
        }
        return formatPercent(beforeTrafficPercent) + " → " + formatPercent(desiredTrafficPercent);
    }

    /**
     * 请求方当初到底要的是什么：{@code 权重值 20} / {@code 流量占比 20%}。
     *
     * <p>审批卡上必须保留这一行：真正被人批准的是「20% 流量」还是「权重 20」，
     * 事后只能靠它回答，而 {@code desiredWeight} 只是换算结果。
     */
    public String requestText() {
        if (requestedUnit == null) {
            return "权重 " + desiredWeight + "（未声明单位）";
        }
        return requestedUnit == WeightRequestUnit.TRAFFIC_PERCENT
                ? "流量占比 " + requestedValue + "%"
                : "权重值 " + requestedValue;
    }

    /** 占比统一保留一位小数，避免 17.399999 这种浮点噪音出现在审批卡上。 */
    private static String formatPercent(double percent) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", percent);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
