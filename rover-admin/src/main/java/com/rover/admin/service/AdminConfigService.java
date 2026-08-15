package com.rover.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.rover.admin.client.ManageHttpClient;
import com.rover.admin.config.AdminProperties;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Author: Daylight
 * Created: 2026-08-11 14:08:00
 * Description: 通过 HTTP 聚合 Gateway / Nameserver 管理口数据的服务层
 */
@Service
public class AdminConfigService {

    private final ManageHttpClient httpClient;
    private final AdminProperties properties;

    public AdminConfigService(ManageHttpClient httpClient, AdminProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    public Map<String, Object> loadStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gateway", fetchStatus(properties.getGatewayUrl(), "Gateway"));
        result.put("nameserver", fetchStatus(properties.getNameserverManageUrl(), "Nameserver"));
        return result;
    }

    public String discoveryType() {
        try {
            JsonNode status = httpClient.getJson(properties.getGatewayUrl(), "/_manage/status");
            return status.path("discoveryType").asText("STATIC");
        } catch (Exception ex) {
            return "UNKNOWN";
        }
    }

    public List<Map<String, Object>> listRoutes() {
        try {
            return httpClient.getList(properties.getGatewayUrl(), "/_manage/routes");
        } catch (Exception ex) {
            throw new IllegalStateException("读取 Gateway 路由失败: " + ex.getMessage(), ex);
        }
    }

    public Map<String, Object> saveRoute(Map<String, String> route) {
        try {
            return httpClient.postJson(properties.getGatewayUrl(), "/_manage/routes", route);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex.getMessage() == null ? "保存路由失败" : ex.getMessage(), ex);
        }
    }

    public Map<String, Object> deleteRoute(String idOrPrefix) {
        try {
            String encoded = URLEncoder.encode(idOrPrefix, StandardCharsets.UTF_8);
            return httpClient.delete(
                    properties.getGatewayUrl(),
                    "/_manage/routes?businessPrefix=" + encoded);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex.getMessage() == null ? "删除路由失败" : ex.getMessage(), ex);
        }
    }

    public List<Map<String, Object>> listInstances() {
        try {
            return httpClient.getList(properties.getNameserverManageUrl(), "/_manage/instances");
        } catch (Exception ex) {
            throw new IllegalStateException("读取 Nameserver 实例失败: " + ex.getMessage(), ex);
        }
    }

    public List<Map<String, Object>> listConfigs() {
        List<Map<String, Object>> items = new ArrayList<>();
        items.addAll(fetchConfigs("gateway", properties.getGatewayUrl()));
        items.addAll(fetchConfigs("nameserver", properties.getNameserverManageUrl()));
        return items;
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

    private Map<String, Object> fetchStatus(String baseUrl, String label) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("label", label);
        status.put("baseUrl", baseUrl);
        try {
            JsonNode node = httpClient.getJson(baseUrl, "/_manage/status");
            status.put("reachable", true);
            status.put("data", node);
            status.put("error", "");
        } catch (Exception ex) {
            status.put("reachable", false);
            status.put("data", null);
            status.put("error", ex.getMessage() == null ? "不可达" : ex.getMessage());
        }
        return status;
    }

    private List<Map<String, Object>> fetchConfigs(String component, String baseUrl) {
        try {
            List<Map<String, Object>> configs = httpClient.getList(baseUrl, "/_manage/configs");
            for (Map<String, Object> item : configs) {
                item.put("component", component);
            }
            return configs;
        } catch (Exception ex) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("component", component);
            error.put("key", component + ".*");
            error.put("description", "读取失败: " + ex.getMessage());
            error.put("value", "");
            error.put("defaultValue", "");
            error.put("applyMode", "HOT_RELOAD");
            error.put("hotReloadable", false);
            error.put("error", true);
            return List.of(error);
        }
    }

    private String resolveBaseUrl(String component) {
        if ("gateway".equalsIgnoreCase(component)) {
            return properties.getGatewayUrl();
        }
        if ("nameserver".equalsIgnoreCase(component)) {
            return properties.getNameserverManageUrl();
        }
        throw new IllegalArgumentException("未知组件: " + component);
    }
}
