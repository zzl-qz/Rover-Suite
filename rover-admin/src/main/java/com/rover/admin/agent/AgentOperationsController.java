package com.rover.admin.agent;

import com.rover.admin.config.AdminProperties;
import com.rover.admin.security.AgentSecurityService;
import com.rover.agent.core.port.AgentAccess.Permission;
import com.rover.agent.core.port.RouteControlPort;
import com.rover.agent.runtime.repository.AgentStore;
import com.rover.agent.runtime.task.InvestigationTaskRegistry;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 单 Admin 的运行管理；恢复备份必须停机执行，不能通过在线接口覆盖当前库。 */
@RestController
@RequestMapping("/api/agent/operations")
public class AgentOperationsController {
    private final AgentStore store;
    private final AgentSecurityService security;
    private final RouteControlPort control;
    private final InvestigationTaskRegistry tasks;
    private final Path backups;
    public AgentOperationsController(AgentStore store, AgentSecurityService security, RouteControlPort control,
                                     InvestigationTaskRegistry tasks, AdminProperties properties) {
        this.store = store; this.security = security; this.control = control; this.tasks = tasks;
        this.backups = Path.of(properties.getLogStorePath()).toAbsolutePath().getParent().resolve("backups");
    }
    public record WriteStop(boolean stopped) { }
    public record OperationStatus(boolean persistent, boolean healthy, boolean writesStopped, boolean draining,
                                  long uncertainActions, int schemaVersion) { }
    @GetMapping public ResponseEntity<?> health(Authentication actor) {
        security.require(actor.getName(), Permission.MANAGE_SYSTEM);
        boolean healthy = store.persistent() && store.durable().healthy();
        long uncertain = store.actions().byStatus(java.util.Set.of(com.rover.agent.core.model.ActionStatus.UNCERTAIN), 1000)
                .stream().filter(action -> store.sessions().find(action.sessionId()).map(session -> control.resourceScope() == null
                        || control.resourceScope().equals(session.scope())
                        && control.resourceNamespace().equals(session.targetNamespace())).orElse(false)).count();
        var status = new OperationStatus(store.persistent(), healthy, security.writesStopped(), tasks.draining(),
                uncertain, store.persistent() ? store.durable().schemaVersion() : 0);
        return ResponseEntity.status(healthy ? 200 : 503).body(status);
    }
    @PostMapping("/write-stop") public ResponseEntity<?> writeStop(@RequestBody WriteStop body, Authentication actor) {
        security.stopWrites(actor.getName(), body.stopped());
        return health(actor);
    }
    @PostMapping("/drain") public Object drain(Authentication actor) {
        security.require(actor.getName(), Permission.MANAGE_SYSTEM);
        security.stopWrites(actor.getName(), true);
        tasks.beginDrain();
        return health(actor);
    }
    @PostMapping("/resume") public Object resume(Authentication actor) {
        security.require(actor.getName(), Permission.MANAGE_SYSTEM);
        if (!store.durable().healthy()) { return ResponseEntity.status(503).body(Map.of("message", "存储未就绪")); }
        tasks.resumeAccepting();
        // 恢复接任务与恢复写入分开操作，避免一次点击就重新放开生产变更。
        return health(actor);
    }
    @GetMapping("/audit") public Object audit(@RequestParam(defaultValue="0") long after,
                                              @RequestParam(defaultValue="100") int limit, Authentication actor) {
        security.require(actor.getName(), Permission.MANAGE_SYSTEM);
        return store.durable().audit(control.resourceNamespace(), after, limit);
    }
    @GetMapping("/legacy-sessions") public Object legacy(Authentication actor) {
        security.require(actor.getName(), Permission.MANAGE_SYSTEM);
        return store.sessions().listAll().stream().filter(session -> session.scope() == null).toList();
    }
    @GetMapping("/legacy-sessions/{id}") public ResponseEntity<?> legacyDetail(@PathVariable String id, Authentication actor) {
        security.require(actor.getName(), Permission.MANAGE_SYSTEM);
        var session = store.sessions().find(id).filter(value -> value.scope() == null).orElse(null);
        if (session == null) { return ResponseEntity.notFound().build(); }
        return ResponseEntity.ok(Map.of("scopeState", "UNBOUND_ARCHIVE", "session", session,
                "messages", store.messages().bySession(id), "tasks", store.tasks().findBySessionId(id), "actions", store.actions().bySession(id)));
    }
    @PostMapping("/backups") public Object backup(Authentication actor) {
        security.require(actor.getName(), Permission.MANAGE_SYSTEM);
        String id = "agent-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".zip";
        Path destination = backups.resolve(id);
        store.durable().appendAudit("BACKUP_REQUESTED", id, actor.getName(), control.resourceNamespace(), Map.of("id", id));
        store.durable().backup(destination);
        return Map.of("backupId", id, "path", destination.toString());
    }
}
