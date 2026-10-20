package com.rover.agent.runtime.llm;

import com.rover.agent.runtime.metrics.AgentMetrics;
import com.rover.agent.runtime.metrics.ModelCallOutcome;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import com.rover.agent.runtime.task.AgentRunBudget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 调用已配置模型获取结构化输出；不可用、失败或空结果返回空。
 * 每次调用记录结局与含重试耗时，日志和指标不包含提示词或输出。
 * 轻量调用超时可重试一次；独立调查规划使用主模型且不重做整轮推理。
 */
public final class SpringAiJsonCompletion implements JsonCompletion {

    private static final Logger log = LoggerFactory.getLogger(SpringAiJsonCompletion.class);

    private final ChatModelGateway gateway;
    private final AgentMetrics metrics;
    private final String scene;
    private final int timeoutSeconds;
    private final Integer maxOutputTokens;
    private final boolean planning;
    private final boolean thinkingEnabled;

    /**
     * @param timeoutSeconds 场景超时上限（秒）；0 表示用模型配置里的超时
     */
    public SpringAiJsonCompletion(ChatModelGateway gateway, AgentMetrics metrics, String scene, int timeoutSeconds) {
        this(gateway, metrics, scene, timeoutSeconds, null);
    }

    public SpringAiJsonCompletion(ChatModelGateway gateway, AgentMetrics metrics, String scene, int timeoutSeconds,
                                   Integer maxOutputTokens) {
        this(gateway, metrics, scene, timeoutSeconds, maxOutputTokens, false, false);
    }

    /** 较长的调查规划只调用一次主模型；超时不重新从头购买同一轮推理。 */
    public static SpringAiJsonCompletion investigation(ChatModelGateway gateway, AgentMetrics metrics, int timeoutSeconds,
                                                       int maxOutputTokens, boolean thinkingEnabled) {
        if (timeoutSeconds <= 0 || maxOutputTokens <= 0) { throw new IllegalArgumentException("规划时间和输出预算必须为正数"); }
        return new SpringAiJsonCompletion(gateway, metrics, "调查规划", timeoutSeconds, maxOutputTokens, true, thinkingEnabled);
    }

    private SpringAiJsonCompletion(ChatModelGateway gateway, AgentMetrics metrics, String scene, int timeoutSeconds,
                                   Integer maxOutputTokens, boolean planning, boolean thinkingEnabled) {
        this.gateway = gateway == null ? new NoopChatModelGateway() : gateway;
        this.metrics = metrics == null ? AgentMetrics.NOOP : metrics;
        this.scene = scene == null || scene.isBlank() ? "结构化输出" : scene.trim();
        this.timeoutSeconds = Math.max(0, timeoutSeconds);
        this.maxOutputTokens = maxOutputTokens;
        this.planning = planning;
        this.thinkingEnabled = thinkingEnabled;
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
            Function<ChatClient, String> call = client -> {
                        var request = client.prompt().system(systemPrompt).user(userPrompt);
                        if (maxOutputTokens != null) {
                            request.options(org.springframework.ai.chat.prompt.ChatOptions.builder().maxTokens(maxOutputTokens));
                        }
                        return content(request.call().chatResponse());
                    };
            String content = planning
                    ? call.apply(AgentBudgetAdvisor.client(gateway.planningClient(timeoutSeconds, thinkingEnabled),
                            AgentRunBudget.current(), gateway::reasoningDelta))
                    : QuickModelCall.content(gateway, timeoutSeconds, scene, call);
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
        } catch (TruncatedOutput ex) {
            outcome = ModelCallOutcome.REJECTED;
            log.warn("Agent 模型输出被供应商截断（{}），不采纳不完整计划", scene);
            return Optional.empty();
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

    private static String content(ChatResponse response) {
        var result = response == null ? null : response.getResult();
        if (result != null && result.getMetadata() != null
                && "length".equalsIgnoreCase(result.getMetadata().getFinishReason())) { throw new TruncatedOutput(); }
        return result == null || result.getOutput() == null ? null : result.getOutput().getText();
    }

    private static final class TruncatedOutput extends RuntimeException { }
}
