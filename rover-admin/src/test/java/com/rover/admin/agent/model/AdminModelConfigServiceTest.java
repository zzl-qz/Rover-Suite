package com.rover.admin.agent.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.config.ConfigValues;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模型配置用例：启用态校验、密钥掩码与"留空即不改"、连接测试的错误归类。
 *
 * 连接测试用 JDK 自带 HttpServer 造真实响应，而不是 mock ChatClient ——
 * 错误归类看的正是真实 SDK 抛出的异常类型，mock 掉就什么都没验证到。
 */
class AdminModelConfigServiceTest {

    private static final String SECRET = "sk-abcdefghijklmnopqrstuvwxyz";

    @TempDir
    Path tempDir;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void saveRejectsEnabledWithoutBaseUrlOrModel() {
        AdminModelConfigService service = service(store(environment()));

        // 自定义厂商下，地址与模型名是用户手填的，缺一不可。
        Map<String, Object> missingUrl = new HashMap<>();
        missingUrl.put("enabled", true);
        missingUrl.put("vendor", "custom");
        missingUrl.put("model", "deepseek-chat");
        assertEquals("启用模型时必须填写服务地址",
                assertThrows(IllegalArgumentException.class, () -> service.save(missingUrl)).getMessage());

        Map<String, Object> missingModel = new HashMap<>();
        missingModel.put("enabled", true);
        missingModel.put("vendor", "custom");
        missingModel.put("baseUrl", "https://api.deepseek.com");
        assertEquals("启用模型时必须填写模型名",
                assertThrows(IllegalArgumentException.class, () -> service.save(missingModel)).getMessage());
    }

