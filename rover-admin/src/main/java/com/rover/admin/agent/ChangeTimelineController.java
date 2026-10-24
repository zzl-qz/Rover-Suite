package com.rover.admin.agent;

import com.rover.agent.core.model.*;
import com.rover.agent.core.port.AgentAccess;
import com.rover.agent.core.port.RouteControlPort;
import com.rover.agent.core.timeline.TimelineQuery;
import com.rover.agent.runtime.timeline.ChangeTimelineService;
import com.rover.agent.runtime.timeline.JdbcChangeTimeline;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Event input stores facts only; neither ingestion nor timeline reads can execute a remediation. */
@RestController
@RequestMapping("/api/agent")
public class ChangeTimelineController {
    private final ChangeTimelineService timeline;
    private final RouteControlPort control;
    private final AgentAccess access;
    public ChangeTimelineController(ChangeTimelineService timeline, RouteControlPort control, AgentAccess access) {
        this.timeline = timeline; this.control = control; this.access = access;
    }

    @PostMapping("/releases/ingest")
    public ResponseEntity<?> ingest(@RequestBody ChangeTimelineService.Release request, Authentication actor) {
        if (actor == null) { return ResponseEntity.status(401).build(); }
        // Connector authentication has already enforced RELEASE purpose, expiry, revocation and namespace.
        if (!actor.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_CONNECTOR"))) {
            access.require(actor.getName(), AgentAccess.Permission.INGEST);
        }
        var result = timeline.ingestRelease(actor.getName(), request);
        return ResponseEntity.status(result.duplicate() ? 200 : 201).body(result);
    }

    @GetMapping("/timeline")
    public Object query(@RequestParam TargetType targetType, @RequestParam String target,
                        @RequestParam long fromMillis, @RequestParam long toMillis,
                        @RequestParam(defaultValue = "100") int limit, Authentication actor) {
        access.require(actor.getName(), AgentAccess.Permission.READ);
        ResourceTarget resource = new ResourceTarget(targetType, target);
        if (resource.value().isBlank() || targetType == TargetType.UNKNOWN) { throw new IllegalArgumentException("缺少明确资源目标"); }
        TimeRange range = new TimeRange(fromMillis, toMillis);
        InvestigationContext context = new InvestigationContext(null, null, control.resourceScope(), control.controlTargetId(),
                control.resourceNamespace(), resource, range, Map.of(), "LIVE_RBAC_V1");
        return timeline.query(new TimelineQuery(context, resource, range, limit));
    }

    @ExceptionHandler(JdbcChangeTimeline.Conflict.class)
    public ResponseEntity<?> conflict(JdbcChangeTimeline.Conflict error) {
        return ResponseEntity.status(409).body(Map.of("code", "RELEASE_EVENT_CONFLICT", "message", error.getMessage()));
    }
}
