package com.rover.agent.core.capability;

/**
 * 当前线程正在执行的模型工具调用号。
 *
 * <p>工具方法跑在模型框架的回调里，证据是在这次回调期间由 {@link CapabilityExecutor} 产生的。
 * 调用号由外层在进入回调前绑定、离开时清掉，证据落库时直接读这里，不必给每个只读方法再加一个参数。
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
