package com.rover.agent.runtime.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

/** 模型接入端口，由宿主进程实现；available=true 时必须已 configured。 */
public interface ChatModelGateway {

    /** 是否"配置了模型"（与能否构建成功无关）。未配置 → false。 */
    boolean configured();

    /** 当前是否真的可用：configured 且客户端已成功构建。未配置必须为 false。 */
    boolean available();

    /** 当前生效的 ChatClient；未配置或构建失败时抛 IllegalStateException。 */
    ChatClient chatClient();

    /**
     * 获取按场景收紧超时且禁用思考的客户端；timeoutSeconds<=0 时使用默认超时。
     * 请求上限不小于配置值时复用客户端，未配置或构建失败时抛 IllegalStateException。
     */
    ChatClient chatClient(int timeoutSeconds);

    /** 供控制台展示的脱敏描述，如 "gpt-5-mini @ api.openai.com"。 */
    String description();

    /** 读取流式响应的思考增量；缺失时返回空串，不抛异常或返回 null。 */
    String reasoningDelta(ChatResponse response);
}