package com.rover.agent.core.intent;

import com.rover.agent.core.model.IntentDecision;
import java.util.Optional;

/**
 * 意图解释器端口：把「模型辅助的意图理解」挡在领域层之外。
 *
 * 领域层只依赖这个函数口，不依赖 Spring AI、也不依赖任何 JSON 库；运行层用已配置的模型实现它，
 * 无模型时用 {@link #none()}。返回 {@link Optional#empty()} 表示「模型没有给出可用判断」，
 * 调用方随之退回规则分类——意图判断因此永远有一个确定性兜底，不会因模型不可用而中断提问。
 */
public interface IntentInterpreter {

    /**
     * 对用户问题做一次结构化意图判断。
     *
     * @param question       用户问题原文
     * @param contextSummary 会话上下文摘要（最近对话与当前事件），供理解「那现在呢」这类追问
     * @return 模型判断；无法判断或未配置模型时返回空
     */
    Optional<IntentDecision> interpret(String question, String contextSummary);

    /** 未接入模型时的空实现。 */
    static IntentInterpreter none() {
        return (question, contextSummary) -> Optional.empty();
    }
}