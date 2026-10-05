package com.rover.agent.runtime.action;

/**
 * 人工变更操作因记录缺失、归属或状态不符而被拒绝。
 * 无权访问统一按 NOT_FOUND 处理；执行失败由变更状态记录。
 */
public class ActionRequestException extends RuntimeException {

    /** 拒绝原因。 */
    public enum Code {
        /** 记录不存在，或不属于当前用户。 */
        NOT_FOUND,
        /** 当前状态不允许这个操作（例如已经批准过、还没成功就回滚）。 */
        CONFLICT
    }

    private final Code code;

    public ActionRequestException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
