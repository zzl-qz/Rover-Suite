package com.rover.admin.security;

import com.rover.agent.core.port.AgentAccess;
import java.util.Map;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 管理账号和机器来源；机器秘密仅在创建响应中出现。 */
@RestController
@RequestMapping("/api/agent/security")
public class AgentSecurityController {
    private final AgentSecurityService security;
    public AgentSecurityController(AgentSecurityService security) { this.security = security; }
    public record UserRequest(String username, String password, Set<String> roles, boolean enabled) { }
    public record TokenRequest(String name, Long expiresAtMillis) { }

    @GetMapping("/me") public Object me(Authentication actor) {
        return Map.of("principal", actor.getName(), "permissions", security.permissions(actor.getName()));
    }
    @GetMapping("/users") public Object users(Authentication actor) { return security.accounts(actor.getName()); }
    @PostMapping("/users") public Object save(@RequestBody UserRequest body, Authentication actor) {
        return security.saveAccount(actor.getName(), body.username(), body.password(), body.roles(), body.enabled());
    }
    @GetMapping("/connectors") public Object tokens(Authentication actor) { return security.machines(actor.getName()); }
    @PostMapping("/connectors") public ResponseEntity<?> issue(@RequestBody TokenRequest body, Authentication actor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(security.issue(actor.getName(), body.name(), body.expiresAtMillis()));
    }
    @PostMapping("/connectors/{id}/revoke") public Object revoke(@PathVariable String id, Authentication actor) {
        return Map.of("revoked", security.revoke(actor.getName(), id));
    }

    @RestControllerAdvice
    static class Errors {
        @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> invalid(IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", error.getMessage() == null ? "参数不合法" : error.getMessage()));
        }
        @ExceptionHandler(AgentAccess.Denied.class) ResponseEntity<?> denied(AgentAccess.Denied error) {
            return ResponseEntity.status(403).body(Map.of("code", "ACCESS_DENIED", "message", error.getMessage()));
        }
    }
}
