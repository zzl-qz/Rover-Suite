/**
 * 作者：Daylight
 * 创建时间：2026-08-08 15:13:00
 * 描述：加载 Gateway YAML 配置文件
 */
package com.rover.gateway.bootstrap.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GatewayConfigLoader {

    private static final String CONFIG_FILE = "rover-gateway.yml";
    private static final Path EXTERNAL_CONFIG = Path.of("config", CONFIG_FILE);

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    public GatewayConfig load() {
        if (Files.exists(EXTERNAL_CONFIG)) {
            return loadFromPath(EXTERNAL_CONFIG);
        }

        return loadFromClasspath();
    }

    private GatewayConfig loadFromPath(Path path) {
        try {
            log.info("Loading Gateway config from {}", path.toAbsolutePath());
            return validate(mapper.readValue(path.toFile(), GatewayConfig.class));
        } catch (IOException err) {
            throw new IllegalStateException("读取 Gateway 配置文件失败：" + path, err);
        }
    }

    private GatewayConfig loadFromClasspath() {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (input == null) {
                log.info("Gateway config not found, using default config");
                return validate(new GatewayConfig());
            }
            log.info("Loading Gateway config from classpath:{}", CONFIG_FILE);
            return validate(mapper.readValue(input, GatewayConfig.class));
        } catch (IOException err) {
            throw new IllegalStateException("读取 classpath Gateway 配置文件失败：" + CONFIG_FILE, err);
        }
    }

    private GatewayConfig validate(GatewayConfig config) {
        config.validate();
        return config;
    }
}
