package com.rover.agent.runtime.repository;

import com.rover.agent.core.repository.AgentActionRepository;
import com.rover.agent.core.repository.GatewayConfigActionRepository;
import com.rover.agent.core.repository.ActionCertificationRepository;
import com.rover.agent.core.repository.AutoExecutionPolicyRepository;
import com.rover.agent.core.repository.AutonomousActionBudgetRepository;
import com.rover.agent.core.repository.AlertLifecycleRepository;
import com.rover.agent.core.repository.ActionCommandRepository;
import com.rover.agent.core.repository.ActionEffectVerificationRepository;
import com.rover.agent.core.repository.AgentCheckpointRepository;
import com.rover.agent.core.repository.AgentMessageRepository;
import com.rover.agent.core.repository.AgentSessionRepository;
import com.rover.agent.core.repository.AgentTaskRepository;
import com.rover.agent.core.repository.IncidentRepository;
import com.rover.agent.core.repository.KnowledgeDocumentRepository;
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
    private final GatewayConfigActionRepository gatewayConfigActions;
    private final KnowledgeDocumentRepository knowledgeDocuments;
    private final AutoExecutionPolicyRepository autoExecutionPolicies;
    private final ActionCertificationRepository actionCertifications;
    private final AutonomousActionBudgetRepository autonomousActionBudget;
    private final AlertLifecycleRepository alertLifecycles;
    private final ActionCommandRepository actionCommands;
    private final ActionEffectVerificationRepository effectVerifications;
    private final OpsJournal journal;
    private final AutoCloseable resource;
    private java.nio.channels.FileChannel processLockChannel;
    private java.nio.channels.FileLock processLock;

    private AgentStore(AgentSessionRepository sessions, AgentMessageRepository messages,
                       IncidentRepository incidents, AgentTaskRepository tasks,
                       AgentCheckpointRepository checkpoints, AgentActionRepository actions,
                       GatewayConfigActionRepository gatewayConfigActions,
                       KnowledgeDocumentRepository knowledgeDocuments,
                       ActionCommandRepository actionCommands, AlertLifecycleRepository alertLifecycles,
                       AutoExecutionPolicyRepository autoExecutionPolicies,
                       ActionCertificationRepository actionCertifications,
                       AutonomousActionBudgetRepository autonomousActionBudget,
                       OpsJournal journal, AutoCloseable resource) {
        this.sessions = sessions;
        this.messages = messages;
        this.incidents = incidents;
        this.tasks = tasks;
        this.checkpoints = checkpoints;
        this.actions = actions;
        this.gatewayConfigActions = gatewayConfigActions;
        this.knowledgeDocuments = knowledgeDocuments;
        this.alertLifecycles = alertLifecycles;
        this.autoExecutionPolicies = autoExecutionPolicies;
        this.actionCertifications = actionCertifications;
        this.autonomousActionBudget = autonomousActionBudget;
        this.actionCommands = actionCommands;
        this.effectVerifications = resource instanceof JdbcAgentStore jdbc
                ? jdbc.effectVerifications() : new InMemoryActionEffectVerificationRepository(1000);
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
                new InMemoryAgentActionRepository(actionCapacity), new InMemoryGatewayConfigActionRepository(actionCapacity),
                new InMemoryKnowledgeDocumentRepository(),
                new InMemoryActionCommandRepository(1000),
                new InMemoryAlertLifecycleRepository(),
                new InMemoryAutoExecutionPolicyRepository(),
                new InMemoryActionCertificationRepository(),
                new InMemoryAutonomousActionBudgetRepository(),
                OpsJournal.none(), () -> { });
    }

    /** 落库实现：会话、事件、任务、步骤、证据、安全恢复点、变更记录与资源笔记都写进同一个库文件。 */
    public static AgentStore file(String path) {
        java.nio.channels.FileChannel channel = null;
        java.nio.channels.FileLock lock = null;
        try {
            var lockPath = java.nio.file.Path.of(path + ".admin-agent.lock").toAbsolutePath();
            java.nio.file.Files.createDirectories(lockPath.getParent());
            channel = java.nio.channels.FileChannel.open(lockPath, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock == null) { throw new IllegalStateException("当前 H2 已被另一 Admin Agent 使用；当前阶段只允许一个执行进程"); }
            JdbcAgentStore store = new JdbcAgentStore(path);
            AgentStore result = new AgentStore(store.sessions(), store.messages(), store.incidents(), store.tasks(),
                    store.checkpoints(), store.actions(), store.gatewayConfigActions(), store.knowledgeDocuments(), store.actionCommands(), store.alertLifecycles(), store.autoExecutionPolicies(),
                    store.actionCertifications(),
                    store.autonomousActionBudget(),
                    OpsJournal.of(store), store);
            result.processLockChannel = channel; result.processLock = lock;
            return result;
        } catch (java.io.IOException | RuntimeException ex) {
            try { if (lock != null) { lock.close(); } if (channel != null) { channel.close(); } }
            catch (java.io.IOException cleanup) { ex.addSuppressed(cleanup); }
            throw new IllegalStateException("获取单 Admin 执行权或打开持久库失败", ex);
        }
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

    public GatewayConfigActionRepository gatewayConfigActions() { return gatewayConfigActions; }
    public KnowledgeDocumentRepository knowledgeDocuments() { return knowledgeDocuments; }

    public ActionCommandRepository actionCommands() { return actionCommands; }

    public AlertLifecycleRepository alertLifecycles() { return alertLifecycles; }

    public AutoExecutionPolicyRepository autoExecutionPolicies() { return autoExecutionPolicies; }

    public ActionCertificationRepository actionCertifications() { return actionCertifications; }

    public AutonomousActionBudgetRepository autonomousActionBudget() { return autonomousActionBudget; }

    public ActionEffectVerificationRepository effectVerifications() { return effectVerifications; }

    public OpsJournal journal() {
        return journal;
    }

    public JdbcAgentStore durable() {
        if (!(resource instanceof JdbcAgentStore jdbc)) { throw new IllegalStateException("当前是显式内存模式，不能进行持久存储操作"); }
        return jdbc;
    }

    public boolean persistent() { return resource instanceof JdbcAgentStore; }

    @Override
    public void close() throws Exception {
        try { resource.close(); }
        finally {
            if (processLock != null) { processLock.close(); }
            if (processLockChannel != null) { processLockChannel.close(); }
        }
    }
}
