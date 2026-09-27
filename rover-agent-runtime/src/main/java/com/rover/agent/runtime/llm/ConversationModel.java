package com.rover.agent.runtime.llm;

import com.rover.agent.runtime.tool.OpsTools;
import java.util.function.Consumer;

/**
 * 一次对话的模型侧执行：把提示词与工具交给模型，由它自己决定查什么、怎么答。
 *
 * <p>为什么单独抽一层：模型「会不会自己拆子问题、会不会选对工具」是**模型能力**，
 * 无法在单测里断言——拿一个脚本化的假模型去断言它拆得好，只是在测自己的脚本。
 * 真正必须被覆盖的是<b>编排职责</b>：工具执行是否落到真实取数、证据有没有随结论落库、
 * 预算耗尽后有没有收尾、失败有没有收敛成可读结论。这些只有在模型侧可替换时才测得到，
 * 因此把「调用模型」与「编排」分开，前者可换、后者可测。
 *
 * <p>模型自主拆解与选工具的效果由真实模型验证（手工场景 + 端到端），不靠单测假装保证。
 */
@FunctionalInterface
public interface ConversationModel {

    /**
     * 执行一次对话。
     *
     * @param systemPrompt 系统提示词（工具使用纪律与事实纪律）
     * @param userMessage  用户消息（含时间、会话背景与提问）
     * @param tools        可执行工具；模型自行决定调哪些、调几次
     * @param onDelta      回答的文本增量回调，用于流式展示
     * @param onThinking   思考内容的增量回调；模型不产出思考时不会被调用
     * @return 完整的回答文本；模型没有产出内容时返回空串（由调用方判定为失败）
     */
    String converse(String systemPrompt, String userMessage, OpsTools tools,
                    Consumer<String> onDelta, Consumer<String> onThinking);
}
