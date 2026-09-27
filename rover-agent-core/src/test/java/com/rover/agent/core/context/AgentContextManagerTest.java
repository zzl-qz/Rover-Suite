package com.rover.agent.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.model.AgentMessage;
import com.rover.agent.core.model.Confidence;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.EvidenceType;
import com.rover.agent.core.model.Incident;
import com.rover.agent.core.model.IncidentOrigin;
import com.rover.agent.core.model.IncidentStatus;
import com.rover.agent.core.model.InvestigationReport;
import com.rover.agent.core.model.MessageRole;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.SessionStatus;
import com.rover.agent.core.model.TargetType;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.core.port.InstanceReadPort;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.repository.AgentMessageRepository;
import com.rover.agent.core.repository.AgentSessionRepository;
import com.rover.agent.core.repository.AgentTaskRepository;
import com.rover.agent.core.repository.IncidentRepository;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentContextManagerTest {

    private final FakeSessions sessions = new FakeSessions();
    private final FakeMessages messages = new FakeMessages();
    private final FakeIncidents incidents = new FakeIncidents();
    private final FakeTasks tasks = new FakeTasks();

    private final RouteReadPort routePort = new RouteReadPort() {
        @Override
        public List<RouteSnapshot> routes() {
            return List.of(new RouteSnapshot("r1", "/api/demo/tt", "demo-service", "", "", 1L));
        }

        @Override
        public DiscoveryMode discoveryMode() {
            return DiscoveryMode.UNKNOWN;
        }
    };
    private final InstanceReadPort instancePort = () -> List.of(
            new InstanceSnapshot("demo-service", "", "", "10.0.0.7", 8080, true, 100, true, 0L));

    private AgentContextManager manager(int limit) {
        return new AgentContextManager(sessions, incidents, messages, tasks, routePort, instancePort, limit);
    }

    @Test
    void unknownSessionHasNoContext() {
        assertTrue(manager(8).build("missing").isEmpty());
    }

    @Test
    void sessionWithoutIncidentHasUnknownTargetAndNoEvidence() {
        Session session = sessions.open();
        messages.add(session.sessionId(), MessageRole.USER, "第一句");

        AgentContext context = manager(8).build(session.sessionId()).orElseThrow();

        assertEquals(TargetType.UNKNOWN, context.currentTarget().type());
        assertFalse(context.hasActiveIncident());
        assertTrue(context.timeRange().isUnspecified());
        assertEquals(1, context.recentMessages().size());
        assertTrue(context.importantEvidence().isEmpty());
    }

    @Test
    void recentMessagesAreLimitedToConfiguredWindow() {
        Session session = sessions.open();
        for (int i = 0; i < 5; i++) {
            messages.add(session.sessionId(), MessageRole.USER, "问题" + i);
        }

        AgentContext context = manager(2).build(session.sessionId()).orElseThrow();

        // 只保留最近两条，且仍按时间顺序（最早在前）交给模型。
        assertEquals(List.of("问题3", "问题4"),
                context.recentMessages().stream().map(AgentMessage::content).toList());
    }

    @Test
    void routeTargetExpandsToItsService() {
        Session session = sessions.open();
        sessions.pointAt(session.sessionId(), incidents.open(session.sessionId(),
                ResourceTarget.route("/api/demo/tt")).incidentId());

        AgentContext context = manager(8).build(session.sessionId()).orElseThrow();

        assertEquals("/api/demo/tt", context.currentRoute());
        assertEquals("demo-service", context.currentService());
        assertNull(context.currentInstance());
        assertTrue(context.hasActiveIncident());
    }

    @Test
    void instanceTargetExpandsToItsService() {
        Session session = sessions.open();
        sessions.pointAt(session.sessionId(), incidents.open(session.sessionId(),
                ResourceTarget.instance("10.0.0.7:8080")).incidentId());

        AgentContext context = manager(8).build(session.sessionId()).orElseThrow();

        assertEquals("10.0.0.7:8080", context.currentInstance());
        assertEquals("demo-service", context.currentService());
        assertNull(context.currentRoute());
    }

    @Test
    void importantEvidenceComesFromLatestTaskOfActiveIncident() {
        Session session = sessions.open();
        Incident incident = incidents.open(session.sessionId(), ResourceTarget.route("/api/demo/tt"));
        sessions.pointAt(session.sessionId(), incident.incidentId());
        tasks.save(task("task-old", incident.incidentId(), 10L, "旧证据"));
        tasks.save(task("task-new", incident.incidentId(), 20L, "新证据"));
        incidents.attach(incident.incidentId(), "task-old");
        incidents.attach(incident.incidentId(), "task-new");

        AgentContext context = manager(8).build(session.sessionId()).orElseThrow();

        assertEquals(List.of("新证据"),
                context.importantEvidence().stream().map(Evidence::summary).toList());
    }

    private static TaskView task(String taskId, String incidentId, long createdAt, String evidenceSummary) {
        List<Evidence> evidence = List.of(Evidence.of(taskId, EvidenceType.ROUTE, "Gateway 路由表",
                "路由快照", evidenceSummary, "/api/routes", createdAt));
        InvestigationReport report = new InvestigationReport("结论", Confidence.MEDIUM, evidence, List.of(),
                List.of(), null);
        return new TaskView(taskId, "session", incidentId, TaskStatus.COMPLETED, null, "/api/demo/tt",
                ResourceTarget.route("/api/demo/tt"), "问题", createdAt, createdAt + 1, List.of(), report, null,
                null);
    }

    private static final class FakeSessions implements AgentSessionRepository {

        private final Map<String, Session> values = new LinkedHashMap<>();
        private int sequence;

        Session open() {
            long now = System.currentTimeMillis();
            Session session = new Session("session-" + (++sequence), "admin", null, null, SessionStatus.ACTIVE,
                    now, now, List.of());
            values.put(session.sessionId(), session);
            return session;
        }

        void pointAt(String sessionId, String incidentId) {
            Session session = values.get(sessionId);
            values.put(sessionId, new Session(session.sessionId(), session.userId(), session.title(), incidentId,
                    session.status(), session.createdAtMillis(), session.lastActiveAtMillis(), session.incidentIds()));
        }

        @Override
        public void save(Session session) {
            values.put(session.sessionId(), session);
        }

        @Override
        public Optional<Session> find(String sessionId) {
            return Optional.ofNullable(values.get(sessionId));
        }

        @Override
        public List<Session> findByUserId(String userId) {
            return values.values().stream()
                    .filter(session -> java.util.Objects.equals(session.userId(), userId))
                    .toList();
        }

        @Override
        public List<Session> listAll() {
            return List.copyOf(values.values());
        }

        @Override
        public void remove(String sessionId) {
            values.remove(sessionId);
        }
    }

    private static final class FakeMessages implements AgentMessageRepository {

        private final List<AgentMessage> values = new ArrayList<>();

        void add(String sessionId, MessageRole role, String content) {
            values.add(new AgentMessage("message-" + (values.size() + 1), sessionId, role, content,
                    System.currentTimeMillis()));
        }

        @Override
        public void save(AgentMessage message) {
            values.add(message);
        }

        @Override
        public List<AgentMessage> bySession(String sessionId) {
            return values.stream().filter(message -> message.sessionId().equals(sessionId)).toList();
        }

        @Override
        public List<AgentMessage> listAll() {
            return List.copyOf(values);
        }

        @Override
        public Optional<AgentMessage> oldest() {
            return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
        }

        @Override
        public void remove(String messageId) {
            values.removeIf(message -> message.messageId().equals(messageId));
        }

        @Override
        public int removeBySession(String sessionId) {
            List<AgentMessage> matched = values.stream()
                    .filter(message -> message.sessionId().equals(sessionId))
                    .toList();
            values.removeAll(matched);
            return matched.size();
        }
    }

    private static final class FakeIncidents implements IncidentRepository {

        private final Map<String, Incident> values = new LinkedHashMap<>();

        Incident open(String sessionId, ResourceTarget target) {
            long now = System.currentTimeMillis();
            Incident incident = new Incident("incident-" + (values.size() + 1), sessionId, IncidentOrigin.USER,
                    target.value(), IncidentStatus.OPEN, target, TimeRange.unspecified(),
                    "", now, now, List.of());
            values.put(incident.incidentId(), incident);
            return incident;
        }

        void attach(String incidentId, String taskId) {
            Incident incident = values.get(incidentId);
            List<String> taskIds = new ArrayList<>(incident.taskIds());
            taskIds.add(taskId);
            values.put(incidentId, new Incident(incident.incidentId(), incident.sessionId(), incident.origin(),
                    incident.title(), incident.status(), incident.target(),
                    incident.timeRange(), incident.summary(), incident.createdAtMillis(), incident.updatedAtMillis(),
                    List.copyOf(taskIds)));
        }

        @Override
        public void save(Incident incident) {
            values.put(incident.incidentId(), incident);
        }

        @Override
        public Optional<Incident> find(String incidentId) {
            return Optional.ofNullable(values.get(incidentId));
        }

        @Override
        public List<Incident> listAll() {
            return List.copyOf(values.values());
        }

        @Override
        public List<Incident> bySession(String sessionId) {
            return values.values().stream().filter(incident -> incident.sessionId().equals(sessionId)).toList();
        }

        @Override
        public void remove(String incidentId) {
            values.remove(incidentId);
        }

        @Override
        public int removeBySession(String sessionId) {
            List<String> ids = values.values().stream()
                    .filter(incident -> incident.sessionId().equals(sessionId))
                    .map(Incident::incidentId)
                    .toList();
            ids.forEach(values::remove);
            return ids.size();
        }
    }

    private static final class FakeTasks implements AgentTaskRepository {

        private final Map<String, TaskView> values = new LinkedHashMap<>();

        @Override
        public void save(TaskView task) {
            values.put(task.taskId(), task);
        }

        @Override
        public Optional<TaskView> find(String taskId) {
            return Optional.ofNullable(values.get(taskId));
        }

        @Override
        public Optional<TaskView> findActiveBySessionId(String sessionId) {
            return values.values().stream()
                    .filter(view -> sessionId.equals(view.sessionId()) && view.status().active())
                    .findFirst();
        }

        @Override
        public List<TaskView> listAll() {
            return List.copyOf(values.values());
        }

        @Override
        public List<TaskView> findBySessionId(String sessionId) {
            return values.values().stream()
                    .filter(view -> sessionId.equals(view.sessionId()))
                    .toList();
        }

        @Override
        public List<TaskView> findByIncidentId(String incidentId) {
            return values.values().stream()
                    .filter(view -> java.util.Objects.equals(view.incidentId(), incidentId))
                    .toList();
        }

        @Override
        public List<TaskView> recentBySession(String sessionId, int limit) {
            return values.values().stream()
                    .filter(view -> sessionId.equals(view.sessionId()))
                    .sorted(Comparator.comparingLong(TaskView::createdAtMillis).reversed())
                    .limit(Math.max(limit, 0))
                    .toList();
        }

        @Override
        public void remove(String taskId) {
            values.remove(taskId);
        }

        @Override
        public int removeBySession(String sessionId) {
            List<String> ids = values.values().stream()
                    .filter(view -> sessionId.equals(view.sessionId()))
                    .map(TaskView::taskId)
                    .toList();
            ids.forEach(values::remove);
            return ids.size();
        }

        @Override
        public int removeByIncident(String incidentId) {
            List<String> ids = values.values().stream()
                    .filter(view -> java.util.Objects.equals(view.incidentId(), incidentId))
                    .map(TaskView::taskId)
                    .toList();
            ids.forEach(values::remove);
            return ids.size();
        }
    }
}