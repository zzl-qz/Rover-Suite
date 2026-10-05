package com.rover.agent.core.capability;

/** Agent 能力标识，执行映射由 {@link CapabilityExecutor} 固定。 */
public enum AgentCapability {

    /** 查询 Gateway 路由与上游服务发现模式 */
    ROUTE_QUERY,

    /** 查询注册中心实例与目标服务健康实例数 */
    INSTANCE_QUERY,

    /** 查询 Gateway 全局流量与拒绝计数（最近窗口） */
    GATEWAY_METRICS_QUERY,

    /** 查询指定请求路径的抽样追踪 */
    TRACE_QUERY,

    /** 读取 Gateway / Nameserver 当前生效配置（限流、熔断、超时、采样率等） */
    CONFIG_READ,

    /** 查询注册中心最近事件（注册、注销、剔除、标记不健康、推送） */
    EVENT_QUERY,

    /** 查询落盘历史日志（配置变更/回滚/错误/实例事件/指标采样/慢与错误链路） */
    LOG_QUERY,

    /** 检索运维知识库（怎么配置/怎么接入/怎么排查等文档与经验） */
    KNOWLEDGE_RETRIEVAL
}