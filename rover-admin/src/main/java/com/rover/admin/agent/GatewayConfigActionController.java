package com.rover.admin.agent;

import com.rover.agent.core.model.GatewayConfigAction;
import com.rover.agent.runtime.action.ActionRequestException;
import com.rover.agent.runtime.action.GatewayConfigActionService;
import java.util.Map;
import java.util.function.Supplier;
import com.rover.agent.core.port.AgentAccess;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Web approval and read-only reconciliation interface for narrow Gateway configuration actions. */
@RestController
@RequestMapping("/api/agent/config-actions")
public class GatewayConfigActionController {
    private final GatewayConfigActionService actions;
    public GatewayConfigActionController(GatewayConfigActionService actions) { this.actions=actions; }
    public record ProposeRequest(String sessionId, String incidentId, String taskId, String desiredValue) { }
    public record ApprovalRequest(String intentHash) { }
    @PostMapping public ResponseEntity<?> propose(@RequestBody ProposeRequest request, Authentication actor) {
        return apply(() -> actions.propose(request.sessionId(), request.incidentId(), request.taskId(),
                request.desiredValue(), user(actor)));
    }
    @PostMapping("/sessions/{sessionId}/config-actions") public ResponseEntity<?> proposeForSession(
            @PathVariable String sessionId, @RequestBody ProposeRequest request, Authentication actor) {
        if (request == null || request.sessionId() != null && !sessionId.equals(request.sessionId())) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", "会话 ID 不匹配"));
        }
        return apply(() -> actions.propose(sessionId, request.incidentId(), request.taskId(),
                request.desiredValue(), user(actor)));
    }
    @GetMapping("/sessions/{sessionId}") public ResponseEntity<?> list(@PathVariable String sessionId, Authentication actor) {
        return apply(() -> actions.bySession(sessionId,user(actor)));
    }
    @GetMapping("/{actionId}") public ResponseEntity<?> find(@PathVariable String actionId, Authentication actor) {
        return actions.find(actionId,user(actor)).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
    @PostMapping("/{actionId}/approve") public ResponseEntity<?> approve(@PathVariable String actionId,
            @RequestBody(required=false) ApprovalRequest request, Authentication actor) {
        return apply(() -> actions.approve(actionId,request==null?null:request.intentHash(),user(actor)));
    }
    @PostMapping("/{actionId}/reject") public ResponseEntity<?> reject(@PathVariable String actionId, Authentication actor) {
        return apply(() -> actions.reject(actionId,user(actor)));
    }
    @PostMapping("/{actionId}/reconcile") public ResponseEntity<?> reconcile(@PathVariable String actionId, Authentication actor) {
        return apply(() -> actions.reconcile(actionId,user(actor)));
    }
    @PostMapping("/{actionId}/compensate") public ResponseEntity<?> compensate(@PathVariable String actionId, Authentication actor) {
        return apply(() -> actions.proposeCompensation(actionId, user(actor)));
    }
    private static ResponseEntity<?> apply(Supplier<?> operation) {
        try { return ResponseEntity.ok(operation.get()); }
        catch (AgentAccess.Denied ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("code", "FORBIDDEN", "message", ex.getMessage()));
        }
        catch (ActionRequestException ex) {
            HttpStatus status=switch(ex.code()) { case NOT_FOUND -> HttpStatus.NOT_FOUND; case CONFLICT -> HttpStatus.CONFLICT; case INVALID_REQUEST -> HttpStatus.BAD_REQUEST; };
            return ResponseEntity.status(status).body(Map.of("code",ex.code().name(),"message",ex.getMessage()));
        }
    }
    private static String user(Authentication actor) {
        return actor==null || !actor.isAuthenticated() || actor instanceof AnonymousAuthenticationToken ? null : actor.getName();
    }
}
