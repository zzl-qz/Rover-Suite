package com.rover.admin.agent;

import com.rover.agent.core.model.AgentAction;
import com.rover.agent.core.model.ActionApproval;
import com.rover.agent.runtime.action.ActionRequestException;
import com.rover.agent.runtime.action.AgentActionService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 变更查询、审批、拒绝、回滚和结果确认接口；变更由 Agent 提议。
 * 用户身份取自认证上下文，操作委托 {@link AgentActionService} 同步执行。
 * 返回最新状态；不存在或无权访问返回 404，状态冲突返回 409。
 */
@RestController
@RequestMapping("/api/agent/actions")
public class AgentActionController {

    private final AgentActionService actions;

    public AgentActionController(AgentActionService actions) {
        this.actions = actions;
    }

    @GetMapping("/pending")
    public List<AgentAction> pending(Authentication authentication) {
        return actions.pendingApprovals(user(authentication));
    }

    /** 按会话查询变更记录，按创建时间倒序。 */
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<List<AgentAction>> bySession(@PathVariable String sessionId,
                                                       Authentication authentication) {
        return actions.bySession(sessionId, user(authentication))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/sessions/{sessionId}/approval-progress")
    public ResponseEntity<?> approvalProgress(@PathVariable String sessionId, Authentication authentication) {
        return apply(() -> actions.approvalProgressBySession(sessionId, user(authentication)));
    }

    @GetMapping("/{actionId}/approval-progress")
    public ResponseEntity<?> approvalProgress(@PathVariable String actionId,
                                              @RequestParam(defaultValue = "APPLY") ActionApproval.Stage stage,
                                              Authentication authentication) {
        return apply(() -> actions.approvalProgress(actionId, user(authentication), stage));
    }

    /** 查询变更的最新状态。 */
    @GetMapping("/{actionId}")
    public ResponseEntity<AgentAction> find(@PathVariable String actionId, Authentication authentication) {
        return actions.find(actionId, user(authentication))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 批准并同步执行；重复批准返回 409。 */
    @PostMapping("/{actionId}/approve")
    public ResponseEntity<?> approve(@PathVariable String actionId, Authentication authentication,
                                     @RequestBody(required = false) ApprovalRequest request) {
        return apply(() -> actions.approve(actionId, user(authentication),
                request == null ? null : request.planVersion(), request == null ? null : request.planHash()));
    }

    public record ApprovalRequest(Integer planVersion, String planHash) { }

    /** 拒绝待审批变更。 */
    @PostMapping("/{actionId}/reject")
    public ResponseEntity<?> reject(@PathVariable String actionId, Authentication authentication) {
        return apply(() -> actions.reject(actionId, user(authentication)));
    }

    /** 将目标版本的权重恢复为变更前的值。 */
    @PostMapping("/{actionId}/rollback")
    public ResponseEntity<?> rollback(@PathVariable String actionId, Authentication authentication,
                                      @RequestBody(required = false) ApprovalRequest request) {
        return apply(() -> actions.rollback(actionId, user(authentication),
                request == null ? null : request.planVersion(), request == null ? null : request.planHash()));
    }

    /** 按原 operationId 确认结果未知的变更。 */
    @PostMapping("/{actionId}/resolve")
    public ResponseEntity<?> resolve(@PathVariable String actionId, Authentication authentication) {
        return apply(() -> actions.resolve(actionId, user(authentication)));
    }

    private static ResponseEntity<?> apply(Supplier<?> operation) {
        try {
            return ResponseEntity.ok(operation.get());
        } catch (ActionRequestException ex) {
            HttpStatus status = switch (ex.code()) {
                case NOT_FOUND -> HttpStatus.NOT_FOUND;
                case CONFLICT -> HttpStatus.CONFLICT;
                case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            };
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", ex.code().name());
            body.put("message", ex.getMessage());
            return ResponseEntity.status(status).body(body);
        }
    }

    /** 从认证上下文获取用户身份；未登录或匿名时返回空。 */
    private static String user(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getName();
    }
}
