package com.rover.gateway.core.route;

import com.rover.common.json.ManageJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:55:00
 * Description: Admin 改路由后落盘，重启优先读这份
 */
@Slf4j
public class RouteOverlayStore {

    public static final Path DEFAULT_PATH = Path.of("config", "routes.overlay.json");

    private final Path path;

    public RouteOverlayStore() {
        this(DEFAULT_PATH);
    }

    public RouteOverlayStore(Path path) {
        this.path = path;
    }

    public Path getPath() {
        return path;
    }

    public boolean exists() {
        return Files.exists(path);
    }

    public List<RouteConfig> loadOrEmpty() {
        if (!exists()) {
            return List.of();
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            return toRoutes(ManageJson.parseObjectArray(json));
        } catch (Exception ex) {
            throw new IllegalStateException("读取路由覆盖文件失败: " + path.toAbsolutePath(), ex);
        }
    }

    public void save(List<RouteConfig> routes) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (RouteConfig route : routes) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", nullToEmpty(route.getId()));
                row.put("businessPrefix", nullToEmpty(route.getBusinessPrefix()));
                row.put("targetUrl", nullToEmpty(route.getTargetUrl()));
                row.put("serviceName", nullToEmpty(route.getServiceName()));
                row.put("group", nullToEmpty(route.getGroup()));
                row.put("stripPrefix", nullToEmpty(route.getStripPrefix()));
                rows.add(row);
            }
            Files.writeString(path, ManageJson.arrayOfObjects(rows), StandardCharsets.UTF_8);
            log.info("路由已写入覆盖文件: {}", path.toAbsolutePath());
        } catch (IOException ex) {
            throw new IllegalStateException("写入路由覆盖文件失败: " + path.toAbsolutePath(), ex);
        }
    }

    public static List<RouteConfig> toRoutes(List<Map<String, String>> rows) {
        List<RouteConfig> routes = new ArrayList<>();
        for (Map<String, String> row : rows) {
            RouteConfig route = new RouteConfig();
            route.setId(blankToNull(row.get("id")));
            route.setBusinessPrefix(blankToNull(row.get("businessPrefix")));
            route.setTargetUrl(blankToNull(row.get("targetUrl")));
            route.setServiceName(blankToNull(row.get("serviceName")));
            route.setGroup(blankToNull(row.get("group")));
            route.setStripPrefix(blankToNull(row.get("stripPrefix")));
            routes.add(route);
        }
        return routes;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
