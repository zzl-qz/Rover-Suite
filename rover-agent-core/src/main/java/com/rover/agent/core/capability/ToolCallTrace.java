package com.rover.agent.core.capability;

/**
 * 当前线程的模型工具调用号，用于关联证据。
 * 进入回调前绑定，退出回调时清除。
 */
public final class ToolCallTrace {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private ToolCallTrace() { }

    /** 绑定本次工具调用号。空号视为没有调用，清掉上一轮残留。 */
    public static void bind(String toolCallId) {
        if (toolCallId == null || toolCallId.isBlank()) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(toolCallId);
    }

    /** 离开工具回调时必须清掉，避免下一次没有调用号的取数继承上一次的号。 */
    public static void clear() {
        CURRENT.remove();
    }

    /** 当前调用号；没有绑定时为空串。 */
    public static String current() {
        String value = CURRENT.get();
        return value == null ? "" : value;
    }
}
