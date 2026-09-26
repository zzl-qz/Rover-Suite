package com.rover.agent.runtime.task;

import com.rover.agent.core.model.AgentMessage;
import com.rover.agent.core.model.Incident;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.repository.AgentMessageRepository;
import com.rover.agent.core.repository.AgentSessionRepository;
import com.rover.agent.core.repository.IncidentRepository;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内存存储的准入与保留策略：容量满时由本类决定"丢谁"，并且整组丢，绝不留孤儿记录。
 *
 * 存储自己（{@code BoundedStore}）只负责"满了拒绝写入"，不知道谁和谁有关联；
 * 会话 → 事件 → 任务 是一条链，只丢中间一环就会留下"任务挂着不存在的事件"这类悬空引用。
 * 因此清理统一在这里做：
 * <ul>
 *   <li>会话满：按"最久未活动"顺序丢掉会话，连同它的事件、消息与任务一起清；</li>
 *   <li>事件满：丢掉最早的事件，连同它挂着的任务一起清，并从会话上摘除；</li>
 *   <li>消息满：丢掉最早的一条对话——消息是叶子记录，除会话外无人引用它，
 *       这是刻意的短期上下文窗口（与上下文管理器只取最近若干条同口径）。</li>
 * </ul>
 *
 * 两条硬规则：
 * <ol>
 *   <li>还有任务在跑的会话与事件不清理：正在执行的任务还在写自己的快照，删了会在下一次状态变更时"复活"；</li>
 *   <li>清理之后仍然没有位置时，写入照常抛出「存储已满」——容量配得不对必须可见，不能靠丢数据掩盖。</li>
 * </ol>
 *
 * 新增记录一律走 {@code admitXxx}：它们在同一个锁内完成"判断容量 →（必要时清理）→ 写入"，
 * 因此并发登记不会出现两个请求各自腾出半个位置、后到的那个又撞满容量。
 * 这里只在上层写入路径上被调用，不参与读路径。
 */
public final class WorkspaceRetention {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceRetention.class);

    private final AgentSessionRepository sessions;
    private final IncidentRepository incidents;
    private final AgentMessageRepository messages;
    private final TaskRetirement tasks;
    private final int sessionCapacity;
    private final int incidentCapacity;
    private final int messageCapacity;

    public WorkspaceRetention(AgentSessionRepository sessions, IncidentRepository incidents,
                              AgentMessageRepository messages, TaskRetirement tasks,
                              int sessionCapacity, int incidentCapacity, int messageCapacity) {
        this.sessions = sessions;
        this.incidents = incidents;
        this.messages = messages;
        this.tasks = tasks;
        this.sessionCapacity = sessionCapacity;
        this.incidentCapacity = incidentCapacity;
        this.messageCapacity = messageCapacity;
    }

    /** 登记新会话：容量满时按"最久未活动"顺序整组清理，再写入。 */
    public synchronized void admitSession(Session session) {
        for (Session victim : cleanupOrder()) {
            if (sessions.listAll().size() < sessionCapacity) {
                break;
            }
            deleteSessionCascade(victim.sessionId());
        }
        sessions.save(session);
    }

    /** 登记新事件：容量满时先清理最早的事件及它挂着的任务，再写入。 */
    public synchronized void admitIncident(Incident incident) {
        for (Incident victim : incidents.listAll().stream()
                .sorted(Comparator.comparingLong(Incident::createdAtMillis))
                .filter(candidate -> !tasks.hasActiveTasksInIncident(candidate.incidentId()))
                .toList()) {
            if (incidents.listAll().size() < incidentCapacity) {
                break;
            }
            deleteIncidentCascade(victim.incidentId());
        }
        incidents.save(incident);
    }

    /** 追加消息：容量满时先丢掉最早的一条对话（短期上下文窗口），再写入。 */
    public synchronized void admitMessage(AgentMessage message) {
        while (messages.listAll().size() >= messageCapacity) {
            AgentMessage oldest = messages.oldest().orElse(null);
            if (oldest == null) {
                break;
            }
            messages.remove(oldest.messageId());
            log.debug("消息窗口已满（容量 {}），丢弃最早一条消息：会话={} 时间={}", messageCapacity,
                    oldest.sessionId(), oldest.createdAtMillis());
        }
        messages.save(message);
    }

    /**
     * 整组清理一个会话：先删它的事件、消息与任务，最后删会话本身。
     *
     * 顺序刻意如此：中途失败时留下的是"没有子记录的会话"，而不是"找不到会话的子记录"。
     *
     * @return 各保留删除条数；会话不存在时返回 {@code null}
     */
    public synchronized Cleanup deleteSessionCascade(String sessionId) {
        Session session = sessions.find(sessionId).orElse(null);
        if (session == null) {
            return null;
        }
        int incidentCount = incidents.removeBySession(sessionId);
        int messageCount = messages.removeBySession(sessionId);
        int taskCount = tasks.retireBySession(sessionId);
        sessions.remove(sessionId);
        log.info("已整组清理会话 {}：事件 {} 条、消息 {} 条、任务 {} 条", sessionId, incidentCount, messageCount,
                taskCount);
        return new Cleanup(incidentCount, messageCount, taskCount);
    }

    /**
     * 整组清理一个事件：先删它挂着的任务，再删事件本身，最后把它从会话的事件列表与当前事件指针上摘除。
     *
     * @return 是否确实删除了一个事件
     */
    public synchronized boolean deleteIncidentCascade(String incidentId) {
        Incident incident = incidents.find(incidentId).orElse(null);
        if (incident == null) {
            return false;
        }
        int taskCount = tasks.retireByIncident(incidentId);
        incidents.remove(incidentId);
        sessions.find(incident.sessionId())
                .ifPresent(session -> sessions.save(SessionLinks.withoutIncident(session, incidentId)));
        log.info("已整组清理事件 {}：任务 {} 条", incidentId, taskCount);
        return true;
    }

    /** 候选顺序：最久未活动的会话在前，跳过还有任务在跑的——正在用的会话不会被清理掉。 */
    private List<Session> cleanupOrder() {
        return sessions.listAll().stream()
                .sorted(Comparator.comparingLong(Session::lastActiveAtMillis))
                .filter(session -> !tasks.hasActiveTasksInSession(session.sessionId()))
                .toList();
    }

    /** 一次整组清理的结果；条数只用于日志与测试断言。 */
    public record Cleanup(int incidents, int messages, int tasks) { }
}