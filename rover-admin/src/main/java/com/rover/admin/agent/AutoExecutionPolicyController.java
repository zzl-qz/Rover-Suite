package com.rover.admin.agent;

import com.rover.agent.core.model.AutoExecutionPolicy;
import com.rover.agent.core.model.ResourceRef;
import com.rover.agent.core.port.AgentAccess.Permission;
import com.rover.agent.core.port.RouteControlPort;
import com.rover.agent.core.repository.AutoExecutionPolicyRepository;
import com.rover.agent.runtime.repository.AgentStore;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Exact-scope route quarantine authorizations. The caller can select a route, never supply its identity. */
@RestController
@RequestMapping("/api/agent/policies/autonomous")
public class AutoExecutionPolicyController {
    private static final long MAX_LIFETIME = Duration.ofDays(7).toMillis();
    private final AutoExecutionPolicyRepository policies;
    private final RouteControlPort control;
    private final com.rover.admin.security.AgentSecurityService security;
    private final AgentStore store;

    public AutoExecutionPolicyController(AutoExecutionPolicyRepository policies, RouteControlPort control,
            com.rover.admin.security.AgentSecurityService security, AgentStore store) {
        this.policies = policies;
        this.control = control;
        this.security = security;
        this.store = store;
    }

    @GetMapping public Object list(Authentication actor) {
        security.require(actor.getName(), Permission.POLICY_ADMIN);
        return policies.list(control.resourceNamespace());
    }

    @GetMapping("/candidates") public Object candidates(Authentication actor) {
        security.require(actor.getName(), Permission.POLICY_ADMIN);
        return control.state().routes().stream().filter(route -> route.resourceRef() != null)
                .map(route -> new Candidate(route.routeId(), route.businessPrefix(), route.resourceRef(), route.targets()))
                .toList();
    }

