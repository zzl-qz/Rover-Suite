package com.rover.agent.runtime.llm;

import com.rover.agent.core.capability.UntrustedText;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.Hypothesis;
import com.rover.agent.runtime.metrics.AgentMetrics;
import com.rover.agent.runtime.metrics.ModelCallOutcome;
import com.rover.agent.runtime.task.AgentRunBudget;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * 模型仅选择已有证据；运行时验证编号后生成诊断正文，不展示未经验证的模型自由文本。
 * 模型接入由 ChatModelGateway 提供，失败时由调用方回退规则结论。
 */
public final class ModelExplainer {

    private static final int MAX_SELECTION_LENGTH = 2000;

    /** 指标里的场景名：与「目标解析 / 调查规划 / 对话」并列，便于按用途分开看模型表现。 */
    private static final String SCENE = "解读";

    /** 只选择证据，不授予模型编写数值、因果结论或执行回执的权限。 */
    private static final String SYSTEM_PROMPT = "你是 Rover 诊断证据选择器。仅从事实目录选择与问题有关的事实编号。"
            + "最多选择8项，只输出JSON：{\"factIds\":[\"F1\",\"F2\"]}。"
            + "不得输出目录以外的编号、结论、数值、动作或解释。假设状态由运行时验证，不能自行改判。"
            + UntrustedText.contract();

    private final ChatModelGateway gateway;
    private final AgentMetrics metrics;

    /** 不关心指标的构造入口（如宿主未提供注册表）：指标端口退化为空实现。 */
    public ModelExplainer(ChatModelGateway gateway) {
        this(gateway, AgentMetrics.NOOP);
    }

    public ModelExplainer(ChatModelGateway gateway, AgentMetrics metrics) {
        this.gateway = gateway == null ? new NoopChatModelGateway() : gateway;
        this.metrics = metrics == null ? AgentMetrics.NOOP : metrics;
    }

    /** 是否已配置模型（不代表当前可用）。 */
    public boolean configured() {
        return gateway.configured();
    }

    /** 当前模型是否真的可用。 */
    public boolean available() {
        return gateway.available();
    }

    /** 当前生效模型的脱敏描述，供控制台与日志展示。 */
    public String modelDescription() {
        return gateway.description();
    }

    /** 一次解读的结果：落库展示用的解释文本、运行时预读的快照名，以及模型额外调用过的只读工具名。 */
    public record Explanation(String text, List<String> prefetched, List<String> tools) { }

