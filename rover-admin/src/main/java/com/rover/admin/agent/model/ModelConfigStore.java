package com.rover.admin.agent.model;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 模型配置的落盘与内存当前值。
 *
 * 用例：文件存在且字段完整 → 文件优先；否则用环境变量播种
 * （{@code ROVER_AGENT_MODEL_CHAT / ROVER_AGENT_API_KEY / ROVER_AGENT_BASE_URL / ROVER_AGENT_MODEL}）。
 * 于是"环境变量作为首次启动默认值"成立：既有用法不改，此后以页面保存的配置为准。
 *
 * 文件损坏或读不动只 WARN 并回落到播种值，不抛异常也不删文件。
 */
@Component
public class ModelConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigStore.class);

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_BASE_URL = "base-url";
    private static final String KEY_MODEL = "model";
    private static final String KEY_TIMEOUT_SECONDS = "timeout-seconds";
    private static final String KEY_API_KEY_ENC = "api-key-enc";

    private static final String SEED_CHAT = "spring.ai.model.chat";
    private static final String SEED_BASE_URL = "spring.ai.openai.base-url";
    private static final String SEED_API_KEY = "spring.ai.openai.api-key";
    private static final String SEED_MODEL = "spring.ai.openai.chat.model";
    private static final String SEED_TIMEOUT = "spring.ai.openai.timeout";

    private final AdminModelProperties properties;
    private final Environment environment;
    private final SecretCipher cipher;

    private volatile ModelSettings current = ModelSettings.none();

    public ModelConfigStore(AdminModelProperties properties, Environment environment, SecretCipher cipher) {
        this.properties = properties;
        this.environment = environment;
        this.cipher = cipher;
    }

    @PostConstruct
    void init() {
        this.current = readFile().orElseGet(this::seedFromEnvironment);
        ensureFileExists();
        log.info("模型配置：来源={} 是否配置={} 文件={}", current.source(), current.configured(), file());
        log.info("模型配置文件加固命令：icacls \"{}\" /inheritance:r /grant:r \"%USERNAME%:F\"", file());
    }

    /** 当前生效的模型配置。 */
    public ModelSettings current() {
        return current;
    }

    /** 模型配置文件路径。 */
    public Path file() {
        return properties.configFilePath();
    }

    /**
     * 落盘并更新内存当前值：密钥加密后写入，先写临时文件再原子替换。
     * 写失败抛 {@link IllegalStateException}，由接口层转成可见的错误。
     */
    public void persist(ModelSettings settings) {
        int timeout = ModelSettings.clampTimeout(settings.timeoutSeconds());
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(KEY_ENABLED, Boolean.toString(settings.enabled()));
        entries.put(KEY_BASE_URL, nullToEmpty(settings.baseUrl()));
        entries.put(KEY_MODEL, nullToEmpty(settings.model()));
        entries.put(KEY_TIMEOUT_SECONDS, Integer.toString(timeout));
        if (settings.apiKey() != null && !settings.apiKey().isBlank()) {
            entries.put(KEY_API_KEY_ENC, cipher.encrypt(settings.apiKey()));
        }
        write(entries);
        this.current = new ModelSettings(settings.enabled(), nullToEmpty(settings.baseUrl()), settings.apiKey(),
                nullToEmpty(settings.model()), timeout, ModelSettings.Source.FILE,
                ModelSettings.keyStateOf(settings.apiKey()));
    }

    /**
     * 缺文件就落一个空配置占位文件：{@code config/} 整个目录被 git 忽略，新克隆的仓库本来就没有它，
     * 启动时补出来，路径才看得见、备份才有对象。占位文件不含任何配置，因此不会顶掉环境变量播种。
     */
    private void ensureFileExists() {
        if (Files.exists(file())) {
            return;
        }
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(KEY_ENABLED, "false");
        entries.put(KEY_BASE_URL, "");
        entries.put(KEY_MODEL, "");
        entries.put(KEY_TIMEOUT_SECONDS, Integer.toString(ModelSettings.DEFAULT_TIMEOUT_SECONDS));
        try {
            write(entries);
            log.info("模型配置文件不存在，已创建空配置：{}", file());
        } catch (RuntimeException ex) {
            // 目录不可写不阻断启动：控制台保存时会再报出具体错误，这里只留痕。
            log.warn("模型配置占位文件创建失败：{}（{}）", file(), ex.getMessage());
        }
    }

    private Optional<ModelSettings> readFile() {
        Path target = file();
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
            Properties props = new Properties();
            props.load(reader);
            String baseUrl = trim(props.getProperty(KEY_BASE_URL));
            String model = trim(props.getProperty(KEY_MODEL));
            if (baseUrl.isEmpty() && model.isEmpty()) {
                // 启动时补出来的空配置占位文件走这条分支：不是损坏，只是还没配过。
                return Optional.empty();
            }
            if (baseUrl.isEmpty() || model.isEmpty()) {
                log.warn("模型配置文件缺少 base-url 或 model，本次回退为环境变量播种：{}", target);
                return Optional.empty();
            }
            String encrypted = trim(props.getProperty(KEY_API_KEY_ENC));
            String apiKey = "";
            ModelSettings.KeyState keyState = ModelSettings.KeyState.ABSENT;
            if (!encrypted.isEmpty()) {
                try {
                    apiKey = cipher.decrypt(encrypted);
                    keyState = ModelSettings.KeyState.OK;
                } catch (RuntimeException ex) {
                    // 密钥解不开必须能被看见：不假装"尚未配置模型"。
                    keyState = ModelSettings.KeyState.UNREADABLE;
                    log.warn("模型 API 密钥无法解密，需要在控制台重新填写：{}（{}）", target, ex.getMessage());
                }
            }
            return Optional.of(new ModelSettings(
                    Boolean.parseBoolean(trim(props.getProperty(KEY_ENABLED, "true"))), baseUrl, apiKey, model,
                    ModelSettings.clampTimeout(parseSeconds(props.getProperty(KEY_TIMEOUT_SECONDS))),
                    ModelSettings.Source.FILE, keyState));
        } catch (IOException | IllegalArgumentException ex) {
            log.warn("模型配置文件读取失败，本次回退为环境变量播种：{}（{}）", target, ex.getMessage());
            return Optional.empty();
        }
    }

    private ModelSettings seedFromEnvironment() {
        boolean enabled = "openai".equalsIgnoreCase(trim(environment.getProperty(SEED_CHAT)));
        String apiKey = trim(environment.getProperty(SEED_API_KEY));
        return new ModelSettings(enabled, trim(environment.getProperty(SEED_BASE_URL)),
                apiKey, trim(environment.getProperty(SEED_MODEL)),
                ModelSettings.clampTimeout(parseSeconds(environment.getProperty(SEED_TIMEOUT))),
                enabled ? ModelSettings.Source.ENV : ModelSettings.Source.NONE,
                ModelSettings.keyStateOf(apiKey));
    }

    private void write(Map<String, String> entries) {
        Path target = file();
        StringBuilder body = new StringBuilder()
                .append("# Rover Admin 模型配置，由控制台保存生成，保存即生效。\n")
                .append("# 本目录已在 .gitignore 中；文件缺失时启动会自动补出这个空配置。\n")
                .append("# api-key-enc 是 AES-GCM 密文；主密钥在主密钥文件里，两者必须分开备份。\n");
        entries.forEach((key, value) -> body.append(key).append('=').append(value).append('\n'));
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, body.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("模型配置无法写入 " + target + "：" + ex.getMessage(), ex);
        }
    }

    /** 支持 {@code 30s} / {@code 1m} / {@code PT30S} / 纯秒数；无法解析时用默认值。 */
    private static int parseSeconds(String raw) {
        String value = trim(raw).toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return ModelSettings.DEFAULT_TIMEOUT_SECONDS;
        }
        try {
            if (value.startsWith("pt")) {
                return (int) Duration.parse(value.toUpperCase(Locale.ROOT)).toSeconds();
            }
            if (value.endsWith("ms")) {
                return Math.max(1, Integer.parseInt(value.substring(0, value.length() - 2)) / 1000);
            }
            int factor = 1;
            char unit = value.charAt(value.length() - 1);
            if (unit == 's' || unit == 'm' || unit == 'h') {
                factor = unit == 's' ? 1 : unit == 'm' ? 60 : 3600;
                value = value.substring(0, value.length() - 1);
            }
            return Integer.parseInt(value.trim()) * factor;
        } catch (RuntimeException ex) {
            return ModelSettings.DEFAULT_TIMEOUT_SECONDS;
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}