package com.rover.common.config;

import com.rover.common.json.JsonCodec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
        Path target = path.toAbsolutePath();
        Path parent = target.getParent();
        Path temporary = null;
        try {
            // 父目录可能不存在，先补建，避免直接落盘失败
            if (parent != null) { Files.createDirectories(parent); }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", entry.getKey());
                row.put("value", entry.getValue() == null ? "" : entry.getValue());
                rows.add(row);
            }
            // Write a complete sibling file and force it before replacing the live overlay.
            // A crash during serialization therefore leaves the previous valid file intact.
            byte[] bytes = JsonCodec.toPrettyJson(rows).getBytes(StandardCharsets.UTF_8);
            temporary = Files.createTempFile(parent, target.getFileName().toString() + ".", ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) { channel.write(buffer); }
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                // Some filesystems do not support atomic rename. Keep the old fallback behavior
                // there rather than failing every runtime config update.
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("运行时配置已写入覆盖文件: {}", path.toAbsolutePath());
        } catch (IOException ex) {
            throw new IllegalStateException("写入配置覆盖文件失败: " + path.toAbsolutePath(), ex);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException cleanupError) {
                    log.warn("清理配置临时文件失败: {}", temporary, cleanupError);
                }
            }
        }
    }
}
