package com.rover.agent.runtime.llm;

import com.rover.agent.runtime.metrics.AgentMetrics;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于已配置模型的 JSON 输出实现：唯一的职责是「把提示词发出去、把文本拿回来」。
 *
 * 不在这里做 JSON 解析与业务校验（那是 {@link ModelJson} 与各决策类的职责），也不在提示词里
 * 放入管理口地址、凭证或任意受控事实。模型未配置或调用失败一律返回空，调用方随之走确定性兜底。
 * 只上报一次模型调用指标（场景标签区分用途），不记录提示词与输出内容。
 *
 * {@code timeoutSeconds > 0} 时按场景收紧超时，且只在超时后重试一次（见 {@link QuickModelCall}）；
 * 指标口径不变：一次调用一条记录，耗时为含重试的总耗时。
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
    public Optional<String> complete(String systemPrompt, String userPrompt) {
        if (!gateway.configured() || !gateway.available()) {
            return Optional.empty();
        }
        long startedAt = System.nanoTime();
        boolean failed = true;
        try {
            String content = QuickModelCall.content(gateway, timeoutSeconds, scene,
                    client -> client.prompt().system(systemPrompt).user(userPrompt).call().content());
            if (content == null || content.isBlank()) {
                return Optional.empty();
            }
            failed = false;
            return Optional.of(content);
        } catch (Exception ex) {
            log.warn("Agent 模型结构化调用失败（{}）", scene, ex);
            return Optional.empty();
        } finally {
            metrics.modelCall(gateway.description() + "/" + scene,
                    Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L), failed);
        }
    }
}