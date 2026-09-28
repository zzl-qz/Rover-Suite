package com.rover.agent.runtime.repository;

import com.rover.agent.core.repository.AgentTaskRepository;
import com.rover.agent.runtime.journal.H2InvestigationLog;
import com.rover.agent.runtime.journal.OpsJournal;

/** 调查快照和资源笔记的同一份存储。没配路径时任务在内存，笔记不写。 */
public final class InvestigationBundle implements AutoCloseable {

    private final AgentTaskRepository tasks;
    private final OpsJournal journal;
    private final AutoCloseable resource;

    private InvestigationBundle(AgentTaskRepository tasks, OpsJournal journal, AutoCloseable resource) {
        this.tasks = tasks;
        this.journal = journal;
        this.resource = resource;
    }

    public static InvestigationBundle memory(int taskCapacity) {
        return new InvestigationBundle(new InMemoryAgentTaskRepository(taskCapacity), OpsJournal.none(), () -> { });
    }

    public static InvestigationBundle file(String path) {
        H2InvestigationLog store = new H2InvestigationLog(path);
        return new InvestigationBundle(store, OpsJournal.of(store), store);
    }

    public AgentTaskRepository tasks() {
        return tasks;
    }

    public OpsJournal journal() {
        return journal;
    }

    @Override
    public void close() throws Exception {
        resource.close();
    }
}
