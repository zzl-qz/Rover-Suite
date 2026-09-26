package com.rover.agent.runtime.llm;

import org.springframework.ai.chat.client.ChatClient;

/**
 * 模型接入端口：运行层只认"是否配置、是否可用、拿客户端"，不关心模型来自哪个服务商。
 *
 * 实现由宿主进程提供（如 Admin 的 OpenAI 兼容适配器），因此运行层不依赖任何具体模型 SDK，
 * 也可以脱离 Web 进程单独复用。实现必须保证"未配置"时不谎报可用：
 * {@link #available()} 为 true 的前提是 {@link #configured()} 也为 true。
 */
public interface ChatModelGateway {

    /** 是否"配置了模型"（与能否构建成功无关）。未配置 → false。 */
    boolean configured();

    /** 当前是否真的可用：configured 且客户端已成功构建。未配置必须为 false。 */
    boolean available();

    /** 当前生效的 ChatClient；未配置或构建失败时抛 IllegalStateException。 */
    ChatClient chatClient();

    /** 供控制台展示的脱敏描述，如 "gpt-5-mini @ api.openai.com"。 */
    String description();
}