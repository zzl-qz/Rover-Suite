package com.rover.agent.core.model;

/**
 * 会话中的一条消息：把一次提问与一次回答都留痕，用于构造多轮上下文。
 *
 * {@code relatedTaskId} 把消息与触发它的任务绑定，前端的会话时间线据此稳定排序
 * （用户消息 → 该任务的 Agent 回复 → 同一任务卡片），不再依赖毫秒级时间戳的偶然关系。
 *
 * @param messageId       消息 ID
 * @param sessionId       所属会话
 * @param role            发出方
 * @param content         消息正文
 * @param createdAtMillis 创建时刻
 * @param relatedTaskId   关联任务 ID；与任务无关的消息（如会话级提示）为 {@code null}
 */
public record AgentMessage(String messageId, String sessionId, MessageRole role, String content,
                           long createdAtMillis, String relatedTaskId) {

    /** 兼容旧签名：不带关联任务的消息。 */
    public AgentMessage(String messageId, String sessionId, MessageRole role, String content,
                        long createdAtMillis) {
        this(messageId, sessionId, role, content, createdAtMillis, null);
    }
}