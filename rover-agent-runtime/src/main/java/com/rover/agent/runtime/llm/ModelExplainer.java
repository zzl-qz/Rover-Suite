package com.rover.agent.runtime.llm;

import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.Hypothesis;
import com.rover.agent.core.model.Verdict;
import com.rover.agent.runtime.tool.SnapshotTools;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 模型解读：在规则诊断之上补充解释，并守住「未读证据不采信」的边界。
 *
 * 解释失败（未配置模型、模型报错、未读取必要证据）不会影响调查结论，
 * 由调用方降级为规则诊断并明确标注。
 */
public final class ModelExplainer {

    private static final int MAX_ANALYSIS_LENGTH = 2000;

    private final ObjectProvider<ChatModel> chatModels;
    private final ObjectProvider<ChatClient.Builder> chatBuilders;

    public ModelExplainer(ObjectProvider<ChatModel> chatModels, ObjectProvider<ChatClient.Builder> chatBuilders) {
        this.chatModels = chatModels;
        this.chatBuilders = chatBuilders;
    }

    /** 是否已配置可用的 ChatModel。 */
    public boolean available() {
        return chatModels.getIfAvailable() != null;
    }

    /**
     * 基于本次调查的只读快照与假设验证结果生成解释。
     * 模型未读取必要证据、未返回内容时抛异常，由调用方降级。
     */
    public String explain(String path, String question, List<Evidence> evidence, List<Hypothesis> hypotheses) {
        SnapshotTools tools = new SnapshotTools(evidence);
        String answer = chatBuilders.getObject().build().prompt()
                .system("你是 Rover 运维诊断助手。只根据只读工具返回的快照和已完成的假设验证解释问题。"
                        + "必须先调用路由和实例工具，再根据需要调用指标与追踪工具。"
                        + "区分配置事实、推断和缺失证据。不要声称已经执行修复，也不要输出执行命令。"
                        + "用简洁中文给出原因和下一步人工检查建议。")
                .user("请求路径：" + path + "\n用户问题：" + question + describeHypotheses(hypotheses))
                .tools(tools)
                .call().content();
        if (!tools.hasReadRoute() || !tools.hasReadInstances()) {
            throw new IllegalStateException("模型未读取必要证据");
        }
        if (answer == null || answer.isBlank()) {
            throw new IllegalStateException("模型没有返回解释");
        }
        return answer.substring(0, Math.min(answer.length(), MAX_ANALYSIS_LENGTH));
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