    @PostMapping public ResponseEntity<?> create(@RequestBody CreateRequest body, Authentication actor) {
        security.require(actor.getName(), Permission.POLICY_ADMIN);
        long now = System.currentTimeMillis();
        if (!store.persistent() || !store.durable().healthy()) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "预授权要求健康的持久 H2 存储");
        }
        if (body == null || body.policyId() == null || !body.policyId().matches("[A-Za-z0-9._-]{1,64}")
                || body.route() == null || body.route().isBlank() || body.serviceName() == null
                || body.serviceName().isBlank() || body.group() == null || body.group().isBlank()
                || !Double.isFinite(body.maxBeforeTrafficPercent()) || body.maxBeforeTrafficPercent() <= 0
                || body.maxBeforeTrafficPercent() > 10 || !Double.isFinite(body.maxTrafficShiftPercent())
                || body.maxTrafficShiftPercent() <= 0 || body.maxTrafficShiftPercent() > 10
                || !Double.isFinite(body.maxCumulativeTrafficShiftPercent())
                || body.maxCumulativeTrafficShiftPercent() < body.maxTrafficShiftPercent()
                || body.maxCumulativeTrafficShiftPercent() > 10
                || body.expiresAtMillis() <= now || body.expiresAtMillis() - now > MAX_LIFETIME) {
            return error(HttpStatus.BAD_REQUEST, "范围、目标、流量上限或有效期不符合预授权边界（最多 7 天、单次影响最多 10%）");
        }
        var route = control.state().routes().stream().filter(candidate -> body.route().equals(candidate.routeId())
                        || body.route().equals(candidate.businessPrefix()))
                .findFirst().orElse(null);
        if (route == null) { return error(HttpStatus.CONFLICT, "当前 Gateway 找不到指定路由"); }
        ResourceRef resource = route.resourceRef();
        if (resource == null || !control.resourceScope().equals(resource.scope())
                || !control.controlTargetId().equals(resource.controlTargetId())
                || !"GATEWAY_ROUTE".equals(resource.kind())) {
            return error(HttpStatus.CONFLICT, "Gateway 未返回当前管理范围内的可信路由身份");
        }
        var target = route.targets().stream().filter(item -> body.serviceName().equals(item.serviceName())
                && body.group().equals(item.group())).findFirst().orElse(null);
        if (target == null || target.weight() <= 0 || route.targets().stream()
                .filter(item -> !item.serviceName().equals(body.serviceName()) || !item.group().equals(body.group()))
                .noneMatch(item -> item.weight() > 0)) {
            return error(HttpStatus.CONFLICT, "目标必须当前承接流量，且路由必须有其他正权重承接版本");
        }
        if (policies.find(body.policyId()).isPresent()) {
            return error(HttpStatus.CONFLICT, "策略 ID 已使用；撤销策略也保留原版本，请使用新 ID 创建");
        }
        AutoExecutionPolicy policy;
        try {
            policy = AutoExecutionPolicy.create(body.policyId(), control.resourceNamespace(), resource,
                    body.serviceName(), body.group(), body.maxBeforeTrafficPercent(),
                    body.maxTrafficShiftPercent(), body.maxCumulativeTrafficShiftPercent(),
                    Duration.ofMinutes(15).toMillis(), body.expiresAtMillis(), actor.getName(), now);
            return ResponseEntity.status(HttpStatus.CREATED).body(policies.save(policy, 0));
        } catch (IllegalStateException ex) {
            return error(HttpStatus.CONFLICT, "策略已被并发修改或无法持久保存");
        }
    }

    @PostMapping("/{policyId}/revoke") public ResponseEntity<?> revoke(@PathVariable String policyId,
            @RequestBody(required = false) RevokeRequest body, Authentication actor) {
        security.require(actor.getName(), Permission.POLICY_ADMIN);
        if (body == null || body.expectedVersion() < 1) {
            return error(HttpStatus.BAD_REQUEST, "expectedVersion 必须是当前策略版本");
        }
        AutoExecutionPolicy current = policies.find(policyId, control.resourceNamespace()).orElse(null);
        if (current == null) { return ResponseEntity.notFound().build(); }
        if (current.version() != body.expectedVersion()) {
            return error(HttpStatus.CONFLICT, "策略版本已变化，请刷新后重试");
        }
        if (!current.enabled()) { return ResponseEntity.ok(current); }
        try {
            return ResponseEntity.ok(policies.save(current.revise(false, current.maxBeforeTrafficPercent(),
                    current.maxTrafficShiftPercent(), current.expiresAtMillis(), actor.getName(),
                    System.currentTimeMillis()), body.expectedVersion()));
        } catch (IllegalStateException ex) {
            return error(HttpStatus.CONFLICT, "策略版本已变化，请刷新后重试");
        }
    }

    @PostMapping("/{policyId}/enable") public ResponseEntity<?> enable(@PathVariable String policyId,
            @RequestBody(required = false) RevokeRequest body, Authentication actor) {
        security.require(actor.getName(), Permission.POLICY_ADMIN);
        if (body == null || body.expectedVersion() < 1) {
            return error(HttpStatus.BAD_REQUEST, "expectedVersion 必须是当前策略版本");
        }
        AutoExecutionPolicy current = policies.find(policyId, control.resourceNamespace()).orElse(null);
        if (current == null) { return ResponseEntity.notFound().build(); }
        if (current.version() != body.expectedVersion()) {
            return error(HttpStatus.CONFLICT, "策略版本已变化，请刷新后重试");
        }
        if (current.expiresAtMillis() <= System.currentTimeMillis()) {
            return error(HttpStatus.CONFLICT, "策略已过期，不能重新启用；请重新创建");
        }
        if (current.enabled()) { return ResponseEntity.ok(current); }
        try {
            return ResponseEntity.ok(policies.save(current.revise(true, current.maxBeforeTrafficPercent(),
                    current.maxTrafficShiftPercent(), current.expiresAtMillis(), actor.getName(),
                    System.currentTimeMillis()), body.expectedVersion()));
        } catch (IllegalStateException ex) {
            return error(HttpStatus.CONFLICT, "策略版本已变化，请刷新后重试");
        }
    }

    public record CreateRequest(String policyId, String route, String serviceName, String group,
            double maxBeforeTrafficPercent, double maxTrafficShiftPercent,
            double maxCumulativeTrafficShiftPercent, long expiresAtMillis) { }
    public record RevokeRequest(long expectedVersion) { }
    public record Candidate(String routeId, String businessPrefix, ResourceRef resource,
            java.util.List<com.rover.agent.core.port.RouteControlTarget> targets) { }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("code", status.name(), "message", message));
    }
}
