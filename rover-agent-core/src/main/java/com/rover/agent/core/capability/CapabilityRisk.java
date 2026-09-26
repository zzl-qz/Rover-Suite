package com.rover.agent.core.capability;

/**
 * 能力风险级别：Agent 只能自主调用只读能力，写能力即便将来接入也不由 Planner 自主选择。
 */
public enum CapabilityRisk {

    /** 只读：只读取快照与事实，不改变任何配置或流量 */
    READ_ONLY,

    /** 写操作：会改变生产状态；本阶段不存在这样的能力 */
    WRITE
}