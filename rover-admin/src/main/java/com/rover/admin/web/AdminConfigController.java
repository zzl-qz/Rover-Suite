package com.rover.admin.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.rover.admin.service.AdminConfigService;
import com.rover.admin.service.ConfigUpdateResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-12 17:10:00
 * Description: Admin 控制台 JSON API：仪表盘聚合、路由管理、实例查看、配置热更新。
 * 页面为静态 SPA（src/main/resources/static），通过同源 /api/* 调用本控制器。
 */
@RestController
@RequestMapping("/api")
@Slf4j
public class AdminConfigController {

    private final AdminConfigService configService;

    public AdminConfigController(AdminConfigService configService) {
        this.configService = configService;
    }

    /** 仪表盘聚合数据：组件状态 + 发现模式 + 指标快照 + 自洽校验。 */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        CompletableFuture<Map<String, Object>> status =
                CompletableFuture.supplyAsync(configService::loadStatus);
        CompletableFuture<String> discoveryType =
                CompletableFuture.supplyAsync(configService::discoveryType);
        CompletableFuture<JsonNode> metrics =
                CompletableFuture.supplyAsync(() -> safeMetrics(configService::loadMetrics));
        CompletableFuture<JsonNode> selfcheck =
                CompletableFuture.supplyAsync(() -> safeMetrics(configService::loadSelfcheck));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status.join());
        result.put("discoveryType", discoveryType.join());
        result.put("metrics", metrics.join());
        result.put("selfcheck", selfcheck.join());
        return result;
    }

    /** 路由列表。 */
    @GetMapping("/routes")
    public List<Map<String, Object>> routes() {
        return configService.listRoutes();
    }

    /** 新增/更新一条路由，返回更新后的完整路由表响应。 */
    @PostMapping("/routes")
    public Map<String, Object> saveRoute(@RequestBody Map<String, String> route) {
        return configService.saveRoute(route);
    }

    /** 按 businessPrefix 删除路由。 */
    @DeleteMapping("/routes")
    public Map<String, Object> deleteRoute(@RequestParam("businessPrefix") String businessPrefix) {
        return configService.deleteRoute(businessPrefix);
    }

    /** 注册实例列表（Nameserver 管理口）。 */
    @GetMapping("/instances")
    public List<Map<String, Object>> instances() {
        return configService.listInstances();
    }

    /** Nameserver 指标快照（注册概况 + 生命周期计数 + TCP 连接 + JVM）。 */
    @GetMapping("/nameserver/metrics")
    public JsonNode nameserverMetrics() {
        return safeMetrics(() -> configService.loadNameserverMetrics());
    }

    /** Nameserver 最近事件列表。 */
    @GetMapping("/events")
    public List<Map<String, Object>> events() {
        return configService.loadEvents();
    }

    /** Gateway 请求链路时间线，支持 traceId/path/slow 过滤。 */
    @GetMapping("/traces")
    public JsonNode traces(@RequestParam(required = false) String traceId,
                           @RequestParam(required = false) String path,
                           @RequestParam(required = false) String slow) {
        return safeMetrics(() -> configService.loadTraces(Map.of(
                "traceId", nullToEmpty(traceId),
                "path", nullToEmpty(path),
                "slow", nullToEmpty(slow))));
    }

    /** 配置列表（gateway + nameserver 聚合）。 */
    @GetMapping("/configs")
    public List<Map<String, Object>> configs() {
        return configService.listConfigs();
    }

    /** 更新配置：body 形如 {"component":"gateway","key":"...","value":"..."}。 */
    @PostMapping("/configs")
    public Map<String, Object> updateConfig(@RequestBody Map<String, String> body) {
        String component = body.getOrDefault("component", "");
        String key = body.getOrDefault("key", "");
        String value = body.getOrDefault("value", "");
        ConfigUpdateResult result = configService.updateConfig(component, key, value);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("component", component);
        resp.put("key", key);
        resp.put("message", result.getMessage());
        if (result.getPayload() != null) {
            resp.put("payload", result.getPayload());
        }
        return resp;
    }

    /** 包装指标读取：网关旧版本可能没有 metrics 端点，返回结构化错误而非抛异常。 */
    private JsonNode safeMetrics(java.util.function.Supplier<JsonNode> supplier) {
        try {
            return supplier.get();
        } catch (Exception ex) {
            log.warn("Admin 指标聚合读取失败", ex);
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    .put("error", "读取失败");
        }
    }

    /** null 安全转空串。 */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
