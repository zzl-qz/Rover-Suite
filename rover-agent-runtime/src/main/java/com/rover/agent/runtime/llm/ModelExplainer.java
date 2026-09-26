package com.rover.agent.runtime.llm;

import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.Hypothesis;
import com.rover.agent.core.model.Verdict;
import com.rover.agent.runtime.metrics.AgentMetrics;
import com.rover.agent.runtime.tool.SnapshotTools;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 模型解读：在规则诊断之上补充解释，并守住「未读证据不采信」的边界。
 *
 * 路由与实例这两份最小依据由运行时预读后写进提示词，模型不必依赖自觉调用工具；
 * 其余证据（指标、追踪）仍只经由只读工具按需读取。解释失败（未配置模型、模型报错、
 * 未返回内容）不会影响调查结论，由调用方降级为规则诊断并明确标注。
 * 模型来源全部由 {@link ChatModelGateway} 决定，本类不关心是哪个服务商、密钥从哪来。
 */
public final class ModelExplainer {

    private static final int MAX_ANALYSIS_LENGTH = 2000;

    private static final String SYSTEM_PROMPT = "你是 Rover 运维诊断助手。只根据提示词里给出的只读快照、只读工具返回的快照"
            + "和已完成的假设验证解释问题。路由与实例快照已由运行时读好，需要指标或追踪证据时再调用对应的只读工具。"
            + "区分配置事实、推断和缺失证据。不要声称已经执行修复，也不要输出执行命令。"
            + "用简洁中文给出原因和下一步人工检查建议。";

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
     * 基于本次调查的只读快照与假设验证结果生成解释，并把增量边产生边交给 {@code onDelta}
     * （供 SSE 实时展示）；返回的是截断后的完整文本，用于落库展示。
     *
     * 路由与实例快照由运行时预读后放进提示词，模型是否自觉调用工具都不影响解读有据可依；
     * 模型未返回内容时才抛异常，由调用方降级。推送与返回文本都受同一长度上限约束，
     * 因此前端看到的增量拼接结果与最终文本一致。预读与模型另调的工具各自如实记录，供调用方展示。
     */
    public Explanation explainStreaming(String path, String question, List<Evidence> evidence,
                                       List<Hypothesis> hypotheses, Consumer<String> onDelta) {
        SnapshotTools tools = new SnapshotTools(evidence);
        Map<String, String> core = tools.coreSnapshots();
        StringBuilder answer = new StringBuilder();
        long startedAt = System.nanoTime();
        boolean failed = true;
        try {
            gateway.chatClient().prompt()
                    .system(SYSTEM_PROMPT)
                    .user("请求路径：" + path + "\n用户问题：" + question + describeHypotheses(hypotheses)
                            + describeSnapshots(core))
                    .tools(tools)
                    .stream()
                    .content()
                    .doOnNext(chunk -> collect(answer, chunk, onDelta))
                    .blockLast();
            if (answer.isEmpty()) {
                throw new IllegalStateException("模型没有返回解释");
            }
            failed = false;
            return new Explanation(answer.toString(), List.copyOf(core.keySet()), tools.calledTools());
        } finally {
            // 只上报耗时与成败，不记提示词与回答：指标里不出现业务内容，也不出现密钥。
            metrics.modelCall(gateway.description(), Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L),
                    failed);
        }
    }

    /** 累积一段增量并转发；超出上限的部分直接丢弃，保证推送内容与最终文本逐字一致。 */
    private static void collect(StringBuilder answer, String chunk, Consumer<String> onDelta) {
        if (chunk == null || chunk.isEmpty() || answer.length() >= MAX_ANALYSIS_LENGTH) {
            return;
        }
        int room = MAX_ANALYSIS_LENGTH - answer.length();
        String piece = chunk.length() > room ? chunk.substring(0, room) : chunk;
        answer.append(piece);
        onDelta.accept(piece);
    }

    /** 提示词里预读的核心快照：与只读工具返回的文本同源，保证解释有据可依且不依赖模型是否自觉调用。 */
    private static String describeSnapshots(Map<String, String> core) {
        StringBuilder builder = new StringBuilder("\n运行时已读取的只读快照：");
        core.forEach((name, text) -> builder.append("\n- ").append(name).append("：").append(text));
        return builder.toString();
    }

    private static String describeHypotheses(List<Hypothesis> hypotheses) {
        StringBuilder builder = new StringBuilder();
        for (Hypothesis item : hypotheses) {
            builder.append("\n- [").append(statusLabel(item.status())).append("] ").append(item.statement())
                    .append("：").append(item.detail());
        }
        return builder.isEmpty() ? "" : "\n已完成的假设验证：" + builder;
    }

    private static String statusLabel(Verdict status) {
        if (status == Verdict.CONFIRMED) {
            return "确认";
        }
        return status == Verdict.REJECTED ? "排除" : "无法验证";
    }
}