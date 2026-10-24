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
    public record OperationStatus(boolean persistent, boolean healthy, boolean writesStopped, boolean draining,
                                  long uncertainActions, int schemaVersion,
                                  AgentSecurityService.WriteResumeState writeResume, boolean canApproveWriteResume) { }
    @GetMapping public ResponseEntity<?> health(Authentication actor) {
        security.require(actor.getName(), Permission.MANAGE_SYSTEM);
        boolean healthy = store.persistent() && store.durable().healthy();
        var uncertainActions = scopedActions(java.util.Set.of(com.rover.agent.core.model.ActionStatus.UNCERTAIN));
        long uncertain = uncertainActions.size() + scopedConfigActions(
                java.util.Set.of(com.rover.agent.core.model.ConfigActionStatus.UNKNOWN)).size();
        var status = new OperationStatus(store.persistent(), healthy, security.writesStopped(), tasks.draining(),
                uncertain, store.persistent() ? store.durable().schemaVersion() : 0, security.writeResumeState(),
                security.writeResumeApproverAllowed(actor.getName()));
        return ResponseEntity.status(healthy ? 200 : 503).body(status);
    }
    @PostMapping("/write-stop") public ResponseEntity<?> writeStop(Authentication actor) {
        security.stopWrites(actor.getName(), true);
        return health(actor);
    }
    @PostMapping("/resume-approval") public ResponseEntity<?> approveResume(Authentication actor) {
        if (!security.writeResumeApproverAllowed(actor.getName())) {
            return ResponseEntity.status(403).body(Map.of("message", "恢复审批只允许当前有效的系统管理员"));
        }
        if (!store.persistent() || !store.durable().healthy()) {
            return ResponseEntity.status(503).body(Map.of("message", "持久存储未就绪，保持停写"));
        }
        java.util.EnumSet<com.rover.agent.core.model.ActionStatus> outstandingStatuses =
                java.util.EnumSet.of(com.rover.agent.core.model.ActionStatus.QUEUED,
                        com.rover.agent.core.model.ActionStatus.EXECUTING, com.rover.agent.core.model.ActionStatus.VERIFYING,
                        com.rover.agent.core.model.ActionStatus.UNCERTAIN, com.rover.agent.core.model.ActionStatus.RECONCILING,
                        com.rover.agent.core.model.ActionStatus.ROLLBACK_QUEUED, com.rover.agent.core.model.ActionStatus.ROLLING_BACK);
        long outstanding = store.sessions().listAll().stream()
                .filter(session -> java.util.Objects.equals(control.resourceNamespace(), session.targetNamespace())
                        && (control.resourceScope() == null || java.util.Objects.equals(control.resourceScope(), session.scope())))
                .flatMap(session -> store.actions().bySession(session.sessionId()).stream())
                .filter(action -> outstandingStatuses.contains(action.status())).count();
        outstanding += scopedConfigActions(java.util.EnumSet.of(
                com.rover.agent.core.model.ConfigActionStatus.PENDING_APPROVAL,
                com.rover.agent.core.model.ConfigActionStatus.QUEUED,
                com.rover.agent.core.model.ConfigActionStatus.EXECUTING,
                com.rover.agent.core.model.ConfigActionStatus.UNKNOWN,
                com.rover.agent.core.model.ConfigActionStatus.RECONCILE_QUEUED,
                com.rover.agent.core.model.ConfigActionStatus.RECONCILING)).size();
        if (outstanding > 0) {
            return ResponseEntity.status(409).body(Map.of("message", "仍有 " + outstanding + " 条待执行或待对账动作；先完成对账后再审批恢复"));
        }
        try {
            security.approveWriteResume(actor.getName(), outstanding);
            return health(actor);
        } catch (IllegalStateException blocked) {
            return ResponseEntity.status(409).body(Map.of("message", blocked.getMessage()));
        }
    }
    @PostMapping("/drain") public Object drain(Authentication actor) {
        security.require(actor.getName(), Permission.MANAGE_SYSTEM);
        security.stopWrites(actor.getName(), true);
        tasks.beginDrain();
        return health(actor);
    }
    private java.util.List<com.rover.agent.core.model.AgentAction> scopedActions(
            java.util.Set<com.rover.agent.core.model.ActionStatus> statuses) {
        return store.actions().byStatus(statuses, 1000).stream()
                .filter(this::inScope).toList();
    }
    private boolean inScope(com.rover.agent.core.model.AgentAction action) {
        return store.sessions().find(action.sessionId()).map(session ->
                java.util.Objects.equals(control.resourceNamespace(), session.targetNamespace())
                        && (control.resourceScope() == null
                        || java.util.Objects.equals(control.resourceScope(), session.scope())))
                .orElse(false);
    }
    private java.util.List<com.rover.agent.core.model.GatewayConfigAction> scopedConfigActions(
            java.util.Set<com.rover.agent.core.model.ConfigActionStatus> statuses) {
        return store.gatewayConfigActions().byStatus(statuses, 1000).stream()
                .filter(action -> store.sessions().find(action.sessionId()).map(session ->
                        java.util.Objects.equals(control.resourceNamespace(), session.targetNamespace())
                                && (control.resourceScope() == null
                                || java.util.Objects.equals(control.resourceScope(), session.scope())))
                        .orElse(false)).toList();
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
