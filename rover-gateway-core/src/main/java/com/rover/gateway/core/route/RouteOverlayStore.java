package com.rover.gateway.core.route;

import com.rover.common.json.JsonCodec;
import com.rover.common.config.ConfigFiles;
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
 * Description: 路由热更新本地持久化：Admin 改路由后落盘，重启优先读 overlay
 */
@Slf4j
public class RouteOverlayStore {

    /** 默认 overlay 文件路径：config/routes.overlay.json */
    public static final Path DEFAULT_PATH = ConfigFiles.ROUTES_OVERLAY;

    /** overlay 文件路径。 */
    private final Path path;

    /** 使用默认路径构造。 */
    public RouteOverlayStore() {
        this(DEFAULT_PATH);
    }

    /**
     * @param path overlay 文件路径
     */
    public RouteOverlayStore(Path path) {
        this.path = path;
    }

    /** @return overlay 文件路径 */
    public Path getPath() {
        return path;
    }

    /** @return overlay 文件是否存在 */
    public boolean exists() {
        return Files.exists(path);
    }

    /** 读取 overlay 文件，不存在或为空时返回空列表。 */
    public List<RouteConfig> loadOrEmpty() {
        if (!exists()) {
            return List.of();
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            return toRoutes(JsonCodec.parseStringMapArray(json));
        } catch (Exception ex) {
            throw new IllegalStateException("读取路由覆盖文件失败: " + path.toAbsolutePath(), ex);
        }
    }

    /**
     * 把路由列表写入 overlay 文件，自动创建父目录。
     *
     * @param routes 要持久化的路由列表
     * @throws IllegalStateException 写入失败
     */
    public void save(List<RouteConfig> routes) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (RouteConfig route : routes) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put(RouteField.ID.jsonName(), nullToEmpty(route.getId()));
                row.put(RouteField.BUSINESS_PREFIX.jsonName(), nullToEmpty(route.getBusinessPrefix()));
                row.put(RouteField.TARGET_URL.jsonName(), nullToEmpty(route.getTargetUrl()));
                row.put(RouteField.TARGET_URLS.jsonName(), joinTargetUrls(route.getTargetUrls()));
                row.put(RouteField.SERVICE_NAME.jsonName(), nullToEmpty(route.getServiceName()));
                row.put(RouteField.GROUP.jsonName(), nullToEmpty(route.getGroup()));
                row.put(RouteField.STRIP_PREFIX.jsonName(), nullToEmpty(route.getStripPrefix()));
                rows.add(row);
            }
            // 落盘用缩进 JSON，方便人眼看；解析不挑格式
            Files.writeString(path, JsonCodec.toPrettyJson(rows), StandardCharsets.UTF_8);
            log.info("路由已写入覆盖文件: {}", path.toAbsolutePath());
        } catch (IOException ex) {
            throw new IllegalStateException("写入路由覆盖文件失败: " + path.toAbsolutePath(), ex);
        }
    }

    /** 把 JSON 行列表转成 RouteConfig 列表。 */
    public static List<RouteConfig> toRoutes(List<Map<String, String>> rows) {
        List<RouteConfig> routes = new ArrayList<>();
        for (Map<String, String> row : rows) {
            RouteConfig route = new RouteConfig();
            route.setId(blankToNull(row.get(RouteField.ID.jsonName())));
            route.setBusinessPrefix(blankToNull(row.get(RouteField.BUSINESS_PREFIX.jsonName())));
            route.setTargetUrl(blankToNull(row.get(RouteField.TARGET_URL.jsonName())));
            route.setTargetUrls(splitTargetUrls(row.get(RouteField.TARGET_URLS.jsonName())));
            route.setServiceName(blankToNull(row.get(RouteField.SERVICE_NAME.jsonName())));
            route.setGroup(blankToNull(row.get(RouteField.GROUP.jsonName())));
            route.setStripPrefix(blankToNull(row.get(RouteField.STRIP_PREFIX.jsonName())));
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

    /** overlay 里用逗号拼接多上游（URL 里本身不含逗号）。 */
    private static String joinTargetUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return "";
        }
        return String.join(",", urls);
    }

    private static List<String> splitTargetUrls(String raw) {
        List<String> urls = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return urls;
        }
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                urls.add(trimmed);
            }
        }
        return urls;
    }
}
