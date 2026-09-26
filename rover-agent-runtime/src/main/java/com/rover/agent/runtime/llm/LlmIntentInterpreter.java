package com.rover.agent.runtime.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.rover.agent.core.intent.IntentInterpreter;
import com.rover.agent.core.model.ActionType;
import com.rover.agent.core.model.AgentIntent;
import com.rover.agent.core.model.Confidence;
import com.rover.agent.core.model.IntentDecision;
import com.rover.agent.core.model.IntentTopic;
import java.util.Optional;

/**
 * 模型辅助的意图解释器：把「用户想干什么」的判断交给模型，但只接受结构化的有限取值。
 *
 * 提示词里给出可选意图、可选主题与可选动作的完整枚举，模型输出用 {@link ModelJson} 解析；
 * 任何越界取值（编造的意图名、动作名）都会被丢弃并返回空，由 {@link com.rover.agent.core.intent.IntentService}
 * 回退到规则分类。目标线索与时间范围不在这里判断，它们由规则从文本直接读出来后再合并——
 * 意图与目标严格分离。
 */
public final class LlmIntentInterpreter implements IntentInterpreter {

    private static final String SYSTEM_PROMPT = "你是 Rover 运维 Agent 的意图识别模块。"
            + "只判断用户想做什么，不判断对象是否存在、也不猜测资源名称。"
            + "只输出一个 JSON 对象，不要输出解释文字，也不要用代码块包裹。";

    private static final int MAX_QUESTION_LENGTH = 1000;
    private static final int MAX_CONTEXT_LENGTH = 600;

    private final JsonCompletion completion;

    public LlmIntentInterpreter(JsonCompletion completion) {
        this.completion = completion;
    }

    @Override
    public Optional<IntentDecision> interpret(String question, String contextSummary) {
        String asked = question == null ? "" : question.trim();
        if (asked.isEmpty()) {
            return Optional.empty();
        }
        if (asked.length() > MAX_QUESTION_LENGTH) {
            asked = asked.substring(0, MAX_QUESTION_LENGTH);
        }
        String context = contextSummary == null ? "" : contextSummary.trim();
        if (context.length() > MAX_CONTEXT_LENGTH) {
            context = context.substring(0, MAX_CONTEXT_LENGTH);
        }
        return completion.complete(SYSTEM_PROMPT, userPrompt(asked, context)).flatMap(LlmIntentInterpreter::toDecision);
    }

    private static String userPrompt(String question, String context) {
        StringBuilder prompt = new StringBuilder("用户消息：").append(question);
        if (!context.isBlank()) {
            prompt.append("\n会话上下文（仅用于理解「它/那个」等指代，不要据此改变用户要问的对象）：")
                    .append(context);
        }
        prompt.append("\n可选意图：");
        for (AgentIntent intent : AgentIntent.values()) {
            prompt.append("\n- ").append(intent.name()).append("：").append(describe(intent));
        }
        prompt.append("\n可选主题：");
        for (IntentTopic topic : IntentTopic.values()) {
            prompt.append(topic.name()).append(" / ");
        }
        prompt.append("\n处置动作（仅当意图为 ACTION_REQUEST 时有意义）：");
        for (ActionType action : ActionType.values()) {
            prompt.append(action.name()).append(" / ");
        }
        prompt.append("\n置信度只能取 HIGH / MEDIUM / LOW。")
                .append("\n输出 JSON：{\"intent\":\"...\",\"confidence\":\"...\",\"topic\":\"...\",")
                .append("\"action\":\"...\",\"reason\":\"一句话依据\"}");
        return prompt.toString();
    }

    private static String describe(AgentIntent intent) {
        return switch (intent) {
            case QUERY_STATE -> "问当前事实（实例数、QPS、路由指向），不需要故障调查";
            case INVESTIGATE -> "问失败或异常的原因，需要只读调查";
            case EXPLAIN -> "要求解释、总结已有信息或说明系统能力";
            case ACTION_REQUEST -> "要求对某个对象执行操作（摘除、恢复、改配置等）";
            case CREATE_INSPECTION -> "要求创建定时或周期性的巡检任务";
            case KNOWLEDGE_QUERY -> "询问文档、使用方法等知识内容";
            case UNKNOWN -> "无法判断";
        };
    }

    private static Optional<IntentDecision> toDecision(String raw) {
        return ModelJson.object(raw).flatMap(node -> {
            AgentIntent intent = intent(ModelJson.text(node, "intent"));
            if (intent == null) {
                return Optional.empty();
            }
            Confidence confidence = confidence(ModelJson.text(node, "confidence"));
            IntentTopic topic = topic(ModelJson.text(node, "topic"));
            ActionType action = action(ModelJson.text(node, "action"));
            String reason = ModelJson.text(node, "reason");
            return Optional.of(new IntentDecision(intent, confidence, topic, "", null, action,
                    reason.isBlank() ? "模型意图判断" : reason, false, null));
        });
    }

    private static AgentIntent intent(String name) {
        try {
            return name.isBlank() ? null : AgentIntent.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Confidence confidence(String name) {
        try {
            return name.isBlank() ? Confidence.MEDIUM : Confidence.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return Confidence.MEDIUM;
        }
    }

    private static IntentTopic topic(String name) {
        try {
            return name.isBlank() ? IntentTopic.NONE : IntentTopic.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return IntentTopic.NONE;
        }
    }

    private static ActionType action(String name) {
        try {
            return name.isBlank() ? ActionType.UNKNOWN : ActionType.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ActionType.UNKNOWN;
        }
    }
}