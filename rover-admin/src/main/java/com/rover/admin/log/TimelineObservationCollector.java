package com.rover.admin.log;

import com.rover.admin.service.AdminConfigService;
import com.rover.agent.core.timeline.ChangeEvent.Subject;
import com.rover.agent.runtime.timeline.ChangeTimelineService;
import com.rover.agent.runtime.timeline.JdbcChangeTimeline.Observation;
import java.util.*;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reuses TelemetryCollector's scheduler; each source succeeds or fails independently. */
final class TimelineObservationCollector {
    private static final Logger log = LoggerFactory.getLogger(TimelineObservationCollector.class);
    private static final Set<String> SAFE_CONFIG_KEYS = Set.of(
            "gateway.loadbalance.strategy", "gateway.request.timeoutMillis", "gateway.metrics.enabled", "gateway.metrics.windowSeconds",
            "gateway.filter.enabled", "gateway.rateLimit.enabled", "gateway.rateLimit.algorithm", "gateway.rateLimit.key",
            "gateway.rateLimit.permitsPerSecond", "gateway.rateLimit.burst", "gateway.rateLimit.limit", "gateway.rateLimit.windowSeconds",
            "gateway.circuitBreaker.enabled", "gateway.circuitBreaker.failureThreshold", "gateway.circuitBreaker.openSeconds",
            "gateway.circuitBreaker.recovery", "gateway.retry.enabled", "gateway.trace.enabled", "gateway.trace.slowThresholdMillis",
            "gateway.trace.sampleRate", "nameserver.health.checkIntervalMillis", "nameserver.heartbeat.timeoutMillis",
            "nameserver.instance.expireMillis", "nameserver.push.enabled");
    private final AdminConfigService admin;
    private final ChangeTimelineService timeline;
    TimelineObservationCollector(AdminConfigService admin, ChangeTimelineService timeline) { this.admin = admin; this.timeline = timeline; }

    void collect() {
        collect("rover.routes", this::routes);
        collect("rover.instances", this::instances);
        List<Map<String, Object>> configs;
        try { configs = admin.listConfigs(); }
        catch (RuntimeException error) { markUnavailable("rover.config.gateway"); markUnavailable("rover.config.nameserver"); return; }
        for (String component : List.of("gateway", "nameserver")) {
            collect("rover.config." + component, () -> configs(component, configs));
        }
    }
    private void collect(String source, Supplier<Map<String, Observation>> pull) {
        try { timeline.observe(source, pull.get()); }
        catch (RuntimeException error) { log.warn("时间线采集失败 source={}", source); markUnavailable(source); }
    }
    private void markUnavailable(String source) {
        try { timeline.unavailable(source); }
        catch (RuntimeException error) { log.error("无法持久化时间线采集空档 source={}", source); }
    }

