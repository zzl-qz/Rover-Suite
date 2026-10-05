package com.rover.agent.runtime.repository;

import com.rover.agent.core.repository.AgentActionRepository;
import com.rover.agent.core.repository.AgentCheckpointRepository;
import com.rover.agent.core.repository.AgentMessageRepository;
import com.rover.agent.core.repository.AgentSessionRepository;
import com.rover.agent.core.repository.AgentTaskRepository;
import com.rover.agent.core.repository.IncidentRepository;
import com.rover.agent.runtime.journal.OpsJournal;

/**
 * 装配 Agent 存储；配置记录库路径时使用 JDBC，否则使用内存实现。
 * 容量限制仅作用于内存实现。
 */
public final class AgentStore implements AutoCloseable {

    private final AgentSessionRepository sessions;
    private final AgentMessageRepository messages;
    private final IncidentRepository incidents;
    private final AgentTaskRepository tasks;
    private final AgentCheckpointRepository checkpoints;
    private final AgentActionRepository actions;
    private final OpsJournal journal;
    private final AutoCloseable resource;

    private AgentStore(AgentSessionRepository sessions, AgentMessageRepository messages,
                       IncidentRepository incidents, AgentTaskRepository tasks,
                       AgentCheckpointRepository checkpoints, AgentActionRepository actions,
                       OpsJournal journal, AutoCloseable resource) {
        this.sessions = sessions;
        this.messages = messages;
        this.incidents = incidents;
        this.tasks = tasks;
        this.checkpoints = checkpoints;
        this.actions = actions;
        this.journal = journal;
        this.resource = resource;
    }

    /** 进程内实现：容量满会拒绝写入，笔记不记。 */
    public static AgentStore memory(int sessionCapacity, int incidentCapacity, int messageCapacity,
                                    int taskCapacity, int actionCapacity) {
        return new AgentStore(new InMemoryAgentSessionRepository(sessionCapacity),
                new InMemoryAgentMessageRepository(messageCapacity),
                new InMemoryIncidentRepository(incidentCapacity),
                new InMemoryAgentTaskRepository(taskCapacity), new InMemoryAgentCheckpointRepository(),
                new InMemoryAgentActionRepository(actionCapacity), OpsJournal.none(), () -> { });
    }

    /** 落库实现：会话、事件、任务、步骤、证据、安全恢复点、变更记录与资源笔记都写进同一个库文件。 */
    public static AgentStore file(String path) {
        JdbcAgentStore store = new JdbcAgentStore(path);
        return new AgentStore(store.sessions(), store.messages(), store.incidents(), store.tasks(),
                store.checkpoints(), store.actions(), OpsJournal.of(store), store);
    }

    public AgentSessionRepository sessions() {
        return sessions;
    }

    public AgentMessageRepository messages() {
        return messages;
    }

    public IncidentRepository incidents() {
        return incidents;
    }

    public AgentTaskRepository tasks() {
        return tasks;
    }

    public AgentCheckpointRepository checkpoints() {
        return checkpoints;
    }

    /** 变更记录：Agent 提议、人批准、执行器落地的那一条链，重启后仍可回查。 */
    public AgentActionRepository actions() {
        return actions;
    }

    public OpsJournal journal() {
        return journal;
    }

    @Override
    public void close() throws Exception {
        resource.close();
    }
}
