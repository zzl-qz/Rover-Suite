package com.rover.admin.agent;

import com.rover.agent.core.model.AgentAction;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 受控变更接口：待审批变更的查看，以及人工的批准 / 拒绝 / 回滚 / 结果确认。
 *
 * <p>这一层只做契约映射：用户身份取自认证上下文，其余全部交给 {@link AgentActionService}。
 * 这里有<b>没有</b>「创建变更」的接口——变更只能由 Agent 在对话里提议，人工入口只负责处置它。
 * 少一个入口，就少一条绕过「先调查、后审批」的路径。
 *
 * <p>执行是同步的：批准请求会等「预检 → 提交 → 回读」走完再返回（每次写请求都有 5 秒级超时上限）。
 * 这样做的好处是返回值就是终态——前端拿到的不是「已受理」，而是「到底成没成、回读到了什么」。
 *
 * <ul>
 *   <li>200：操作完成，返回变更记录的最新状态；</li>
 *   <li>404：变更不存在或不属于当前用户（不区分，避免用 ID 探出别人的变更）；</li>
 *   <li>409：当前状态不允许这个操作（已批准过、还没成功就回滚等）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/agent/actions")
public class AgentActionController {

    private final AgentActionService actions;

    public AgentActionController(AgentActionService actions) {
        this.actions = actions;
    }

    /** 某个会话的变更记录（新的在前）：待审批、执行结果与回滚都在这里。 */
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<List<AgentAction>> bySession(@PathVariable String sessionId,
                                                       Authentication authentication) {
        return actions.bySession(sessionId, user(authentication))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 单条变更的最新状态：执行或回滚之后用来对齐事实。 */
    @GetMapping("/{actionId}")
    public ResponseEntity<AgentAction> find(@PathVariable String actionId, Authentication authentication) {
        return actions.find(actionId, user(authentication))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 批准并执行。
     *
     * <p>连点多次只会执行一次：状态从「待审批 → 执行中」是一次原子迁移，抢不到的那次直接回 409。
     */
    @PostMapping("/{actionId}/approve")
    public ResponseEntity<?> approve(@PathVariable String actionId, Authentication authentication) {
        return apply(() -> actions.approve(actionId, user(authentication)));
    }

    /** 拒绝：不执行，也不会执行。 */
    @PostMapping("/{actionId}/reject")
    public ResponseEntity<?> reject(@PathVariable String actionId, Authentication authentication) {
        return apply(() -> actions.reject(actionId, user(authentication)));
    }

    /** 回滚：把这次改过的那个版本补偿回变更前的权重。 */
    @PostMapping("/{actionId}/rollback")
    public ResponseEntity<?> rollback(@PathVariable String actionId, Authentication authentication) {
        return apply(() -> actions.rollback(actionId, user(authentication)));
    }

    /** 结果确认：对「结果未知」的变更，用原 operationId 再查一次到底生效了没有。 */
    @PostMapping("/{actionId}/resolve")
    public ResponseEntity<?> resolve(@PathVariable String actionId, Authentication authentication) {
        return apply(() -> actions.resolve(actionId, user(authentication)));
    }

    private static ResponseEntity<?> apply(Supplier<AgentAction> operation) {
        try {
            return ResponseEntity.ok(operation.get());
        } catch (ActionRequestException ex) {
            HttpStatus status = ex.code() == ActionRequestException.Code.NOT_FOUND
                    ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", ex.code().name());
            body.put("message", ex.getMessage());
            return ResponseEntity.status(status).body(body);
        }
    }

    /** 当前用户身份；未登录（含匿名）时返回空，不接受前端提交的用户名。 */
    private static String user(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getName();
    }
}