    private Map<String, Observation> routes() {
        Map<String, Object> state = admin.routesState();
        if (!(state.get("routes") instanceof List<?> rows)) { throw new IllegalArgumentException("路由快照缺失"); }
        Map<String, Observation> result = new TreeMap<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> route)) { throw new IllegalArgumentException("路由行不合法"); }
            String id = required(route.get("id")), prefix = required(route.get("businessPrefix"));
            List<Map<String, Object>> targets = new ArrayList<>();
            Set<String> services = new TreeSet<>();
            if (route.get("targets") instanceof List<?> rawTargets) {
                for (Object value : rawTargets) {
                    if (!(value instanceof Map<?, ?> target)) { throw new IllegalArgumentException("目标不合法"); }
                    String service = required(target.get("serviceName")); services.add(service);
                    if (!(target.get("weight") instanceof Number weight) || weight.doubleValue() != weight.longValue() || weight.longValue() < 0) {
                        throw new IllegalArgumentException("版本权重缺失或不合法");
                    }
                    targets.add(Map.of("serviceName", service, "group", text(target.get("group")), "weight", weight));
                }
            }
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("services", List.copyOf(services)); values.put("targets", targets);
            values.put("resourceUid", text(route.get("resourceUid")));
            values.put("generation", number(route.get("generation")));
            // Target order affects sticky traffic assignment; preserve source order instead of sorting it away.
            values.put("stripPrefix", route.get("stripPrefix")); values.put("stickyHeader", route.get("stickyHeader"));
            if (route.containsKey("timeoutMillis")) { values.put("timeoutMillis", route.get("timeoutMillis")); }
            values.put("targetUrl", safeUrl(route.get("targetUrl"))); values.put("targetUrls", safeUrls(route.get("targetUrls")));
            Subject subject = new Subject(id, prefix, text(route.get("resourceUid")), number(route.get("generation")), List.copyOf(services), null, null, null, null);
            String revision = state.get("revision") == null ? null : String.valueOf(state.get("revision"));
            String identity = com.rover.common.json.JsonCodec.toJson(List.of(id, prefix, subject.routeUid()));
            if (result.put(identity, new Observation(subject, values, revision)) != null) { throw new IllegalArgumentException("重复路由身份"); }
        }
        return result;
    }
    private Map<String, Observation> instances() {
        List<Map<String, Object>> rows = admin.listInstances();
        if (rows == null) { throw new IllegalArgumentException("实例快照缺失"); }
        Map<String, Observation> result = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            String id = required(row.get("instanceId")), service = required(row.get("serviceName"));
            String host = required(row.get("host")); long port = number(row.get("port"));
            if (port < 1 || port > 65535 || !(row.get("healthy") instanceof Boolean)) { throw new IllegalArgumentException("实例状态不合法"); }
            String address = (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
            String group = text(row.get("group"));
            Subject subject = new Subject(null, null, null, 0, List.of(service), group, id, address, null);
            String key = com.rover.common.json.JsonCodec.toJson(List.of(service, group, id));
            Map<String, Object> values = new LinkedHashMap<>(Map.of("healthy", row.get("healthy"), "services", List.of(service), "group", group, "hostPort", address));
            if (row.containsKey("weight")) { values.put("weight", row.get("weight")); }
            if (row.containsKey("ephemeral")) { values.put("ephemeral", row.get("ephemeral")); }
            if (result.put(key, new Observation(subject, values)) != null) {
                throw new IllegalArgumentException("重复实例身份");
            }
        }
        return result;
    }
    private Map<String, Observation> configs(String component, List<Map<String, Object>> rows) {
        Map<String, Observation> result = new TreeMap<>();
        boolean found = false;
        for (Map<String, Object> row : rows) {
            if (!component.equals(row.get("component"))) { continue; }
            found = true;
            if (Boolean.TRUE.equals(row.get("error"))) { throw new IllegalArgumentException("配置读取失败"); }
            String key = required(row.get("key"));
            // Only built-in numeric/boolean/enum settings; plugin properties can contain arbitrary secrets.
            if (!SAFE_CONFIG_KEYS.contains(key)) { continue; }
            String value = text(row.get("value"));
            if (value.length() > 128 || !value.matches("[A-Za-z0-9_.+-]*")) { throw new IllegalArgumentException("内置配置值格式不合法"); }
            Subject subject = new Subject(null, null, null, 0, List.of(), null, null, null, component);
            if (result.put(key, new Observation(subject, Map.of("key", key, "value", value, "applyMode", text(row.get("applyMode")), "globalScope", true))) != null) {
                throw new IllegalArgumentException("重复配置项");
            }
        }
        if (!found) { throw new IllegalArgumentException("组件配置快照缺失"); }
        return result;
    }
    private static String safeUrl(Object value) {
        String text = text(value);
        if (text.isBlank()) { return ""; }
        try {
            java.net.URI uri = java.net.URI.create(text);
            return new java.net.URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (Exception error) { return "[REDACTED_INVALID_URL]"; }
    }
    private static List<String> safeUrls(Object value) {
        if (value instanceof List<?> list) { return list.stream().map(TimelineObservationCollector::safeUrl).toList(); }
        return text(value).isBlank() ? List.of() : Arrays.stream(text(value).split(",")).map(TimelineObservationCollector::safeUrl).toList();
    }
    private static String required(Object value) {
        String text = text(value);
        if (text.isBlank() || text.length() > 255) { throw new IllegalArgumentException("缺少资源身份"); }
        return text;
    }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static long number(Object value) { return value instanceof Number n ? n.longValue() : 0; }
}
