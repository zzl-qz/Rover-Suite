package com.rover.agent.core.model;

/**
 * 意图识别结果：对「用户想干什么」的一次结构化判断，不含「对谁做」。
 *
 * <p>{@code targetHint} 只是从文本抽出的对象线索，不是解析结果；最终目标由 TargetResolver 依据真实数据确定。
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