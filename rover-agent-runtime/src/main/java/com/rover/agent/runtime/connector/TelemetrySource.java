package com.rover.agent.runtime.connector;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.model.ResourceScope;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TargetType;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 由宿主配置绑定的只读源；不通过模型或公开写接口创建。 */
public record TelemetrySource(String id, Kind kind, URI endpoint, Set<String> allowedAddresses, boolean allowHttp,
                              ResourceScope scope, String controlTargetId, String namespace, String tenantId,
                              String bearerToken, Map<String, String> labels, List<String> metrics,
                              List<Binding> bindings) {
    public enum Kind {
        PROMETHEUS(AgentCapability.APPLICATION_METRICS_QUERY),
        LOKI(AgentCapability.APPLICATION_LOG_QUERY), TEMPO(AgentCapability.APPLICATION_TRACE_QUERY);
        private final AgentCapability capability;
        Kind(AgentCapability capability) { this.capability = capability; }
        public AgentCapability capability() { return capability; }
    }

    public record Binding(TargetType targetType, String target, String service) {
        public Binding {
            if (targetType == null || targetType == TargetType.UNKNOWN || !safe(target) || !safe(service)) {
                throw new IllegalArgumentException("遥测资源映射必须包含明确目标与服务");
            }
        }
        boolean matches(ResourceTarget resource) {
            return resource != null && resource.type() == targetType && (target.equals(resource.value())
                    || targetType == TargetType.ROUTE && resource.value().startsWith(target.endsWith("/") ? target : target + "/"));
        }
    }

    public TelemetrySource {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,64}") || kind == null || endpoint == null
                || scope == null || !safe(controlTargetId) || !safe(namespace)) {
            throw new IllegalArgumentException("遥测数据源标识、范围与管理目标必须明确");
        }
        if (!("https".equals(endpoint.getScheme()) || allowHttp && "http".equals(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null || !(endpoint.getRawPath() == null || endpoint.getRawPath().isEmpty()
                || endpoint.getRawPath().equals("/"))) {
            throw new IllegalArgumentException("遥测地址只允许明确的 HTTPS origin；HTTP 需显式开启，禁止凭证、路径和查询参数");
        }
        allowedAddresses = allowedAddresses == null ? Set.of() : Set.copyOf(allowedAddresses);
        if (allowedAddresses.isEmpty() || allowedAddresses.size() > 16) {
            throw new IllegalArgumentException("遥测源必须配置 1～16 个明确允许的 IP 地址");
        }
        allowedAddresses.forEach(SafeTelemetryHttp::literalAddress);
        // 元数据、链路本地、组播和未指定地址无论是否列入白名单都不可达。
        var permittedAddresses = allowedAddresses;
        allowedAddresses.forEach(address -> SafeTelemetryHttp.validateAddress(SafeTelemetryHttp.literalAddress(address), permittedAddresses));
        tenantId = tenantId == null ? "" : tenantId;
        bearerToken = bearerToken == null ? "" : bearerToken;
        if (!tenantId.isEmpty() && !tenantId.matches("[a-zA-Z0-9_.-]{1,128}") || bearerToken.contains("\r")
                || bearerToken.contains("\n") || bearerToken.length() > 4096) {
            throw new IllegalArgumentException("遥测凭证或单租户标识格式无效");
        }
        Map<String, String> defaults = Map.of("organization", "organization_id", "team", "team_id",
                "environment", "environment", "namespace", "namespace", "service", kind == Kind.TEMPO ? "service.name" : "service_name");
        labels = labels == null || labels.isEmpty() ? defaults : Map.copyOf(labels);
        if (!labels.keySet().equals(defaults.keySet()) || labels.values().stream().distinct().count() != 5
                || labels.values().stream().anyMatch(v -> !v.matches(kind == Kind.TEMPO ? "[a-zA-Z_][a-zA-Z0-9_.]*" : "[a-zA-Z_][a-zA-Z0-9_]*"))) {
            throw new IllegalArgumentException("遥测范围标签须完整且不可重名");
        }
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        if (kind == Kind.PROMETHEUS && (metrics.isEmpty() || metrics.size() > 8
                || metrics.stream().anyMatch(m -> !m.matches("[a-zA-Z_:][a-zA-Z0-9_:]{0,127}")))) {
            throw new IllegalArgumentException("Prometheus 必须配置 1～8 个明确允许的指标名");
        }
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        if (bindings.isEmpty() || bindings.size() > 256 || bindings.stream().map(b -> b.targetType() + ":" + b.target()).distinct().count() != bindings.size()) {
            throw new IllegalArgumentException("遥测源必须配置无冲突的资源映射");
        }
    }

    public String service(ResourceTarget target) {
        return bindings.stream().filter(b -> b.matches(target)).max(java.util.Comparator.comparingInt(b -> b.target().length()))
                .map(Binding::service).orElse(null);
    }

    private static boolean safe(String value) {
        return value != null && !value.isBlank() && value.length() <= 256 && value.chars().noneMatch(Character::isISOControl);
    }

    /** 防止日志/错误处理意外输出 record 中的凭证和内部地址。 */
    @Override public String toString() { return "TelemetrySource[id=" + id + ",kind=" + kind + "]"; }
}
