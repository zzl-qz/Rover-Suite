package com.rover.admin.agent;

import com.rover.agent.core.model.ResourceRef;
import com.rover.agent.core.model.ResourceScope;
import com.rover.agent.core.port.RouteControlPort;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 只读资产清单；范围和管理目标来自服务端配置，客户端不能传 URL 切换目标。 */
@RestController
@RequestMapping("/api/agent/assets")
public class AgentAssetController {
    private final RouteControlPort control;

    public AgentAssetController(RouteControlPort control) { this.control = control; }

    @GetMapping
    public Inventory list() {
        var state = control.state();
        List<ResourceRef> resources = state.routes().stream().map(route -> route.resourceRef())
                .filter(java.util.Objects::nonNull).toList();
        return new Inventory(control.resourceScope(), state.revision(), resources,
                resources.size() == state.routes().size());
    }

    public record Inventory(ResourceScope scope, int revision, List<ResourceRef> resources, boolean identityComplete) { }
}