    @Test
    void saveRejectsBadUrlAndOverlongValues() {
        AdminModelConfigService service = service(store(environment()));

        Map<String, Object> badScheme = new HashMap<>();
        badScheme.put("enabled", true);
        badScheme.put("vendor", "custom");
        badScheme.put("baseUrl", "api.deepseek.com");
        badScheme.put("model", "deepseek-chat");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.save(badScheme))
                .getMessage().contains("http://"));

        Map<String, Object> tooLong = new HashMap<>();
        tooLong.put("enabled", true);
        tooLong.put("vendor", "custom");
        tooLong.put("baseUrl", "https://api.deepseek.com");
        tooLong.put("model", "m".repeat(200));
        assertEquals("服务地址或模型名过长",
                assertThrows(IllegalArgumentException.class, () -> service.save(tooLong)).getMessage());
    }

    @Test
    void savingWithoutApiKeyKeepsTheStoredOne() {
        ModelConfigStore store = store(environment());
        AdminChatModelGateway gateway = gateway(store);
        AdminModelConfigService service = new AdminModelConfigService(store, gateway);
        service.save(enabledSettings("deepseek-chat"));

        // 只切厂商、不带 apiKey：不能把已存的密钥抹掉；模型由新厂商映射，不再让用户手填。
        Map<String, Object> body = new HashMap<>();
        body.put("enabled", true);
        body.put("vendor", "zhipu");
        service.save(body);

        assertEquals(SECRET, gateway.appliedSettings().apiKey());
        assertEquals("glm-4.6", gateway.appliedSettings().model());
    }

    /** 厂商收敛：用户只选厂商，地址 / 主模型 / 快速模型都由后台映射。 */
    @Test
    void vendorMapsModelsWithoutRequiringTheUserToFillThem() {
        ModelConfigStore store = store(environment());
        AdminChatModelGateway gateway = gateway(store);
        AdminModelConfigService service = new AdminModelConfigService(store, gateway);

        // 智谱：主模型 glm-4.6（思考）+ 快速模型 glm-4-air（廉价调用）
        Map<String, Object> zhipu = new HashMap<>();
        zhipu.put("enabled", true);
        zhipu.put("vendor", "zhipu");
        zhipu.put("apiKey", SECRET);
        service.save(zhipu);
        assertEquals("glm-4.6", gateway.appliedSettings().model());
        assertEquals("https://open.bigmodel.cn/api/paas/v4", gateway.appliedSettings().baseUrl());
        assertTrue(gateway.appliedSettings().fastConfigured());
        assertEquals("glm-4-air", gateway.appliedSettings().fast().model());

        // DeepSeek：deepseek-chat 本身不思考，无需单独快速模型。
        Map<String, Object> deepseek = new HashMap<>();
        deepseek.put("enabled", true);
        deepseek.put("vendor", "deepseek");
        deepseek.put("apiKey", SECRET);
        service.save(deepseek);
        assertEquals("deepseek-chat", gateway.appliedSettings().model());
        assertFalse(gateway.appliedSettings().fastConfigured());
    }

    /** 自定义厂商：本地 / 代理没有「厂商」概念，地址与模型名仍由用户手填。 */
    @Test
    void customVendorKeepsManualAddressAndModel() {
        ModelConfigStore store = store(environment());
        AdminChatModelGateway gateway = gateway(store);
        AdminModelConfigService service = new AdminModelConfigService(store, gateway);

        Map<String, Object> custom = new HashMap<>();
        custom.put("enabled", true);
        custom.put("vendor", "custom");
        custom.put("baseUrl", "http://127.0.0.1:11434/v1");
        custom.put("model", "qwen2.5:7b");
        custom.put("apiKey", "");
        service.save(custom);

        assertEquals("http://127.0.0.1:11434/v1", gateway.appliedSettings().baseUrl());
        assertEquals("qwen2.5:7b", gateway.appliedSettings().model());
        assertFalse(gateway.appliedSettings().fastConfigured());
    }

    @Test
    void maskedApiKeyMeansKeepTheStoredOne() {
        ModelConfigStore store = store(environment());
        AdminModelConfigService service = service(store);
        service.save(enabledSettings("deepseek-chat"));

        Map<String, Object> body = new HashMap<>();
        body.put("enabled", true);
        body.put("baseUrl", "https://api.deepseek.com");
        body.put("model", "deepseek-chat");
        body.put("apiKey", ConfigValues.MASKED);
        service.save(body);

        assertEquals(SECRET, store.current().apiKey());
    }

    @Test
    void clearApiKeyRemovesItForKeylessLocalModels() {
        ModelConfigStore store = store(environment());
        AdminChatModelGateway gateway = gateway(store);
        AdminModelConfigService service = new AdminModelConfigService(store, gateway);
        service.save(enabledSettings("deepseek-chat"));

        Map<String, Object> body = new HashMap<>();
        body.put("enabled", true);
        body.put("baseUrl", "http://127.0.0.1:11434/v1");
        body.put("model", "qwen2.5:7b");
        body.put("clearApiKey", true);
        Map<String, Object> result = service.save(body);

        assertEquals("", gateway.appliedSettings().apiKey());
        assertEquals(ModelSettings.KeyState.ABSENT, gateway.appliedSettings().keyState());
        assertEquals("", result.get("apiKeyMasked"));
        assertTrue(gateway.available());
    }

    @Test
    void responsesOnlyEverCarryTheMaskedKey() {
        ModelConfigStore store = store(environment());
        AdminModelConfigService service = service(store);

        Map<String, Object> saved = service.save(enabledSettings("deepseek-chat"));
        Map<String, Object> current = service.current();

        assertEquals(ConfigValues.MASKED, saved.get("apiKeyMasked"));
        assertEquals(ConfigValues.MASKED, current.get("apiKeyMasked"));
        // 出参里既不能有明文，也不能有密文。
        assertFalse(saved.toString().contains(SECRET), "保存响应不得含明文密钥");
        assertFalse(current.toString().contains(SECRET), "配置响应不得含明文密钥");
        assertFalse(current.toString().contains("api-key-enc"));
        assertEquals("FILE", current.get("source"));
    }

    @Test
    void testProbeSucceedsAgainstARealEndpointAndDoesNotPersistTheCandidate() throws IOException {
        ModelConfigStore store = store(environment());
        AdminModelConfigService service = service(store);
        startServer(200, chatCompletionBody());

        Map<String, Object> body = new HashMap<>();
        body.put("enabled", true);
        body.put("baseUrl", localBaseUrl());
        body.put("model", "probe-model");
        body.put("apiKey", SECRET);
        Map<String, Object> result = service.test(body);

        assertEquals(true, result.get("ok"));
        assertEquals("", result.get("errorCode"));
        assertEquals("probe-model", result.get("model"));
        assertEquals("127.0.0.1", result.get("baseUrlHost"));
        assertTrue((Long) result.get("latencyMs") >= 0);
        // 测试只是探活：候选值不能落盘，也不能变成生效配置。
        assertFalse(store.current().configured());
        assertEquals(ModelSettings.Source.NONE, store.current().source());
    }

    @Test
    void testProbeClassifiesUnauthorizedAndNetworkFailures() throws IOException {
        AdminModelConfigService service = service(store(environment()));
        startServer(401, "{\"error\":{\"message\":\"Incorrect API key provided: " + SECRET
                + "\",\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}");

        Map<String, Object> candidate = new HashMap<>();
        candidate.put("enabled", true);
        candidate.put("baseUrl", localBaseUrl());
        candidate.put("model", "probe-model");
        candidate.put("apiKey", "sk-wrongwrongwrong");

        Map<String, Object> unauthorized = service.test(candidate);
        assertEquals(false, unauthorized.get("ok"));
        assertEquals("AUTH", unauthorized.get("errorCode"));
        // 服务商回显了密钥形态的串时，必须清洗掉再出参。
        assertFalse(String.valueOf(unauthorized.get("message")).contains("sk-wrongwrongwrong"));
        assertFalse(String.valueOf(unauthorized.get("message")).contains(SECRET));

        // 关掉服务器 → 连接被拒 → 归到「网络不通」，而不是笼统的 UNKNOWN。
        stopServer();
        Map<String, Object> network = service.test(candidate);
        assertEquals(false, network.get("ok"));
        assertEquals("NETWORK", network.get("errorCode"));
        assertEquals("127.0.0.1", network.get("baseUrlHost"));
    }

    @Test
    void verifyReportsNotConfiguredWhenNothingWasSaved() {
        AdminModelConfigService service = service(store(environment()));

        Map<String, Object> result = service.verify();

        assertEquals(false, result.get("ok"));
        assertEquals("NOT_CONFIGURED", result.get("errorCode"));
        assertEquals("", result.get("model"));
    }

    @Test
    void verifyReportsUnavailableWhenTheStoredKeyCannotBeDecrypted() throws IOException {
        Path file = tempDir.resolve("admin-model.properties");
        Files.writeString(file, "enabled=true\nbase-url=https://api.deepseek.com\nmodel=deepseek-chat\n"
                + "timeout-seconds=30\napi-key-enc=bm90LWEtcmVhbC1jaXBoZXJ0ZXh0\n", StandardCharsets.UTF_8);
        ModelConfigStore store = store(environment());
        assertEquals(ModelSettings.KeyState.UNREADABLE, store.current().keyState());

        Map<String, Object> result = service(store).verify();

        assertEquals(false, result.get("ok"));
        assertEquals("UNAVAILABLE", result.get("errorCode"));
        assertEquals("deepseek-chat", result.get("model"));
        assertEquals("api.deepseek.com", result.get("baseUrlHost"));
        assertTrue(String.valueOf(result.get("message")).contains("解密"));
    }

    @Test
    void verifyRunsAgainstTheAppliedConfigurationAndReportsTheBuildId() throws IOException {
        ModelConfigStore store = store(environment());
        AdminChatModelGateway gateway = gateway(store);
        AdminModelConfigService service = new AdminModelConfigService(store, gateway);
        startServer(200, chatCompletionBody());

        Map<String, Object> body = new HashMap<>();
        body.put("enabled", true);
        body.put("baseUrl", localBaseUrl());
        body.put("model", "probe-model");
        body.put("apiKey", SECRET);
        service.save(body);

        Map<String, Object> result = service.verify();

        assertEquals(true, result.get("ok"));
        assertEquals("probe-model", result.get("model"));
        assertEquals(gateway.buildId(), result.get("buildId"));
        assertNotEquals("", String.valueOf(result.get("appliedAt")));
    }

    private Map<String, Object> enabledSettings(String model) {
        Map<String, Object> body = new HashMap<>();
        body.put("enabled", true);
        body.put("baseUrl", "https://api.deepseek.com");
        body.put("model", model);
        body.put("apiKey", SECRET);
        return body;
    }

    private AdminModelConfigService service(ModelConfigStore store) {
        return new AdminModelConfigService(store, gateway(store));
    }

    private ModelConfigStore store(MockEnvironment environment) {
        AdminModelProperties properties = new AdminModelProperties();
        properties.setConfigFile(tempDir.resolve("admin-model.properties").toString());
        properties.setMasterKeyFile(tempDir.resolve("master.key").toString());
        ModelConfigStore store = new ModelConfigStore(properties, environment,
                new SecretCipher("", tempDir.resolve("master.key")));
        store.init();
        return store;
    }

    /** 未被环境变量播种的起点：文件为空且开关关闭，一切从零开始。 */
    private static MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("spring.ai.model.chat", "none")
                .withProperty("spring.ai.openai.base-url", "")
                .withProperty("spring.ai.openai.api-key", "")
                .withProperty("spring.ai.openai.chat.model", "");
    }

    private static AdminChatModelGateway gateway(ModelConfigStore store) {
        AdminChatModelGateway gateway = new AdminChatModelGateway(store, toolManagers());
        gateway.apply(store.current());
        return gateway;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ToolCallingManager> toolManagers() {
        ObjectProvider<ToolCallingManager> managers = mock(ObjectProvider.class);
        when(managers.getIfAvailable(any(Supplier.class))).thenReturn(DefaultToolCallingManager.builder().build());
        return managers;
    }

    private void startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
    }

    private String localBaseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String chatCompletionBody() {
        return "{\"id\":\"chatcmpl-probe\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"probe-model\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,"
                + "\"total_tokens\":2}}";
    }
}