package com.rover.agent.core.repository;

import com.rover.agent.core.model.AgentMessage;
import java.util.List;

/**
 * 会话消息存储契约：多轮上下文的原始素材。
 *
 * 上下文窗口（取最近多少条交给模型）由上下文管理器决定，本接口只负责按写入顺序保存与读取。
 * 当前由内存实现承载，仅保留短期上下文；接入持久化后用于长期回看。
 */
public interface AgentMessageRepository {

    /** 保存一条消息。 */
    void save(AgentMessage message);

    /** 会话内的全部消息，按写入顺序（最早在前）。 */
    List<AgentMessage> bySession(String sessionId);

    /** 删除会话内全部消息，用于会话回滚。 */
    void removeBySession(String sessionId);
}