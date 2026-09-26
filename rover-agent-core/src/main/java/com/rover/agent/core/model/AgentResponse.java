package com.rover.agent.core.model;

/**
 * 一次用户提问的处理结果：会话连续性所需的记录 + 本轮任务（若有）。
 *
 * 两种形态互斥：
 * 要么 {@code task} 非空（已开始调查），要么 {@code clarification} 非空（目标没确定，需要用户补充）。
 * {@code reply} 是已写入会话的 Agent 回复消息，两种形态下都有，前端可以直接把它追加到对话里。
 *
 * @param session       本次提问所在的会话（已更新最近活动时间）
 * @param incident      本轮命中的事件；需要澄清时为 {@code null}
 * @param task          本轮调查任务视图；需要澄清时为 {@code null}
 * @param reply         写入会话的 Agent 回复消息
 * @param clarification 澄清提问；已开始调查时为 {@code null}
 */
public record AgentResponse(Session session, Incident incident, TaskView task, AgentMessage reply,
                            String clarification) {

    /** 是否因为无法确定调查对象而需要用户澄清。 */
    public boolean needsClarification() {
        return clarification != null && !clarification.isBlank();
    }
}