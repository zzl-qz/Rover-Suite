package com.rover.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-05 15:40:00
 * Description: 通用 YAML 启动配置加载器：外部文件优先、classpath 兜底、默认对象收尾，解析失败终止启动
 */
@Slf4j
public class YamlConfigLoader<T> {

    /** 配置文件文件名，同时用于外部目录定位与 classpath 资源名。 */
    private final String configFile;
    /** 外部配置路径：位于工作目录 config/ 子目录，优先于 classpath。 */
    private final Path externalConfig;
    /** 目标配置类型。 */
    private final Class<T> configType;
    /** 可选校验器，解析/兜底完成后调用。 */
    private final Consumer<T> validator;
    /** Jackson YAML 反序列化器（线程安全，可复用）。 */
    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    /**
     * @param configFile 配置文件文件名（如 rover-nameserver.yml）
     * @param configType 目标强类型配置类
     * @param validator  可选校验器，可为 null
     */
    public YamlConfigLoader(String configFile, Class<T> configType, Consumer<T> validator) {
        this.configFile = configFile;
        this.externalConfig = Path.of(ConfigFiles.DIRECTORY, configFile);
        this.configType = configType;
        this.validator = validator;
    }

    /**
     * 加载配置：外部文件 → classpath → 默认对象，三步兜底。
     *
     * @return 强类型配置对象
     * @throws IllegalStateException 找到配置文件但解析失败时抛出
     */
    public T load() {
        if (Files.exists(externalConfig)) {
            return loadFromPath(externalConfig);
        }
        return loadFromClasspath();
    }

    /** 从外部文件系统路径加载配置。 */
    private T loadFromPath(Path path) {
        try {
            log.info("Loading config {} from {}", configFile, path.toAbsolutePath());
            return validate(mapper.readValue(path.toFile(), configType));
        } catch (IOException err) {
            throw new IllegalStateException("读取配置文件失败: " + path, err);
        }
    }

    /** 从 classpath 加载配置；资源缺失不算错误，返回默认配置对象。 */
    private T loadFromClasspath() {
        try (InputStream input = YamlConfigLoader.class.getClassLoader().getResourceAsStream(configFile)) {
            if (input == null) {
                log.info("Config not found, using default config: {}", configFile);
                return validate(newDefault());
            }
            log.info("Loading config {} from classpath", configFile);
            return validate(mapper.readValue(input, configType));
        } catch (IOException err) {
            throw new IllegalStateException("读取 classpath 配置文件失败: " + configFile, err);
        }
    }

    private T validate(T config) {
        if (validator != null) {
            validator.accept(config);
        }
        return config;
    }

    /** 反射创建默认配置对象，要求目标类型有无参构造。 */
    private T newDefault() {
        try {
            return configType.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("创建默认配置失败: " + configType.getName(), ex);
        }
    }
}
