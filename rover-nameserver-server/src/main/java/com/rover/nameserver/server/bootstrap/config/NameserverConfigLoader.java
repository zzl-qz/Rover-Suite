package com.rover.nameserver.server.bootstrap.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 15:13:00
 * Description: 加载 Nameserver YAML 配置文件
 *
 * 核心职责：按优先级把 rover-nameserver.yml 读取为 {@link NameserverConfig}：</p>
 * <ol>
 *     <li>优先加载工作目录下的外部配置 {@code config/rover-nameserver.yml}（便于部署环境覆盖）；</li>
 *     <li>其次加载 classpath 内的同名配置文件；</li>
 *     <li>以上都不存在时返回全默认配置对象，服务仍可启动。</li>
 * </ol>
 *
 * 被 {@link com.rover.nameserver.server.bootstrap.NameserverApplication} 调用；
 * 解析失败（文件损坏/格式错误）抛出 IllegalStateException 终止启动。</p>
 */
@Slf4j
public class NameserverConfigLoader {

    /** classpath 内配置文件名 */
    private static final String CONFIG_FILE = "rover-nameserver.yml";
    /** 外部配置路径：优先于 classpath，位于工作目录下 config/ 子目录 */
    private static final Path EXTERNAL_CONFIG = Path.of("config", CONFIG_FILE);

    /** Jackson YAML 反序列化器（线程安全，可复用） */
    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    /**
     * 加载配置：外部文件 → classpath → 默认值，三步兜底。
     *
     * @return 强类型的 Nameserver 配置对象
     * @throws IllegalStateException 找到配置文件但解析失败时抛出
     */
    public NameserverConfig load() {
        if (Files.exists(EXTERNAL_CONFIG)) {
            return loadFromPath(EXTERNAL_CONFIG);
        }

        return loadFromClasspath();
    }

    /**
     * 从外部文件系统路径加载配置。
     *
     * @param path 配置文件路径（外部 config/ 目录）
     * @return 解析后的配置
     * @throws IllegalStateException 读取或解析失败（如 YAML 语法错误）时抛出
     */
    private NameserverConfig loadFromPath(Path path) {
        try {
            log.info("Loading Nameserver config from {}", path.toAbsolutePath());
            return mapper.readValue(path.toFile(), NameserverConfig.class);
        } catch (IOException err) {
            throw new IllegalStateException("读取 Nameserver 配置文件失败：" + path, err);
        }
    }

    /**
     * 从 classpath 加载配置；资源缺失不算错误，返回全默认配置。
     *
     * @return 解析后的配置；classpath 无配置时为默认配置对象
     * @throws IllegalStateException 资源存在但解析失败时抛出
     */
    private NameserverConfig loadFromClasspath() {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (input == null) {
                log.info("Nameserver config not found, using default config");
                return new NameserverConfig();
            }
            log.info("Loading Nameserver config from classpath:{}", CONFIG_FILE);
            return mapper.readValue(input, NameserverConfig.class);
        } catch (IOException err) {
            throw new IllegalStateException("读取 classpath Nameserver 配置文件失败：" + CONFIG_FILE, err);
        }
    }
}