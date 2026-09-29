package com.rover.agent.runtime.action;

import com.rover.agent.core.model.ActionStatus;
import com.rover.agent.core.model.AgentAction;
import java.util.List;

/**
 * 变更记录的迁移草稿：只描述「这一次改了什么」，其余字段从原记录原样带过去。
 *
 * <p>为什么需要它：{@link AgentAction} 是不可变记录，状态机每走一步都要产生一条新记录。
 * 如果每一步都手写 24 个字段的构造，改一个字段时漏带另一个字段的事故迟早会发生
 * （例如回滚时把 {@code beforeWeight} 忘了，补偿就永远回不到原值）。这里把「改哪几个字段」
 * 收敛到几个具名方法上，其余字段由构造器负责保持。
 *
 * <p>{@code updatedAtMillis} 只在 {@link #toAction(long)} 落定时写一次，因此「记录是什么时候变成现在这样的」
 * 是调用方给定的时刻，而不是某次不小心触发的写库动作的时间。
 */
final class ActionDraft {

    private final AgentAction origin;
    private ActionStatus status;
    private String applyOperationId;
    private Integer appliedRevision;
    private String rollbackOperationId;
    private String approvedBy;
    private long approvedAtMillis;
    private List<String> preview;
    private String errorMessage;

    ActionDraft(AgentAction origin) {
        this.origin = origin;
        this.status = origin.status();
        this.applyOperationId = origin.applyOperationId();
        this.appliedRevision = origin.appliedRevision();
        this.rollbackOperationId = origin.rollbackOperationId();
        this.approvedBy = origin.approvedBy();
        this.approvedAtMillis = origin.approvedAtMillis();
        this.preview = origin.preview();
        this.errorMessage = origin.errorMessage();
    }

    /** 迁移状态；{@code error} 非空时同时更新说明（成功时传 {@code null} 把上一轮的说明清掉）。 */
    ActionDraft move(ActionStatus next, String error) {
        this.status = next;
        this.errorMessage = error;
        return this;
    }

    /** 记下即将发出的写请求的幂等号——必须在发请求之前落库，超时后才有号可查。 */
    ActionDraft applyOperation(String operationId) {
        this.applyOperationId = operationId;
        return this;
    }

    /** 记下补偿回滚的幂等号。 */
    ActionDraft rollbackOperation(String operationId) {
        this.rollbackOperationId = operationId;
        return this;
    }

    /** 记下网关生效的版本；传 {@code null} 表示不改（没有更可靠的版本信息时不要覆盖已知值）。 */
    ActionDraft appliedRevision(Integer revision) {
        if (revision != null) {
            this.appliedRevision = revision;
        }
        return this;
    }

    /** 记下批准人。 */
    ActionDraft approvedBy(String user, long atMillis) {
        this.approvedBy = user;
        this.approvedAtMillis = atMillis;
        return this;
    }

    /** 记下变更预览（逐条差异文本）。 */
    ActionDraft preview(List<String> changes) {
        this.preview = changes;
        return this;
    }

    /** 落定成一条新的不可变记录。 */
    AgentAction toAction(long atMillis) {
        return new AgentAction(origin.actionId(), origin.sessionId(), origin.incidentId(), origin.taskId(),
                origin.type(), status, origin.routeId(), origin.businessPrefix(), origin.serviceName(),
                origin.group(), origin.beforeWeight(), origin.desiredWeight(),
                origin.requestedValue(), origin.requestedUnit(),
                origin.beforeTrafficPercent(), origin.desiredTrafficPercent(),
                origin.expectedRevision(),
                applyOperationId, appliedRevision, rollbackOperationId, origin.requestedBy(), approvedBy,
                approvedAtMillis, preview, origin.impact(), errorMessage, origin.createdAtMillis(), atMillis);
    }
}
