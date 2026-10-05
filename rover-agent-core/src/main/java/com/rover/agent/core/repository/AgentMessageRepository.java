package com.rover.agent.core.repository;

import com.rover.agent.core.model.AgentMessage;
import java.util.List;
import java.util.Optional;

/** 按写入顺序保存和读取会话消息；上下文窗口由上下文管理器控制。 */
public interface AgentMessageRepository {

    /** 保存一条消息。 */
    void save(AgentMessage message);

    /** 会话内的全部消息，按写入顺序（最早在前）。 */
    List<AgentMessage> bySession(String sessionId);

    /** 全部消息，按写入顺序（最早在前）；保留策略据此挑出最早的对话。 */
    List<AgentMessage> listAll();

    /** 写入顺序上的最早一条消息；空存储返回空。 */
    Optional<AgentMessage> oldest();

    /** 删除一条消息。 */
    void remove(String messageId);

    /** 删除会话内全部消息，返回删除条数，用于会话回滚与级联清理。 */
    int removeBySession(String sessionId);
}