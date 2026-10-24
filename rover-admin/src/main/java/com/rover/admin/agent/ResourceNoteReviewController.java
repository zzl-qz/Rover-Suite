package com.rover.admin.agent;

import com.rover.admin.security.AgentSecurityService;
import com.rover.agent.core.journal.ResourceNote;
import com.rover.agent.core.journal.ResourceNotes;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.port.AgentAccess.Permission;
import com.rover.agent.core.port.RouteControlPort;
import com.rover.agent.runtime.repository.AgentStore;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 管理已确认的历史资源结论；人工修订不会触碰当前运行状态或动作。 */
@RestController
@RequestMapping("/api/agent/knowledge/resource-notes")
public final class ResourceNoteReviewController {
    private final AgentStore store;
    private final AgentSecurityService security;
    private final RouteControlPort control;

    public ResourceNoteReviewController(AgentStore store, AgentSecurityService security, RouteControlPort control) {
        this.store = store; this.security = security; this.control = control;
    }

    public record ReviewRequest(long version, String resourceKey, String action, String correction) { }

    @GetMapping("/{routeId}")
    public ResponseEntity<?> get(@PathVariable String routeId, Authentication actor) {
        security.require(actor.getName(), Permission.POLICY_ADMIN);
        if (!store.persistent() || !store.durable().healthy()) {
            return ResponseEntity.status(503).body(Map.of("error", "持久知识库未就绪"));
        }
        String key = resourceKey(routeId);
        if (key == null) { return ResponseEntity.notFound().build(); }
        return store.durable().findNote(noteIdentity(key))
                .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{routeId}/review")
    public ResponseEntity<?> review(@PathVariable String routeId, @RequestBody ReviewRequest request, Authentication actor) {
        security.require(actor.getName(), Permission.POLICY_ADMIN);
        if (!store.persistent() || !store.durable().healthy()) {
            return ResponseEntity.status(503).body(Map.of("error", "持久知识库未就绪"));
        }
        String key = resourceKey(routeId);
        if (key == null || request == null || request.version() < 1) { return ResponseEntity.notFound().build(); }
        String identity = noteIdentity(key);
        if (identity == null || !identity.equals(request.resourceKey())) {
            return ResponseEntity.status(409).body(Map.of("error", "资源身份已变化，请刷新后重试"));
        }
        ResourceNote.ReviewStatus status = switch (request.action() == null ? "" : request.action()) {
            case "correct" -> ResourceNote.ReviewStatus.CORRECTED;
            case "retract" -> ResourceNote.ReviewStatus.RETRACTED;
            default -> null;
        };
        if (status == null || status == ResourceNote.ReviewStatus.CORRECTED
                && (request.correction() == null || request.correction().isBlank() || request.correction().length() > 1000)) {
            return ResponseEntity.badRequest().body(Map.of("error", "请选择纠正或撤回，并提供不超过 1000 字的纠正说明"));
        }
        return store.durable().reviewNote(identity, control.resourceNamespace(), request.version(), actor.getName(), status,
                        request.correction(), System.currentTimeMillis())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(409).body(Map.of("error", "记录已变化或已撤回，请刷新后重试")));
    }

    private String noteIdentity(String resourceKey) {
        var route = control.state().routes().stream()
                .filter(item -> item.resourceRef() != null && item.resourceRef().scope().equals(control.resourceScope())
                        && (resourceKey.equals(item.routeId()) || resourceKey.equals(item.businessPrefix())))
                .max(java.util.Comparator.comparingInt(item -> item.businessPrefix().length())).orElse(null);
        if (route == null) { return null; }
        String identity = control.requiresResourceIdentity()
                ? route.resourceRef().resourceUid() + ":" + route.resourceRef().generation()
                : resourceKey;
        return control.resourceNamespace() + ":" + identity;
    }

    private String resourceKey(String routeId) {
        if (routeId == null || routeId.isBlank() || control.resourceScope() == null
                || control.resourceNamespace() == null || control.resourceNamespace().isBlank()) { return null; }
        return control.state().routes().stream()
                .filter(route -> route.resourceRef() != null && (routeId.equals(route.routeId())
                        || routeId.equals(route.businessPrefix()) || routeId.startsWith(route.businessPrefix() + "/")))
                .max(java.util.Comparator.comparingInt(route -> route.businessPrefix().length()))
                .filter(route -> route.resourceRef().scope().equals(control.resourceScope()))
                .map(route -> ResourceNotes.resourceKey(ResourceTarget.route(route.businessPrefix())))
                .orElse(null);
    }
}
