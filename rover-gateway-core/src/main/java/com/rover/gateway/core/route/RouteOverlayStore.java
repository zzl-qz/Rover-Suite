package com.rover.gateway.core.route;

import com.rover.common.config.ConfigFiles;
import com.rover.common.json.JsonCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:55:00
 * Description: 路由热更新本地持久化：Admin 改路由后落盘，重启优先读 overlay
 *
 * 文件带版本元信息（{@code revision} / {@code appliedOperationId}），于是「重启后我报的版本」
 * 与「重启前确认过的版本」是同一个值——调用方超时重试、Agent 判断是否已生效都以此为准。
 *
 * 写盘走「同目录临时文件 + 原子改名」：半截文件不会被读到，进程在写盘中途挂掉时
 * 磁盘上要么是旧版本、要么是新版本，不存在中间态。
 */
@Slf4j
public class RouteOverlayStore {

    /** 默认 overlay 文件路径：config/routes.overlay.json */
    public static final Path DEFAULT_PATH = ConfigFiles.ROUTES_OVERLAY;

    /** overlay 文件字段名。 */
    private static final String KEY_REVISION = "revision";
    private static final String KEY_OPERATION_ID = "appliedOperationId";
    private static final String KEY_APPLIED_AT = "appliedAtMillis";
    private static final String KEY_ROUTES = "routes";

    /** 临时文件名后缀，落在同目录以保证同文件系统内可原子改名。 */
    private static final String TEMP_SUFFIX = ".tmp";

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

    /**
     * 已落盘的确认状态：版本号 + 产生它的操作 + 生效时刻 + 路由表。
     *
     * @param revision          已确认版本号，从 0 开始
     * @param appliedOperationId 产生该版本的操作 ID；空表示不是通过管理口写入的
     * @param appliedAtMillis   该版本落盘时刻；0 表示无记录
     * @param routes            该版本的路由表
     */
    public record AppliedState(int revision, String appliedOperationId, long appliedAtMillis,
                              List<RouteConfig> routes) {
    }

