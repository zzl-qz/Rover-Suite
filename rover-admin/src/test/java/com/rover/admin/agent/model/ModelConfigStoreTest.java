package com.rover.admin.agent.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/** 模型配置的落盘、播种优先级与密钥保护。 */
class ModelConfigStoreTest {

    private static final String SECRET = "sk-abcdefghijklmnopqrstuvwxyz";

    @TempDir
    Path tempDir;

    @Test
    void seedsFromEnvironmentWhenNoFileExists() {
        ModelConfigStore store = store(environment("openai", "https://api.deepseek.com", SECRET, "deepseek-chat"));

        ModelSettings current = store.current();

        assertEquals(ModelSettings.Source.ENV, current.source());
        assertTrue(current.configured());
        assertEquals(SECRET, current.apiKey());
        assertEquals(ModelSettings.KeyState.OK, current.keyState());
        assertEquals(30, current.timeoutSeconds());
        // 缺文件时启动会补出空配置（config/ 被 git 忽略，新克隆的仓库本来就没有它）。
        assertTrue(Files.isRegularFile(store.file()));
        // 占位文件不含任何配置，所以不会顶掉环境变量播种。
        assertEquals(ModelSettings.Source.ENV,
                store(environment("openai", "https://api.deepseek.com", SECRET, "deepseek-chat")).current().source());
    }

    @Test
    void defaultConfigFileSitsInTheProjectConfigDirectory() {
        AdminModelProperties properties = new AdminModelProperties();

        // 默认落在工作目录下的 config/：随项目一起维护与备份，不再写到项目之外的用户主目录。
        assertEquals(Path.of("config", "admin-model.properties").toAbsolutePath().normalize(),
                properties.configFilePath());
        assertEquals(properties.configFilePath().resolveSibling("master.key"), properties.masterKeyPath());
    }

    @Test
    void reportsNothingConfiguredWhenTheChatModelIsOff() {
        ModelConfigStore store = store(environment("none", "https://api.openai.com", "", "gpt-5-mini"));

        assertEquals(ModelSettings.Source.NONE, store.current().source());
        // 环境变量里 base-url / model 有 yml 默认值，但开关没打开就不算配过。
        assertFalse(store.current().configured());
    }

    @Test
    void persistedSettingsSurviveAReloadAndKeepTheKeyEncrypted() throws IOException {
        ModelConfigStore store = store(environment("none", "", "", ""));
        store.persist(new ModelSettings(true, "https://api.deepseek.com", SECRET, "deepseek-chat", 45,
                ModelSettings.Source.FILE, ModelSettings.KeyState.OK));

        String onDisk = Files.readString(store.file(), StandardCharsets.UTF_8);
        assertFalse(onDisk.contains(SECRET), "落盘内容不得含明文密钥");
        assertTrue(onDisk.contains("api-key-enc="));
        // 临时文件不得残留。
        try (Stream<Path> files = Files.list(tempDir)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }

        ModelConfigStore reloaded = store(environment("none", "", "", ""));
        assertEquals(ModelSettings.Source.FILE, reloaded.current().source());
        assertEquals(SECRET, reloaded.current().apiKey());
        assertEquals(ModelSettings.KeyState.OK, reloaded.current().keyState());
        assertEquals(45, reloaded.current().timeoutSeconds());
        assertEquals("deepseek-chat", reloaded.current().model());
    }

    @Test
    void fileWinsOverDivergentEnvironment() {
        ModelConfigStore first = store(environment("none", "", "", ""));
        first.persist(new ModelSettings(true, "http://127.0.0.1:11434/v1", "", "qwen2.5:7b", 30,
                ModelSettings.Source.FILE, ModelSettings.KeyState.ABSENT));

        ModelConfigStore reloaded = store(environment("openai", "https://api.deepseek.com", SECRET, "deepseek-chat"));

        assertEquals("http://127.0.0.1:11434/v1", reloaded.current().baseUrl());
        assertEquals("qwen2.5:7b", reloaded.current().model());
        assertEquals("", reloaded.current().apiKey());
        assertEquals(ModelSettings.KeyState.ABSENT, reloaded.current().keyState());
    }

    @Test
    void incompleteFileFallsBackToEnvironmentWithoutDeletingIt() throws IOException {
        Path file = tempDir.resolve("admin-model.properties");
        Files.writeString(file, "enabled=true\ntimeout-seconds=30\n", StandardCharsets.UTF_8);

        ModelConfigStore store = store(environment("openai", "https://api.deepseek.com", SECRET, "deepseek-chat"));

        // 缺 base-url / model：回落到播种值，且不动用户文件。
        assertEquals(ModelSettings.Source.ENV, store.current().source());
        assertEquals(SECRET, store.current().apiKey());
        assertTrue(Files.isRegularFile(file));
    }

