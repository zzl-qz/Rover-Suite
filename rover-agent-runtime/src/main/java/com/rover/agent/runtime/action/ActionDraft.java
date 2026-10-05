package com.rover.agent.runtime.action;

import com.rover.agent.core.model.ActionStatus;
import com.rover.agent.core.model.AgentAction;
import java.util.List;

/**
 * 变更记录的迁移草稿，未修改字段沿用原记录。
 * updatedAtMillis 在 toAction 时由调用方指定。
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
