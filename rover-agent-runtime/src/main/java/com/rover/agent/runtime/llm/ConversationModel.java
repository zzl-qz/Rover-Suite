package com.rover.agent.runtime.llm;

import com.rover.agent.runtime.tool.OpsTools;
import java.util.function.Consumer;

/** 对话模型执行端口，接收提示词与工具，由模型选择调用并生成回答。 */
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
