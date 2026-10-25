package com.rover.admin.agent;

import com.rover.admin.security.AgentSecurityService;
import com.rover.agent.core.model.ActionEffectVerification;
import com.rover.agent.core.model.AgentAction;
import com.rover.agent.core.model.ConfigActionStatus;
import com.rover.agent.core.model.GatewayConfigAction;
import com.rover.agent.core.model.Incident;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.port.AgentAccess.Permission;
import com.rover.agent.core.port.RouteControlPort;
import com.rover.agent.runtime.repository.AgentStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only incident history assembled from the durable task and action records. */
@RestController
@RequestMapping("/api/agent/incidents")
public final class IncidentCaseController {
    private static final int MAX_CASE_RECORDS = 500;
    private static final int MAX_CASE_AUDIT = 500;
    private final AgentStore store;
    private final AgentSecurityService security;
    private final RouteControlPort control;

    public IncidentCaseController(AgentStore store, AgentSecurityService security, RouteControlPort control) {
        this.store = store;
        this.security = security;
        this.control = control;
    }

    @GetMapping("/{incidentId}/case")
    public ResponseEntity<CaseRecord> get(@PathVariable String incidentId, Authentication actor) {
        security.require(actor.getName(), Permission.READ);
        return getCase(incidentId, actor.getName(), false);
    }

    public ResponseEntity<CaseRecord> getForReviewer(String incidentId, Authentication actor) {
        security.require(actor.getName(), Permission.POLICY_ADMIN);
        return getCase(incidentId, actor.getName(), true);
    }

    private ResponseEntity<CaseRecord> getCase(String incidentId, String principal, boolean reviewAccess) {
        if (!store.persistent() || !store.durable().healthy()) { return ResponseEntity.status(503).build(); }
        Incident incident = store.incidents().find(incidentId).orElse(null);
        if (incident == null) { return ResponseEntity.notFound().build(); }
        Session session = store.sessions().find(incident.sessionId()).orElse(null);
        if (!visible(session, principal, reviewAccess)) { return ResponseEntity.notFound().build(); }

        List<TaskView> tasks = store.durable().caseTasks(incidentId, session.sessionId(), MAX_CASE_RECORDS + 1);
        List<AgentAction> actions = store.durable().caseActions(incidentId, session.sessionId(), MAX_CASE_RECORDS + 1);
        boolean recordsTruncated = tasks.size() > MAX_CASE_RECORDS || actions.size() > MAX_CASE_RECORDS;
        tasks = recent(tasks, MAX_CASE_RECORDS);
        actions = recent(actions, MAX_CASE_RECORDS);
        List<ActionRecord> actionRecords = actions.stream().map(action -> new ActionRecord(action,
                store.effectVerifications().find(action.actionId(), ActionEffectVerification.Phase.APPLY).orElse(null),
                store.effectVerifications().find(action.actionId(), ActionEffectVerification.Phase.COMPENSATION).orElse(null)))
                .toList();
        List<GatewayConfigAction> configActions = store.durable()
                .caseConfigActions(incidentId, session.sessionId(), MAX_CASE_RECORDS + 1);
        recordsTruncated |= configActions.size() > MAX_CASE_RECORDS;
        configActions = recent(configActions, MAX_CASE_RECORDS);
        List<com.rover.agent.core.event.TaskEvent> taskEvents = store.durable().caseTaskEvents(incidentId,
                session.sessionId(), MAX_CASE_AUDIT + 1);
        boolean taskEventsTruncated = taskEvents.size() > MAX_CASE_AUDIT;
        if (taskEventsTruncated) { taskEvents = taskEvents.subList(taskEvents.size() - MAX_CASE_AUDIT, taskEvents.size()); }
        List<CaseAuditEvent> audit = store.durable().caseAudit(incidentId, session.sessionId(),
                        session.targetNamespace(), session.scope(), MAX_CASE_AUDIT + 1).stream()
                .map(item -> new CaseAuditEvent(item.id(), item.kind(), item.aggregateId(), item.actor(),
                        item.createdAtMillis())).toList();
        boolean auditTruncated = audit.size() > MAX_CASE_AUDIT;
        if (auditTruncated) { audit = audit.subList(audit.size() - MAX_CASE_AUDIT, audit.size()); }
        List<TimelineEvent> timeline = timeline(tasks, actionRecords, configActions, taskEvents, audit);
        boolean truncated = recordsTruncated || taskEventsTruncated || auditTruncated;
        return ResponseEntity.ok(new CaseRecord(incident, tasks, actionRecords, configActions, timeline, audit, truncated));
    }

    private static <T> List<T> recent(List<T> values, int limit) {
        return List.copyOf(values.subList(Math.max(0, values.size() - limit), values.size()));
    }