    /**
     * 读取本机已确认的路由版本。
     *
     * 返回 {@code null} 表示**没有可用的已确认版本**：文件不存在、文件为空、或内容解析不了
     * （典型是旧版本写下的格式）。调用方据此回退到 YAML 路由。
     *
     * 这里刻意不抛异常，也不返回「空版本」：把「读不出来」当成「路由表是空的」，
     * 会让网关以**零路由**启动——全站 404，而且日志里看不到任何异常。回退 YAML 至少是
     * 一份完整、可解释的配置。
     */
    public AppliedState loadOrNull() {
        if (!exists()) {
            return null;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                log.warn("路由覆盖文件为空，已忽略并回退 YAML 路由: path={}", path.toAbsolutePath());
                return null;
            }
            Map<String, Object> root = JsonCodec.parseObjectMap(json);
            return new AppliedState(
                    intValue(root.get(KEY_REVISION)),
                    text(root.get(KEY_OPERATION_ID)),
                    longValue(root.get(KEY_APPLIED_AT)),
                    toRoutes(objectMapList(root.get(KEY_ROUTES))));
        } catch (Exception ex) {
            log.warn("路由覆盖文件不可解析，已忽略并回退 YAML 路由: path={}, 原因={}。"
                            + "当前格式为 {{revision, appliedOperationId, appliedAtMillis, routes:[...]}}，"
                            + "确认无需保留后可直接删除该文件",
                    path.toAbsolutePath(), ex.getMessage());
            return null;
        }
    }

    /**
     * 把路由表与版本元信息原子写入 overlay 文件，自动创建父目录。
     *
     * @param revision    本次生效的版本号
     * @param operationId 本次操作 ID，可为空
     * @param appliedAtMillis 落盘时刻
     * @param routes      要持久化的路由列表
     * @throws IllegalStateException 写入失败
     */
    public void save(int revision, String operationId, long appliedAtMillis, List<RouteConfig> routes) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(KEY_REVISION, revision);
        root.put(KEY_OPERATION_ID, operationId == null ? "" : operationId);
        root.put(KEY_APPLIED_AT, appliedAtMillis);
        root.put(KEY_ROUTES, toRows(routes));
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Path temp = tempPath();
            // 落盘用缩进 JSON，方便人眼看；解析不挑格式
            Files.writeString(temp, JsonCodec.toPrettyJson(root), StandardCharsets.UTF_8);
            moveAtomically(temp);
            log.info("路由已写入覆盖文件: {} revision={} operationId={}",
                    path.toAbsolutePath(), revision, operationId);
        } catch (IOException ex) {
            throw new IllegalStateException("写入路由覆盖文件失败: " + path.toAbsolutePath(), ex);
        }
    }

    /** 同目录临时文件：跨目录改名不是原子操作，因此必须落在同一个目录下。 */
    private Path tempPath() {
        String name = path.getFileName().toString() + TEMP_SUFFIX;
        return path.getParent() == null ? Path.of(name) : path.getParent().resolve(name);
    }

    /** 原子改名替换目标文件；文件系统不支持时退回普通替换。 */
    private void moveAtomically(Path temp) throws IOException {
        try {
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            log.warn("文件系统不支持原子改名，退回普通替换: {}", path.toAbsolutePath());
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 管理口和 overlay 落盘共用同一套字段。 */
    public static List<Map<String, Object>> toRows(List<RouteConfig> routes) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (routes == null) {
            return rows;
        }
        for (RouteConfig route : routes) {
            rows.add(toRow(route));
        }
        return rows;
    }

    /** 单条路由转成可落盘/可返回的字段表。 */
    public static Map<String, Object> toRow(RouteConfig route) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(RouteField.ID.jsonName(), nullToEmpty(route.getId()));
        row.put(RouteField.BUSINESS_PREFIX.jsonName(), nullToEmpty(route.getBusinessPrefix()));
        row.put(RouteField.TARGET_URL.jsonName(), nullToEmpty(route.getTargetUrl()));
        row.put(RouteField.TARGET_URLS.jsonName(), joinTargetUrls(route.getTargetUrls()));
        row.put(RouteField.TARGETS.jsonName(), targetRows(route.getTargets()));
        row.put(RouteField.STICKY_HEADER.jsonName(), nullToEmpty(route.getStickyHeader()));
        row.put(RouteField.STRIP_PREFIX.jsonName(), nullToEmpty(route.getStripPrefix()));
        return row;
    }

    /** 把字段表列表转成 RouteConfig 列表。 */
    public static List<RouteConfig> toRoutes(List<Map<String, Object>> rows) {
        List<RouteConfig> routes = new ArrayList<>();
        if (rows == null) {
            return routes;
        }
        for (Map<String, Object> row : rows) {
            RouteConfig route = new RouteConfig();
            route.setId(blankToNull(text(row.get(RouteField.ID.jsonName()))));
            route.setBusinessPrefix(blankToNull(text(row.get(RouteField.BUSINESS_PREFIX.jsonName()))));
            route.setTargetUrl(blankToNull(text(row.get(RouteField.TARGET_URL.jsonName()))));
            route.setTargetUrls(splitTargetUrls(text(row.get(RouteField.TARGET_URLS.jsonName()))));
            route.setTargets(toTargets(objectMapList(row.get(RouteField.TARGETS.jsonName()))));
            route.setStickyHeader(blankToNull(text(row.get(RouteField.STICKY_HEADER.jsonName()))));
            route.setStripPrefix(blankToNull(text(row.get(RouteField.STRIP_PREFIX.jsonName()))));
            routes.add(route);
        }
        return routes;
    }

    private static List<Map<String, Object>> targetRows(List<RouteTarget> targets) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (targets == null) {
            return rows;
        }
        for (RouteTarget target : targets) {
            if (target == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(RouteField.TARGET_SERVICE_NAME.jsonName(), nullToEmpty(target.serviceName()));
            row.put(RouteField.TARGET_GROUP.jsonName(), nullToEmpty(target.group()));
            row.put(RouteField.TARGET_WEIGHT.jsonName(), target.weight());
            rows.add(row);
        }
        return rows;
    }

    private static List<RouteTarget> toTargets(List<Map<String, Object>> rows) {
        List<RouteTarget> targets = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String serviceName = text(row.get(RouteField.TARGET_SERVICE_NAME.jsonName()));
            if (serviceName.isBlank()) {
                throw new IllegalArgumentException("targets 缺少 serviceName");
            }
            targets.add(new RouteTarget(
                    serviceName,
                    text(row.get(RouteField.TARGET_GROUP.jsonName())),
                    intValue(row.get(RouteField.TARGET_WEIGHT.jsonName()))));
        }
        return targets;
    }

    /** 把 JSON 值宽松收敛成字段表列表：非数组一律当空。 */
    private static List<Map<String, Object>> objectMapList(Object value) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!(value instanceof List<?> list)) {
            return rows;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                map.forEach((key, val) -> row.put(String.valueOf(key), val));
                rows.add(row);
            }
        }
        return rows;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(text(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(text(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
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
