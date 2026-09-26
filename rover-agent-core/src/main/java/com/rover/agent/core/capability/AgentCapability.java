package com.rover.agent.core.capability;

/**
 * Agent 可调用的能力：不再把能力写死成图节点，而是先声明「Rover 有哪些能力」，再由计划去选择。
 *
 * 命名与数据源对应，不出现任意函数名或工具地址：执行映射（哪个能力调哪个只读端口）由
 * {@link CapabilityExecutor} 用代码固定下来，模型无法凭空创造新能力。
 */
public enum AgentCapability {

    /** 查询 Gateway 路由与上游服务发现模式 */
    ROUTE_QUERY,

    /** 查询注册中心实例与目标服务健康实例数 */
    INSTANCE_QUERY,

    /** 查询 Gateway 全局流量与拒绝计数（最近窗口） */
    GATEWAY_METRICS_QUERY,

    /** 查询指定请求路径的抽样追踪 */
    TRACE_QUERY,

    /** 读取 Gateway 配置；尚未接入适配器 */
    CONFIG_READ,

    /** 查询 Gateway/管理口事件；尚未接入适配器 */
    EVENT_QUERY
}