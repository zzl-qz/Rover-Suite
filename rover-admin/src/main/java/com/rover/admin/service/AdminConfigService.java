package com.rover.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.rover.admin.client.ManageHttpClient;
import com.rover.admin.config.AdminProperties;
import com.rover.common.config.ConfigApplyMode;
import com.rover.common.constants.ManageApiPaths;
import com.rover.common.constants.RoverComponent;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Author: Daylight
 * Created: 2026-08-11 14:08:00
 * Description: 通过 HTTP 聚合 Gateway / Nameserver 管理口数据的服务层
 */
@Service
@Slf4j
public class AdminConfigService {

    /** Gateway 状态接口缺失 discoveryType 字段时的默认展示值 */
    private static final String DISCOVERY_TYPE_STATIC = "STATIC";
    /** 无法获取状态时展示为未知 */
    private static final String DISCOVERY_TYPE_UNKNOWN = "UNKNOWN";

    private final ManageHttpClient httpClient;
    private final AdminProperties properties;

    public AdminConfigService(ManageHttpClient httpClient, AdminProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    public Map<String, Object> loadStatus() {
        CompletableFuture<Map<String, Object>> gateway = CompletableFuture.supplyAsync(
                () -> fetchStatus(properties.getGatewayUrl(), RoverComponent.GATEWAY));
        CompletableFuture<Map<String, Object>> nameserver = CompletableFuture.supplyAsync(
                () -> fetchStatus(properties.getNameserverManageUrl(), RoverComponent.NAMESERVER));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(RoverComponent.GATEWAY.id(), gateway.join());
        result.put(RoverComponent.NAMESERVER.id(), nameserver.join());
        return result;
    }

    public String discoveryType() {
        try {
            JsonNode status = httpClient.getJson(properties.getGatewayUrl(), ManageApiPaths.STATUS);
            return status.path("discoveryType").asText(DISCOVERY_TYPE_STATIC);
        } catch (Exception ex) {
            log.warn("读取 Gateway discoveryType 失败", ex);
            return DISCOVERY_TYPE_UNKNOWN;
        }
    }

    public List<Map<String, Object>> listRoutes() {
        try {
            return httpClient.getList(properties.getGatewayUrl(), ManageApiPaths.ROUTES);
        } catch (Exception ex) {
            throw new IllegalStateException("读取 Gateway 路由失败: " + ex.getMessage(), ex);
        }
    }

    public Map<String, Object> saveRoute(Map<String, String> route) {
        try {
            return httpClient.postJson(properties.getGatewayUrl(), ManageApiPaths.ROUTES, route);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex.getMessage() == null ? "保存路由失败" : ex.getMessage(), ex);
        }
    }