    private boolean visible(Session session, String principal, boolean reviewAccess) {
        return session != null && (reviewAccess || Objects.equals(session.userId(), principal))
                && Objects.equals(session.targetNamespace(), control.resourceNamespace())
                && Objects.equals(session.scope(), control.resourceScope());
    }

    private static List<TimelineEvent> timeline(List<TaskView> tasks, List<ActionRecord> actions,
                                                List<GatewayConfigAction> configActions,
                                                List<com.rover.agent.core.event.TaskEvent> taskEvents,
                                                List<CaseAuditEvent> audit) {
        List<TimelineEvent> events = new ArrayList<>();
        tasks.forEach(task -> events.add(new TimelineEvent(task.createdAtMillis(), "INVESTIGATION",
                task.status().name(), task.result() != null ? task.result().summary()
                        : task.error() != null && !task.error().isBlank() ? task.error() : task.question(),
                task.taskId(), null, task.completedAtMillis() > 0 ? task.completedAtMillis() : null)));
        taskEvents.forEach(event -> events.add(new TimelineEvent(event.timestampMillis(), "TASK_EVENT",
                event.type().name(), taskEventSummary(event), event.taskId(), null, event.timestampMillis(), event.eventId())));
        actions.forEach(record -> {
            AgentAction action = record.action();
            events.add(new TimelineEvent(action.createdAtMillis(), "ACTION", action.status().name(),
                    action.describe(), action.taskId(), action.actionId(), action.updatedAtMillis()));
        });
        configActions.forEach(action -> events.add(new TimelineEvent(action.createdAtMillis(), "CONFIG_ACTION",
                action.status().name(), action.intent().configKey() + "：" + action.intent().beforeValue()
                        + " → " + action.intent().desiredValue(), action.taskId(), action.actionId(), action.updatedAtMillis())));
        audit.forEach(item -> events.add(new TimelineEvent(item.occurredAtMillis(), "AUDIT", item.kind(),
                auditSummary(item), null, item.aggregateId(), item.occurredAtMillis(), item.id())));
        events.sort(Comparator.comparingLong((TimelineEvent event) -> Objects.requireNonNullElse(
                        event.lastUpdatedAtMillis(), event.occurredAtMillis()))
                .thenComparing(TimelineEvent::kind).thenComparing(event -> Objects.toString(event.actionId(), event.taskId()))
                .thenComparingLong(event -> Objects.requireNonNullElse(event.auditId(), 0L)));
        return List.copyOf(events);
    }

    public record CaseRecord(Incident incident, List<TaskView> investigations, List<ActionRecord> actions,
                             List<GatewayConfigAction> configActions, List<TimelineEvent> timeline,
                             List<CaseAuditEvent> audit, boolean truncated) {
        public CaseRecord {
            investigations = List.copyOf(investigations);
            actions = List.copyOf(actions);
            configActions = List.copyOf(configActions);
            timeline = List.copyOf(timeline);
            audit = List.copyOf(audit);
        }
        public CaseRecord(Incident incident, List<TaskView> investigations, List<ActionRecord> actions,
                          List<GatewayConfigAction> configActions, List<TimelineEvent> timeline,
                          List<CaseAuditEvent> audit) {
            this(incident, investigations, actions, configActions, timeline, audit, false);
        }
    }
    public record ActionRecord(AgentAction action, ActionEffectVerification applyEffect,
                               ActionEffectVerification compensationEffect) { }
    private static String auditSummary(CaseAuditEvent event) {
        return (event.aggregateId() == null ? "" : event.aggregateId())
                + (event.actor() == null ? "" : " · 操作者 " + event.actor());
    }
    public record TimelineEvent(long occurredAtMillis, String kind, String status, String summary,
                                String taskId, String actionId, Long lastUpdatedAtMillis, Long auditId) {
        public TimelineEvent(long occurredAtMillis, String kind, String status, String summary,
                             String taskId, String actionId, Long lastUpdatedAtMillis) {
            this(occurredAtMillis, kind, status, summary, taskId, actionId, lastUpdatedAtMillis, null);
        }
    }
    public record CaseAuditEvent(long id, String kind, String aggregateId, String actor,
                                 long occurredAtMillis) { }

    private static String taskEventSummary(com.rover.agent.core.event.TaskEvent event) {
        if (event.payload() instanceof java.util.Map<?, ?> payload) {
            Object summary = payload.get("summary");
            if (summary == null) { summary = payload.get("clarification"); }
            if (summary == null) { summary = payload.get("error"); }
            return summary == null ? event.type().name() : summary.toString();
        }
        return event.type().name();
    }
}
