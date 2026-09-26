package com.rover.agent.core.model;

/**
 * 意图的细分主题：同样是 {@link AgentIntent#EXPLAIN}，「你能做什么」与「帮我总结这次故障」
 * 需要完全不同的数据来源，用主题把两者分开，避免靠问题文本二次猜测。
 */
public enum IntentTopic {

    /** 无细分主题 */
    NONE,

    /** 询问系统能力（由能力注册表生成真实能力清单） */
    CAPABILITIES,

    /** 围绕当前事件/上下文做解释与总结 */
    INCIDENT,

    /** 一般性解释 */
    GENERAL
}