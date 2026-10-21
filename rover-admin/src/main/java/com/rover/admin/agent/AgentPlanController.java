package com.rover.admin.agent;

import com.rover.agent.core.model.RemediationPlan;
import com.rover.agent.core.model.WeightRequestUnit;
import com.rover.agent.runtime.action.ActionRequestException;
import com.rover.agent.runtime.action.AgentActionService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 读取不可变计划版本；修改目标值只能根据服务端当前路由重新生成待批版本。 */
@RestController
@RequestMapping("/api/agent/v1/plans")
public class AgentPlanController {
    private static final Set<String> REVISION_FIELDS = Set.of(
            "expectedVersion", "expectedPlanHash", "requestedValue", "requestedUnit");
    private final AgentActionService actions;

    public AgentPlanController(AgentActionService actions) { this.actions = actions; }

    @GetMapping("/{planId}/versions")
    public ResponseEntity<List<RemediationPlan>> versions(@PathVariable String planId, Authentication actor) {
        return actions.planVersions(planId, user(actor)).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{planId}/versions/{version}")
    public ResponseEntity<?> version(@PathVariable String planId, @PathVariable int version, Authentication actor) {
        return apply(() -> {
            if (version < 1) { throw invalid("计划版本必须是正整数"); }
            return actions.plan(planId, version, user(actor)).map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        });
    }

    @PostMapping("/{planId}/versions")
    public ResponseEntity<?> revise(@PathVariable String planId,
                                    @RequestBody(required = false) Map<String, Object> body,
                                    Authentication actor) {
        return apply(() -> {
            if (body == null || !body.keySet().equals(REVISION_FIELDS)) {
                throw invalid("必须且只能提交 expectedVersion、expectedPlanHash、requestedValue 和 requestedUnit");
            }
            if (!(body.get("expectedVersion") instanceof Integer version) || version < 1) {
                throw invalid("expectedVersion 必须是正整数");
            }
            if (!(body.get("expectedPlanHash") instanceof String hash) || !hash.matches("[0-9a-f]{64}")) {
                throw invalid("expectedPlanHash 必须是页面中待修改计划的完整摘要");
            }
            if (!(body.get("requestedValue") instanceof Integer value)) {
                throw invalid("requestedValue 必须是整数");
            }
            WeightRequestUnit unit;
            if ("RAW_WEIGHT".equals(body.get("requestedUnit"))) { unit = WeightRequestUnit.RAW_WEIGHT; }
            else if ("TRAFFIC_PERCENT".equals(body.get("requestedUnit"))) { unit = WeightRequestUnit.TRAFFIC_PERCENT; }
            else { throw invalid("requestedUnit 必须明确为 RAW_WEIGHT 或 TRAFFIC_PERCENT"); }
            if (value < 0 || value > (unit == WeightRequestUnit.RAW_WEIGHT ? 10_000 : 100)) {
                throw invalid("目标值超出所选单位允许的范围");
            }
            return ResponseEntity.ok(actions.revisePlan(planId, version, hash, value, unit, user(actor)));
        });
    }

    private static ResponseEntity<?> apply(Supplier<ResponseEntity<?>> operation) {
        try { return operation.get(); }
        catch (ActionRequestException ex) {
            HttpStatus status = switch (ex.code()) {
                case NOT_FOUND -> HttpStatus.NOT_FOUND;
                case CONFLICT -> HttpStatus.CONFLICT;
                case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            };
            return ResponseEntity.status(status).body(Map.of("code", ex.code().name(), "message", ex.getMessage()));
        }
    }

    private static ActionRequestException invalid(String message) {
        return new ActionRequestException(ActionRequestException.Code.INVALID_REQUEST, message);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> invalidJsonOrVersion(Exception ignored) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", "计划版本或 JSON 请求格式不合法"));
    }

    private static String user(Authentication actor) {
        return actor == null || !actor.isAuthenticated() || actor instanceof AnonymousAuthenticationToken
                ? null : actor.getName();
    }
}
