package com.rover.agent.runtime.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

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

    /**
     * 取一个"等待上限不超过 {@code timeoutSeconds} 秒"的客户端，供意图识别这类廉价调用收紧超时。
     *
     * {@code timeoutSeconds <= 0} 表示不限制，等价于 {@link #chatClient()}；只收紧不放宽：
     * 请求值不小于配置超时时直接返回当前客户端。未配置或构建失败时抛 IllegalStateException。
     *
     * <p>这条路径上的调用都是「失败也能确定性兜底」的小调用，实现方应当同样<b>不开深度思考</b>：
     * 让模型先想一遍既拖慢等待，也更容易撞上超时后回退规则，反而丢掉模型本该贡献的判断。
     * 需要推理质量的长调用（如「AI 解读」）走 {@link #chatClient()}。
     */
    ChatClient chatClient(int timeoutSeconds);

    /** 供控制台展示的脱敏描述，如 "gpt-5-mini @ api.openai.com"。 */
    String description();

    /**
     * 取一帧流式响应里的「思考增量」；当前模型不产出思考内容时返回空串。
     *
     * 深度思考是厂商特有的位置：只有原生协议的响应里才有这个字段，OpenAI 兼容协议没有它的容身之处——
     * 差异收在接入层，运行层只问「这一帧有没有思考内容」，因此换厂商、换模型都不会波及诊断逻辑。
     *
     * 实现必须永不抛异常、也永不返回 {@code null}：思考内容只是解释过程的陪衬，
     * 取不到不该让一次解读失败。
     */
    String reasoningDelta(ChatResponse response);
}