    /**
     * 选择完成且通过引用校验后才发布正文。保留旧回调签名，原始 JSON 和思考均不作为诊断发布。
     */
    public Explanation explainStreaming(String path, String question, List<Evidence> evidence,
                                       List<Hypothesis> hypotheses, Consumer<String> onDelta,
                                       Consumer<String> onThinking) {
        var report = new com.rover.agent.core.investigation.DiagnosticReport(evidence, hypotheses);
        String catalog = report.facts().stream().map(f -> f.id() + " [evidenceId=" + f.evidenceId() + "] " + f.text())
                .collect(java.util.stream.Collectors.joining("\n"));
        StringBuilder answer = new StringBuilder();
        boolean[] incomplete = {false};
        UsageTotals tokens = new UsageTotals();
        long startedAt = System.nanoTime();
        ModelCallOutcome outcome = ModelCallOutcome.OK;
        try {
            AgentBudgetAdvisor.client(gateway.chatClient(0),
                    AgentRunBudget.current(), gateway::reasoningDelta).prompt()
                    .system(SYSTEM_PROMPT)
                    .options(org.springframework.ai.chat.prompt.ChatOptions.builder().maxTokens(512))
                    .user("请求路径：" + path + "\n" + UntrustedText.block("用户问题", question)
                            + UntrustedText.block("事实目录", catalog))
                    .stream()
                    // 用 chatResponse 而不是 content：文本照旧逐段取，但顺带拿到用量与结束信息，
                    // content() 会把响应元数据整条丢掉，token 也就无从统计。
                    .chatResponse()
                    // JSON 和供应商思考均不作为用户可见结论推送；验证后只发布程序渲染的事实。
                    .doOnNext(response -> {
                        collect(response, answer, tokens);
                        Generation generation = response == null ? null : response.getResult();
                        if (generation != null && generation.getMetadata() != null
                                && "length".equalsIgnoreCase(generation.getMetadata().getFinishReason())) { incomplete[0] = true; }
                    })
                    .blockLast();
            if (answer.isEmpty()) {
                outcome = ModelCallOutcome.EMPTY;
                throw new IllegalStateException("模型没有返回解释");
            }
            if (incomplete[0] || answer.length() > MAX_SELECTION_LENGTH) {
                outcome = ModelCallOutcome.REJECTED;
                throw new IllegalArgumentException("模型诊断选择被截断或超过长度限制");
            }
            var selected = ModelJson.object(answer.toString());
            if (selected.isEmpty() || selected.get().size() != 1 || !selected.get().path("factIds").isArray()
                    || selected.get().path("factIds").isEmpty() && !report.facts().isEmpty()) {
                outcome = ModelCallOutcome.REJECTED;
                throw new IllegalArgumentException("模型诊断选择不符合事实引用契约");
            }
            List<String> ids = new java.util.ArrayList<>();
            for (var id : selected.get().path("factIds")) {
                if (!id.isTextual()) { outcome = ModelCallOutcome.REJECTED; throw new IllegalArgumentException("无效事实编号"); }
                ids.add(id.asText());
            }
            String rendered;
            try { rendered = report.render(ids); }
            catch (IllegalArgumentException invalid) { outcome = ModelCallOutcome.REJECTED; throw invalid; }
            onDelta.accept(rendered);
            return new Explanation(rendered, List.of("结构化证据目录"), List.of());
        } catch (RuntimeException ex) {
            if (outcome == ModelCallOutcome.OK) {
                outcome = QuickModelCall.classify(ex);
            }
            throw ex;
        } finally {
            // 只上报耗时、结局与用量，不记提示词与回答：指标里不出现业务内容，也不出现密钥。
            String model = gateway.description();
            metrics.modelCall(model, SCENE, Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L), outcome);
            tokens.reportTo(metrics, model, SCENE);
        }
    }

    /** 供应商输出只缓存在本地；超长时保留一个溢出标记并在完成后拒绝。 */
    private static void collect(ChatResponse response, StringBuilder answer, UsageTotals tokens) {
        if (response == null) { return; }
        Generation generation = response.getResult();
        AssistantMessage message = generation == null ? null : generation.getOutput();
        String chunk = message == null ? null : message.getText();
        if (chunk != null && answer.length() <= MAX_SELECTION_LENGTH) {
            int room = MAX_SELECTION_LENGTH + 1 - answer.length();
            answer.append(chunk, 0, Math.min(room, chunk.length()));
        }
        tokens.absorb(response);
    }

    /** 流式用量保留最后一个非空值，不累加；未提供用量时不补零。 */
    private static final class UsageTotals {

        private long promptTokens;
        private long completionTokens;
        private boolean seen;

        private void absorb(ChatResponse response) {
            ChatResponseMetadata metadata = response.getMetadata();
            Usage usage = metadata == null ? null : metadata.getUsage();
            if (usage == null) {
                return;
            }
            Integer prompt = usage.getPromptTokens();
            Integer completion = usage.getCompletionTokens();
            if (prompt == null && completion == null) {
                return;
            }
            seen = true;
            if (prompt != null) {
                promptTokens = prompt;
            }
            if (completion != null) {
                completionTokens = completion;
            }
        }

        private void reportTo(AgentMetrics metrics, String model, String scene) {
            if (seen) {
                metrics.modelTokens(model, scene, promptTokens, completionTokens);
            }
        }
    }

}