    public Map<String, Object> deleteRoute(String idOrPrefix) {
        try {
            String encoded = URLEncoder.encode(idOrPrefix, StandardCharsets.UTF_8);
            return httpClient.delete(
                    properties.getGatewayUrl(),
                    ManageApiPaths.ROUTES + "?" + ManageApiPaths.PARAM_BUSINESS_PREFIX + "=" + encoded);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex.getMessage() == null ? "删除路由失败" : ex.getMessage(), ex);
        }
    }

    public List<Map<String, Object>> listInstances() {
        try {
            return httpClient.getList(properties.getNameserverManageUrl(), ManageApiPaths.INSTANCES);
        } catch (Exception ex) {
            throw new IllegalStateException("读取 Nameserver 实例失败: " + ex.getMessage(), ex);
        }
    }

    public List<Map<String, Object>> listConfigs() {
        List<Map<String, Object>> items = new ArrayList<>();
        items.addAll(fetchConfigs(RoverComponent.GATEWAY, properties.getGatewayUrl()));
        items.addAll(fetchConfigs(RoverComponent.NAMESERVER, properties.getNameserverManageUrl()));
        return items;
    }

    /** 读取 Gateway / Nameserver 轻量实时快照，给仪表盘 1 秒轮询。 */
    public Map<String, Object> loadLive(int rangeSeconds) {
        int range = ManageApiPaths.clampLiveRange(String.valueOf(rangeSeconds));
        String query = "?" + ManageApiPaths.PARAM_RANGE + "=" + range;
        CompletableFuture<JsonNode> gateway = CompletableFuture.supplyAsync(
                () -> safeLive(properties.getGatewayUrl(), ManageApiPaths.METRICS_LIVE + query));
        CompletableFuture<JsonNode> nameserver = CompletableFuture.supplyAsync(
                () -> safeLive(properties.getNameserverManageUrl(), ManageApiPaths.METRICS_LIVE + query));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serverTimeMillis", System.currentTimeMillis());
        result.put("rangeSeconds", range);
        result.put(RoverComponent.GATEWAY.id(), gateway.join());
        result.put(RoverComponent.NAMESERVER.id(), nameserver.join());
        return result;
    }

    private JsonNode safeLive(String baseUrl, String path) {
        try {
            return httpClient.getJson(baseUrl, path);
        } catch (Exception ex) {
            log.warn("读取 live 指标失败: url={} path={}", baseUrl, path, ex);
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    .put("error", "读取失败")
                    .put("reachable", false);
        }
    }

    /** 读取 Gateway 指标快照（/api/metrics）。 */
    public JsonNode loadMetrics() {
        try {
            return httpClient.getJson(properties.getGatewayUrl(), ManageApiPaths.METRICS);
        } catch (Exception ex) {
            throw new IllegalStateException("读取 Gateway 指标失败: " + ex.getMessage(), ex);
        }
    }

    /** 读取 Gateway 指标自洽校验结果（/api/selfcheck）。 */
    public JsonNode loadSelfcheck() {
        try {
            return httpClient.getJson(properties.getGatewayUrl(), ManageApiPaths.METRICS_SELFCHECK);
        } catch (Exception ex) {
            throw new IllegalStateException("读取指标自洽校验失败: " + ex.getMessage(), ex);
        }
    }

    /** 读取 Nameserver 指标快照（注册概况 + 生命周期计数 + TCP 连接 + JVM）。 */
    public JsonNode loadNameserverMetrics() {
        try {
            return httpClient.getJson(properties.getNameserverManageUrl(), ManageApiPaths.METRICS);
        } catch (Exception ex) {
            throw new IllegalStateException("读取 Nameserver 指标失败: " + ex.getMessage(), ex);
        }
    }

    /** 读取 Nameserver 最近事件列表（注册/注销/剔除/标不健康/推送）。 */
    public List<Map<String, Object>> loadEvents() {
        try {
            return httpClient.getList(properties.getNameserverManageUrl(), ManageApiPaths.EVENTS);
        } catch (Exception ex) {
            throw new IllegalStateException("读取 Nameserver 事件失败: " + ex.getMessage(), ex);
        }
    }

    /** 读取 Gateway 请求链路时间线（支持 traceId/path/slow 过滤）。 */
    public JsonNode loadTraces(Map<String, String> params) {
        try {
            StringBuilder path = new StringBuilder(ManageApiPaths.TRACES);
            List<String> query = new ArrayList<>();
            params.forEach((key, value) -> {
                if (value != null && !value.isBlank()) {
                    query.add(key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
                }
            });
            if (!query.isEmpty()) {
                path.append('?').append(String.join("&", query));
            }
            return httpClient.getJson(properties.getGatewayUrl(), path.toString());
        } catch (Exception ex) {
            throw new IllegalStateException("读取请求链路失败: " + ex.getMessage(), ex);
        }
    }

    public ConfigUpdateResult updateConfig(String component, String key, String value) {
        String baseUrl = resolveBaseUrl(component);
        try {
            Map<String, Object> payload = httpClient.postConfig(baseUrl, key, value);
            String message = payload.get("message") == null
                    ? "已提交"
                    : String.valueOf(payload.get("message"));
            return new ConfigUpdateResult(component, payload, message);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex.getMessage() == null ? "更新失败" : ex.getMessage(), ex);
        }
    }

    private Map<String, Object> fetchStatus(String baseUrl, RoverComponent component) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("label", component.displayName());
        status.put("baseUrl", baseUrl);
        try {
            JsonNode node = httpClient.getJson(baseUrl, ManageApiPaths.STATUS);
            status.put("reachable", true);
            status.put("data", node);
            status.put("error", "");
        } catch (Exception ex) {
            log.warn("读取组件状态失败: component={}, baseUrl={}", component.id(), baseUrl, ex);
            status.put("reachable", false);
            status.put("data", null);
            status.put("error", "不可达");
        }
        return status;
    }

    private List<Map<String, Object>> fetchConfigs(RoverComponent component, String baseUrl) {
        try {
            List<Map<String, Object>> configs = httpClient.getList(baseUrl, ManageApiPaths.CONFIGS);
            for (Map<String, Object> item : configs) {
                item.put("component", component.id());
            }
            return configs;
        } catch (Exception ex) {
            log.warn("读取配置列表失败: component={}", component.id(), ex);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("component", component.id());
            error.put("key", component.id() + ".*");
            error.put("description", "读取失败");
            error.put("value", "");
            error.put("defaultValue", "");
            error.put("applyMode", ConfigApplyMode.HOT_RELOAD.name());
            error.put("hotReloadable", false);
            error.put("error", true);
            return List.of(error);
        }
    }

    private String resolveBaseUrl(String component) {
        return switch (RoverComponent.from(component)) {
            case GATEWAY -> properties.getGatewayUrl();
            case NAMESERVER -> properties.getNameserverManageUrl();
        };
    }
}
