package com.rover.agent.runtime.llm;

import com.rover.agent.runtime.metrics.AgentMetrics;
import com.rover.agent.runtime.metrics.ModelCallOutcome;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 调用已配置模型获取结构化输出；不可用、失败或空结果返回空。
 * 每次调用记录结局与含重试耗时，日志和指标不包含提示词或输出。
 * 按场景收紧超时，仅对超时重试一次。
 */
public final class SpringAiJsonCompletion implements JsonCompletion {

    private static final Logger log = LoggerFactory.getLogger(SpringAiJsonCompletion.class);

    private final ChatModelGateway gateway;
    private final AgentMetrics metrics;
    private final String scene;
    private final int timeoutSeconds;

    /**
     * @param timeoutSeconds 场景超时上限（秒）；0 表示用模型配置里的超时
     */
    public SpringAiJsonCompletion(ChatModelGateway gateway, AgentMetrics metrics, String scene, int timeoutSeconds) {
        this.gateway = gateway == null ? new NoopChatModelGateway() : gateway;
        this.metrics = metrics == null ? AgentMetrics.NOOP : metrics;
        this.scene = scene == null || scene.isBlank() ? "结构化输出" : scene.trim();
        this.timeoutSeconds = Math.max(0, timeoutSeconds);
    }

    @Override
    public <T> Optional<T> complete(String systemPrompt, String userPrompt, Function<String, Optional<T>> parser) {
        if (!gateway.configured()) {
            // 纯规则部署是常态，不是故障：这里只留 debug，控制台不刷无谓的警告。
            log.debug("Agent 未配置模型，{} 直接用规则结果", scene);
            metrics.modelCall(gateway.description(), scene, 0L, ModelCallOutcome.NOT_CONFIGURED);
            return Optional.empty();
        }
        if (!gateway.available()) {
            log.warn("Agent 模型已配置但客户端不可用，{} 退回规则结果", scene);
            metrics.modelCall(gateway.description(), scene, 0L, ModelCallOutcome.UNAVAILABLE);
            return Optional.empty();
        }
        long startedAt = System.nanoTime();
        ModelCallOutcome outcome = ModelCallOutcome.OK;
        try {
            String content = QuickModelCall.content(gateway, timeoutSeconds, scene,
                    client -> client.prompt().system(systemPrompt).user(userPrompt).call().content());
            if (content == null || content.isBlank()) {
                outcome = ModelCallOutcome.EMPTY;
                log.warn("Agent 模型返回空内容（{}），退回规则结果", scene);
                return Optional.empty();
            }
            Optional<T> parsed = parser.apply(content);
            if (parsed.isEmpty()) {
                // 有内容却转不成结果：这是契约与提示词的问题，不是链路问题，单独记一类。
                outcome = ModelCallOutcome.REJECTED;
                log.warn("Agent 模型输出不符合契约（{}），整条丢弃并退回规则结果", scene);
            }
            return parsed;
        } catch (RuntimeException ex) {
            outcome = QuickModelCall.classify(ex);
            if (outcome == ModelCallOutcome.TIMEOUT) {
                // 超时重试已由 QuickModelCall 完成，这里只留一条不带栈的告警，避免每次卡顿刷一屏堆栈。
                log.warn("Agent 模型调用超时（{}），退回规则结果：{}", scene, ex.getMessage());
            } else {
                log.warn("Agent 模型结构化调用失败（{}），退回规则结果", scene, ex);
            }
            return Optional.empty();
        } finally {
            metrics.modelCall(gateway.description(), scene,
                    Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L), outcome);
        }
    }
}