package com.rover.agent.core.model;

/**
 * 意图识别结果：对「用户想干什么」的一次结构化判断，不包含「对谁做」——那是目标解析的职责。
 *
 * {@code targetHint} 只是从问题文本里抽出的对象线索（如 {@code order-03}），供目标解析与处置预检
 * 作为输入；它不是解析结果，最终目标仍由 TargetResolver 依据真实注册数据确定。
 *
 * {@code confidence} 为 {@link Confidence#LOW} 且 {@code needsClarification} 为 true 时，
 * 编排层必须向用户澄清，不允许猜一个意图硬执行。
 *
 * @param intent             识别出的意图
 * @param confidence         判断置信度
 * @param topic              意图细分主题（如能力咨询 vs 上下文解释）
 * @param targetHint         文本中抽出的对象线索；没有时为空串
 * @param timeRange          本次请求的时间范围；用户未指定时为未指定
 * @param requestedAction    处置请求对应的动作类型；非处置请求为 {@link ActionType#UNKNOWN}
 * @param reason             判断依据（展示与审计用）
 * @param needsClarification 是否需要用户补充信息后才能继续
 * @param clarification      澄清提问；不需要澄清时为 {@code null}
 */
public record IntentDecision(AgentIntent intent, Confidence confidence, IntentTopic topic, String targetHint,
                             TimeRange timeRange, ActionType requestedAction, String reason,
                             boolean needsClarification, String clarification) {

    public IntentDecision {
        intent = intent == null ? AgentIntent.UNKNOWN : intent;
        confidence = confidence == null ? Confidence.LOW : confidence;
        topic = topic == null ? IntentTopic.NONE : topic;
        targetHint = targetHint == null ? "" : targetHint.trim();
        timeRange = timeRange == null ? TimeRange.unspecified() : timeRange;
        requestedAction = requestedAction == null ? ActionType.UNKNOWN : requestedAction;
        reason = reason == null ? "" : reason.trim();
        clarification = clarification == null || clarification.isBlank() ? null : clarification.trim();
        needsClarification = needsClarification && clarification != null;
    }

    /** 一般意图判断。 */
    public static IntentDecision of(AgentIntent intent, Confidence confidence, String reason) {
        return new IntentDecision(intent, confidence, IntentTopic.NONE, "", TimeRange.unspecified(),
                ActionType.UNKNOWN, reason, false, null);
    }

    /** 系统能力咨询。 */
    public static IntentDecision capabilities(String reason) {
        return new IntentDecision(AgentIntent.EXPLAIN, Confidence.HIGH, IntentTopic.CAPABILITIES, "",
                TimeRange.unspecified(), ActionType.UNKNOWN, reason, false, null);
    }

    /** 处置请求。 */
    public static IntentDecision action(ActionType action, String targetHint, Confidence confidence, String reason) {
        return new IntentDecision(AgentIntent.ACTION_REQUEST, confidence, IntentTopic.NONE, targetHint,
                TimeRange.unspecified(), action, reason, false, null);
    }

    /** 需要澄清的判断：置信度低且必须由用户补充信息。 */
    public static IntentDecision clarify(AgentIntent intent, String reason, String clarification) {
        return new IntentDecision(intent, Confidence.LOW, IntentTopic.NONE, "", TimeRange.unspecified(),
                ActionType.UNKNOWN, reason, true, clarification);
    }

    /** 补上调用方显式给出的时间范围。 */
    public IntentDecision withTimeRange(TimeRange range) {
        return new IntentDecision(intent, confidence, topic, targetHint, range, requestedAction, reason,
                needsClarification, clarification);
    }

    /** 依据后续事实修正意图判断（如：文本识别不出意图，但目标可解析，按故障调查处理）。 */
    public IntentDecision as(AgentIntent refined, Confidence refinedConfidence, String refinedReason) {
        return new IntentDecision(refined, refinedConfidence, IntentTopic.NONE, targetHint, timeRange,
                ActionType.UNKNOWN, refinedReason, false, null);
    }
}