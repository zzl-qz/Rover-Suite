package com.rover.common.config;

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
 * Created: 2026-08-10 17:00:00
 * Description: 运行时配置 overlay，Admin 改完落盘，重启再灌回去
 *
 * 这个类是什么：运行时配置覆盖层(overlay)的本地文件持久化存储。
 * 核心职责：以 JSON 数组文件形式保存 Admin 在运行期修改过的配置(key/value 行)，
 * 进程重启后 load 回来覆盖默认配置，实现「改完即持久、重启不丢失」。
 * 被谁用：rover-admin/各组件配置启动链条；需要持久化运行期配置修改的地方。
 */
@Slf4j
public class RuntimeConfigOverlayStore {

    /** 覆盖文件路径 */
    private final Path path;

    /**
     * 构造存储。
     *
     * @param path 覆盖文件的落盘路径
     */
    public RuntimeConfigOverlayStore(Path path) {
        this.path = path;
    }

    /** @return 覆盖文件路径 */
    public Path getPath() {
        return path;
    }

    /** @return 覆盖文件是否已存在 */
    public boolean exists() {
        return Files.exists(path);
    }

    /**
     * 读取覆盖配置。
     *
     * @return key -> value 的有序 Map；文件不存在时返回空 Map
     * @throws IllegalStateException 文件存在但解析失败
     */
    public Map<String, String> load() {
        if (!exists()) {
            return Map.of(); // 首次启动无覆盖文件，直接返回空
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            List<Map<String, String>> rows = ManageJson.parseObjectArray(json);
            Map<String, String> result = new LinkedHashMap<>();
            // 逐行解析：跳过 key 为空的行，value 为空时落成空串
            for (Map<String, String> row : rows) {
                String key = row.get("key");
                if (key == null || key.isBlank()) {
                    continue;
                }
                result.put(key, row.get("value") == null ? "" : row.get("value"));
            }
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException("读取配置覆盖文件失败: " + path.toAbsolutePath(), ex);
        }
    }

    /**
     * 写入覆盖配置(全量覆盖写)。
     *
     * @param values 待持久化的 key -> value 集合
     * @throws IllegalStateException 父目录创建或文件写入失败
     */
    public void save(Map<String, String> values) {
        try {
            // 父目录可能不存在，先补建，避免直接落盘失败
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", entry.getKey());
                row.put("value", entry.getValue() == null ? "" : entry.getValue());
                rows.add(row);
            }
            Files.writeString(path, ManageJson.arrayOfObjects(rows), StandardCharsets.UTF_8);
            log.info("运行时配置已写入覆盖文件: {}", path.toAbsolutePath());
        } catch (IOException ex) {
            throw new IllegalStateException("写入配置覆盖文件失败: " + path.toAbsolutePath(), ex);
        }
    }
}
