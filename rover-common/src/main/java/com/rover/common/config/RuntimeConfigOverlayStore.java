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
 */
@Slf4j
public class RuntimeConfigOverlayStore {

    private final Path path;

    public RuntimeConfigOverlayStore(Path path) {
        this.path = path;
    }

    public Path getPath() {
        return path;
    }

    public boolean exists() {
        return Files.exists(path);
    }

    public Map<String, String> load() {
        if (!exists()) {
            return Map.of();
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            List<Map<String, String>> rows = ManageJson.parseObjectArray(json);
            Map<String, String> result = new LinkedHashMap<>();
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

    public void save(Map<String, String> values) {
        try {
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
