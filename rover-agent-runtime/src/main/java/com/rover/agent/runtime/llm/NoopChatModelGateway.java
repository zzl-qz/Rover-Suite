package com.rover.agent.runtime.llm;

import org.springframework.ai.chat.client.ChatClient;

/**
 * 未接入模型的缺省端口：始终是"未配置"语义。
 *
 * 有它，运行层就可以脱离 Admin 独立装配（不配任何 ChatModel Bean 也不会启动失败），
 * 诊断链路自动走规则诊断，报告里标注"尚未配置模型"。
 */
public final class NoopChatModelGateway implements ChatModelGateway {

    @Override
    public boolean configured() {
        return false;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public ChatClient chatClient() {
        throw new IllegalStateException("尚未配置模型");
    }

    @Override
    public String description() {
        return "未配置模型";
    }
}