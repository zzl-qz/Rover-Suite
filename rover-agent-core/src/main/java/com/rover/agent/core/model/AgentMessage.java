package com.rover.agent.core.model;

/**
 * 会话中的一条消息：把一次提问与一次回答都留痕，用于构造多轮上下文。
 *
 * @param messageId       消息 ID
 * @param sessionId       所属会话
 * @param role            发出方
 * @param content         消息正文
 * @param createdAtMillis 创建时刻
 */
public record AgentMessage(String messageId, String sessionId, MessageRole role, String content,
                           long createdAtMillis) { }