    @Test
    void unreadableFileFallsBackToEnvironmentWithoutThrowing() throws IOException {
        // 用一个目录占住配置文件路径，制造"读不动"。
        Files.createDirectory(tempDir.resolve("admin-model.properties"));

        ModelConfigStore store = store(environment("openai", "https://api.deepseek.com", SECRET, "deepseek-chat"));

        assertEquals(ModelSettings.Source.ENV, store.current().source());
        assertTrue(store.current().configured());
    }

    @Test
    void undecryptableKeyIsVisibleInsteadOfLookingUnconfigured() throws IOException {
        Path file = tempDir.resolve("admin-model.properties");
        Files.writeString(file, "enabled=true\nbase-url=https://api.deepseek.com\nmodel=deepseek-chat\n"
                + "timeout-seconds=30\napi-key-enc=bm90LWEtcmVhbC1jaXBoZXJ0ZXh0\n", StandardCharsets.UTF_8);

        ModelConfigStore store = store(environment("none", "", "", ""));

        assertEquals(ModelSettings.KeyState.UNREADABLE, store.current().keyState());
        // 配过就是配过：不能因为解不开就伪装成"尚未配置模型"。
        assertTrue(store.current().configured());
        assertEquals("", store.current().apiKey());
    }

    @Test
    void masterKeyFileIsCreatedOnFirstSaveAndReusedAfterwards() throws IOException {
        ModelConfigStore store = store(environment("none", "", "", ""));
        store.persist(new ModelSettings(true, "https://api.deepseek.com", SECRET, "deepseek-chat", 30,
                ModelSettings.Source.FILE, ModelSettings.KeyState.OK));

        Path masterKey = tempDir.resolve("master.key");
        assertTrue(Files.isRegularFile(masterKey));
        String encoded = Files.readString(masterKey, StandardCharsets.UTF_8).trim();
        assertFalse(encoded.contains(SECRET));

        ModelConfigStore reloaded = store(environment("none", "", "", ""));
        assertEquals(SECRET, reloaded.current().apiKey());
    }

    @Test
    void plainTextMasterKeyFromConfigurationKeepsTheKeyReadable() {
        ModelConfigStore first = new ModelConfigStore(properties(), new MockEnvironment(),
                new SecretCipher("correct-horse-battery-staple", tempDir.resolve("ignored.key")));
        first.init();
        first.persist(new ModelSettings(true, "https://api.deepseek.com", SECRET, "deepseek-chat", 30,
                ModelSettings.Source.FILE, ModelSettings.KeyState.OK));

        ModelConfigStore reloaded = new ModelConfigStore(properties(), new MockEnvironment(),
                new SecretCipher("correct-horse-battery-staple", tempDir.resolve("ignored.key")));
        reloaded.init();

        assertEquals(SECRET, reloaded.current().apiKey());
        assertFalse(Files.exists(tempDir.resolve("ignored.key")));
    }

    @Test
    void timeoutIsClampedAndParsedFromSpringStyleDuration() throws IOException {
        Path file = tempDir.resolve("admin-model.properties");
        Files.writeString(file, "enabled=true\nbase-url=https://api.deepseek.com\nmodel=deepseek-chat\n"
                + "timeout-seconds=2m\n", StandardCharsets.UTF_8);

        ModelConfigStore store = store(environment("none", "", "", ""));
        assertEquals(120, store.current().timeoutSeconds());

        store.persist(new ModelSettings(true, "https://api.deepseek.com", "", "deepseek-chat", 9999,
                ModelSettings.Source.FILE, ModelSettings.KeyState.ABSENT));
        assertEquals(300, store.current().timeoutSeconds());
    }

    @Test
    void apiKeyIsNeverWrittenToDiskInPlainText() throws IOException {
        ModelConfigStore store = store(environment("none", "", "", ""));
        store.persist(new ModelSettings(true, "https://api.deepseek.com", SECRET, "deepseek-chat", 30,
                ModelSettings.Source.FILE, ModelSettings.KeyState.OK));

        List<String> lines = Files.readAllLines(store.file(), StandardCharsets.UTF_8);
        assertTrue(lines.stream().noneMatch(line -> line.contains(SECRET)));
    }

    private ModelConfigStore store(MockEnvironment environment) {
        ModelConfigStore store = new ModelConfigStore(properties(), environment,
                new SecretCipher("", tempDir.resolve("master.key")));
        store.init();
        return store;
    }

    private AdminModelProperties properties() {
        AdminModelProperties properties = new AdminModelProperties();
        properties.setConfigFile(tempDir.resolve("admin-model.properties").toString());
        properties.setMasterKeyFile(tempDir.resolve("master.key").toString());
        return properties;
    }

    private static MockEnvironment environment(String chat, String baseUrl, String apiKey, String model) {
        return new MockEnvironment()
                .withProperty("spring.ai.model.chat", chat)
                .withProperty("spring.ai.openai.base-url", baseUrl)
                .withProperty("spring.ai.openai.api-key", apiKey)
                .withProperty("spring.ai.openai.chat.model", model)
                .withProperty("spring.ai.openai.timeout", "30s");
    }
}