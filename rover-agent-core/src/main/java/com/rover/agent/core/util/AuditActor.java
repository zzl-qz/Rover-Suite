package com.rover.agent.core.util;

/** 由认证入口或持久任务调度器设置；模型和请求体不能指定审计主体。 */
public final class AuditActor implements AutoCloseable {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private final String previous;
    private AuditActor(String actor) { previous = CURRENT.get(); CURRENT.set(actor); }
    public static AuditActor enter(String actor) { return new AuditActor(actor); }
    public static String current(String fallback) { return CURRENT.get() == null ? fallback : CURRENT.get(); }
    @Override public void close() { if (previous == null) { CURRENT.remove(); } else { CURRENT.set(previous); } }
}
