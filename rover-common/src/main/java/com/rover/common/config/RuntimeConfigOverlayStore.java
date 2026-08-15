package com.rover.common.config;

import com.rover.common.json.JsonCodec;
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
 * Created: 2026-08-04 10:15:00
 * Description: 运行时配置 overlay 持久化：Admin 改完落盘，重启再灌回
 */
@Slf4j
public class RuntimeConfigOverlayStore {

    /** 覆盖文件路径 */
    private final Path path;

    /** 构造存储；path 为覆盖文件落盘路径。 */
    public RuntimeConfigOverlayStore(Path path) {
        this.path = path;
    }

    /** 覆盖文件路径。 */
    public Path getPath() {
        return path;
    }

    /** 覆盖文件是否已存在。 */
    public boolean exists() {
        return Files.exists(path);
    }

    /** 读取覆盖配置；文件不存在返回空 Map。 */
    public Map<String, String> load() {
        if (!exists()) {
            return Map.of(); // 首次启动无覆盖文件，直接返回空
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            List<Map<String, String>> rows = JsonCodec.parseStringMapArray(json);
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

    /** 全量覆盖写入。 */
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
            // 落盘用缩进 JSON，方便人眼看；解析不挑格式
            Files.writeString(path, JsonCodec.toPrettyJson(rows), StandardCharsets.UTF_8);
            log.info("运行时配置已写入覆盖文件: {}", path.toAbsolutePath());
        } catch (IOException ex) {
            throw new IllegalStateException("写入配置覆盖文件失败: " + path.toAbsolutePath(), ex);
        }
    }
}
