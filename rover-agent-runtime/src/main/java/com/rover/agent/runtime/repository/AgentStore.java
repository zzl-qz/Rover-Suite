package com.rover.agent.runtime.repository;

import com.rover.agent.core.repository.AgentCheckpointRepository;
import com.rover.agent.core.repository.AgentMessageRepository;
import com.rover.agent.core.repository.AgentSessionRepository;
import com.rover.agent.core.repository.AgentTaskRepository;
import com.rover.agent.core.repository.IncidentRepository;
import com.rover.agent.runtime.journal.OpsJournal;

/**
 * Agent 存储组合根：会话 / 消息 / 事件 / 任务 / 资源笔记一次装配好。
 *
 * <p>没配记录库路径（单测、无状态运行）时全部走内存，重启即失；配了路径就落到同一份库，
 * 重启后整条链——会话 → 事件 → 任务 → 步骤 / 证据——都还能读回来。
 *
 * <p>上层只认 {@code com.rover.agent.core.repository} 里的接口，因此换实现不影响任何业务代码；
 * 容量只对内存实现有意义，落库实现不受它约束。
 */
public final class AgentStore implements AutoCloseable {

    private final AgentSessionRepository sessions;
    private final AgentMessageRepository messages;
    private final IncidentRepository incidents;
    private final AgentTaskRepository tasks;
    private final AgentCheckpointRepository checkpoints;
    private final OpsJournal journal;
    private final AutoCloseable resource;

    private AgentStore(AgentSessionRepository sessions, AgentMessageRepository messages,
                       IncidentRepository incidents, AgentTaskRepository tasks,
                       AgentCheckpointRepository checkpoints, OpsJournal journal, AutoCloseable resource) {
        this.sessions = sessions;
        this.messages = messages;
        this.incidents = incidents;
        this.tasks = tasks;
        this.checkpoints = checkpoints;
        this.journal = journal;
        this.resource = resource;
    }

    /** 进程内实现：容量满会拒绝写入，笔记不记。 */
    public static AgentStore memory(int sessionCapacity, int incidentCapacity, int messageCapacity,
                                    int taskCapacity) {
        return new AgentStore(new InMemoryAgentSessionRepository(sessionCapacity),
                new InMemoryAgentMessageRepository(messageCapacity),
                new InMemoryIncidentRepository(incidentCapacity),
                new InMemoryAgentTaskRepository(taskCapacity), new InMemoryAgentCheckpointRepository(),
                OpsJournal.none(), () -> { });
    }

    /** 落库实现：会话、事件、任务、步骤、证据、安全恢复点与资源笔记都写进同一个库文件。 */
    public static AgentStore file(String path) {
        JdbcAgentStore store = new JdbcAgentStore(path);
        return new AgentStore(store.sessions(), store.messages(), store.incidents(), store.tasks(),
                store.checkpoints(), OpsJournal.of(store), store);
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

    public OpsJournal journal() {
        return journal;
    }

    @Override
    public void close() throws Exception {
        resource.close();
    }
}
