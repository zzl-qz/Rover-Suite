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
 */
@Slf4j
public class NameserverConfigLoader {

    private static final String CONFIG_FILE = "rover-nameserver.yml";
    private static final Path EXTERNAL_CONFIG = Path.of("config", CONFIG_FILE);

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    public NameserverConfig load() {
        if (Files.exists(EXTERNAL_CONFIG)) {
            return loadFromPath(EXTERNAL_CONFIG);
        }

        return loadFromClasspath();
    }

    private NameserverConfig loadFromPath(Path path) {
        try {
            log.info("Loading Nameserver config from {}", path.toAbsolutePath());
            return mapper.readValue(path.toFile(), NameserverConfig.class);
        } catch (IOException err) {
            throw new IllegalStateException("读取 Nameserver 配置文件失败：" + path, err);
        }
    }

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
