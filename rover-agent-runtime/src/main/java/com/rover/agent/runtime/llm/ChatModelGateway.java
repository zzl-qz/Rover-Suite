package com.rover.agent.runtime.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

/** 模型接入端口，由宿主进程实现；available=true 时必须已 configured。 */
public interface ChatModelGateway {
    enum Purpose { CONVERSATION, LIGHTWEIGHT, PLANNING }

    /** 旧宿主身份未知时明确记录 UNKNOWN，不能从展示文案猜真实模型。 */
    default ModelClient select(Purpose purpose,int timeoutSeconds,boolean thinkingEnabled) {
        ChatClient selected=switch(purpose) {
            case CONVERSATION -> chatClient();
            case LIGHTWEIGHT -> chatClient(timeoutSeconds);
            case PLANNING -> planningClient(timeoutSeconds,thinkingEnabled);
        };
        return new ModelClient(selected,com.rover.agent.core.model.ModelIdentity.unknown(),null,null);
    }

    /** 是否"配置了模型"（与能否构建成功无关）。未配置 → false。 */
    boolean configured();

    /** 当前是否真的可用：configured 且客户端已成功构建。未配置必须为 false。 */
    boolean available();

    /** 当前生效的 ChatClient；未配置或构建失败时抛 IllegalStateException。 */
    ChatClient chatClient();

    /**
     * 获取按场景收紧超时且禁用思考的客户端；timeoutSeconds<=0 时使用默认超时。
     * 上限不会放宽配置超时；无论上限是否相等，场景客户端均保持禁用思考。
     */
    ChatClient chatClient(int timeoutSeconds);

    /**
     * 调查规划使用主模型，拥有独立超时与思考设置；不能因配置了快速模型而自动降档。
     * 宿主仍须将超时限制在模型配置内。不支持思考控制的宿主必须显式拒绝启用。
     */
    default ChatClient planningClient(int timeoutSeconds, boolean thinkingEnabled) {
        throw new UnsupportedOperationException("宿主未实现独立的调查规划客户端");
    }

    /** 供控制台展示的脱敏描述，如 "gpt-5-mini @ api.openai.com"。 */
    String description();

    /** 读取流式响应的思考增量；缺失时返回空串，不抛异常或返回 null。 */
    String reasoningDelta(ChatResponse response);
}
