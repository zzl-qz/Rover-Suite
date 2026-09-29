package com.rover.agent.runtime.action;

/**
 * 一次人工操作（批准 / 拒绝 / 回滚 / 确认）被拒绝。
 *
 * <p>它表达的是「这个请求本身不该被受理」——记录不存在、不属于当前用户、状态已经不允许这么做。
 * 与「执行失败」严格区分：执行失败会落成 {@code FAILED} 状态给人看，而这里的结果是 HTTP 层的 4xx，
 * 不会在变更历史上留下任何痕迹。
 *
 * <p>归属判定统一用 {@link Code#NOT_FOUND} 而不是「不许你操作」：别人的变更对当前用户应该像不存在一样，
 * 否则一个 UUID 就能探出「这里有一条别人提的变更」。
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
