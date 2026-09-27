package com.rover.admin.agent.model;

import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.errors.UnexpectedStatusCodeException;
import com.rover.common.config.ConfigValues;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 模型配置用例：读取当前状态、保存并即时生效、连接测试与效果验证。
 *
 * 密钥只以明文出现在请求体与内存里；对外响应一律走 {@link ConfigValues#MASKED}，
 * 日志与出参都先做敏感串清洗。
 */
@Service
public class AdminModelConfigService {

    private static final Logger log = LoggerFactory.getLogger(AdminModelConfigService.class);

    /** 连接测试的超时：页面不能等太久，8 秒足够区分"通"、"鉴权失败"和"网络不通"。 */
    private static final int PROBE_TIMEOUT_SECONDS = 8;
    private static final String PROBE_PROMPT = "只回复 ok，不要解释。";
    private static final int MAX_URL_LENGTH = 300;
    private static final int MAX_MODEL_LENGTH = 120;
    private static final Pattern SECRET_SHAPE = Pattern.compile("sk-[A-Za-z0-9_\\-]{8,}");

    private final ModelConfigStore store;
    private final AdminChatModelGateway gateway;

    public AdminModelConfigService(ModelConfigStore store, AdminChatModelGateway gateway) {
        this.store = store;
        this.gateway = gateway;
    }

    /** 当前配置 + 生效状态，供页面渲染；不含任何明文密钥。 */
    public Map<String, Object> current() {
        ModelSettings settings = store.current();
        FastModel fast = settings.fast() == null ? FastModel.none() : settings.fast();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", settings.enabled());
        body.put("baseUrl", settings.baseUrl());
        body.put("model", settings.model());
        body.put("timeoutSeconds", settings.timeoutSeconds());
        body.put("source", settings.source().name());
        body.put("configured", gateway.configured());
        body.put("available", gateway.available());
        body.put("applied", gateway.appliedSettings().equals(settings));
        body.put("description", gateway.description());
        body.put("apiKeyMasked", settings.apiKey() == null || settings.apiKey().isBlank() ? "" : ConfigValues.MASKED);
        body.put("apiKeyReadable", settings.keyState() != ModelSettings.KeyState.UNREADABLE);
        body.put("masterKeyState", settings.keyState() == ModelSettings.KeyState.UNREADABLE ? "MISMATCH" : "OK");
        body.put("buildId", gateway.buildId());
        body.put("appliedAt", gateway.appliedAt() == null ? "" : gateway.appliedAt().toString());
        body.put("lastError", gateway.lastError() == null ? "" : gateway.lastError());
        body.put("configFile", store.file().toString());
        // 厂商收敛：用户只选厂商，映射出的 baseUrl / 主模型 / 快速模型都作为只读展示，不再要求手填模型名。
        body.put("vendor", ModelVendor.infer(settings.baseUrl()).code());
        body.put("vendors", vendors());
        body.put("presets", presets());
        body.put("fastPresets", fastPresets());
        // 快速模型：承接廉价调用，可选；未配置时页面留空即可。
        body.put("fastConfigured", settings.fastConfigured());
        body.put("fastBaseUrl", fast.baseUrl());
        body.put("fastModel", fast.model());
        body.put("fastApiKeyMasked", fast.apiKey() == null || fast.apiKey().isBlank() ? "" : ConfigValues.MASKED);
        return body;
    }

    /** 厂商清单：供页面渲染下拉；不含任何密钥。 */
    private static List<Map<String, Object>> vendors() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ModelVendor vendor : ModelVendor.list()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", vendor.code());
            item.put("label", vendor.label());
            item.put("baseUrl", vendor.baseUrl());
            item.put("mainModel", vendor.mainModel());
            item.put("fastModel", vendor.fastModel());
            result.add(item);
        }
        return result;
    }

    /** 保存并立即生效；返回保存后的完整状态与提示文案。 */
    public Map<String, Object> save(Map<String, Object> body) {
        ModelSettings existing = store.current();
        ModelSettings candidate = candidate(body, existing);
        ModelSettings before = existing;
        store.persist(candidate);
        gateway.apply(store.current());
        log.info("模型配置已更新：{}", describeChange(before, store.current()));
        Map<String, Object> result = current();
        result.put("message", gateway.available()
                ? "模型配置已生效，无需重启 Admin。"
                : "模型配置已保存，但当前不可用，请查看错误信息。");
        return result;
    }

    /** 用提交的候选值做一次连通性测试；不落盘、不影响当前生效配置。 */
    public Map<String, Object> test(Map<String, Object> body) {
        ModelSettings candidate = candidate(body, store.current());
        return probe(candidate, gateway.transientClient(candidate, PROBE_TIMEOUT_SECONDS));
    }

    /** 对当前已生效的配置再跑一次，用来证明"生效的正是这次保存的配置"。 */
    public Map<String, Object> verify() {
        ModelSettings applied = gateway.appliedSettings();
        if (!gateway.configured()) {
            Map<String, Object> body = failed("NOT_CONFIGURED", "尚未配置模型，请先填写并保存。");
            body.put("model", "");
            body.put("baseUrlHost", "");
            return body;
        }
        if (!gateway.available()) {
            Map<String, Object> body = failed("UNAVAILABLE",
                    gateway.lastError() == null ? "模型当前不可用。" : gateway.lastError());
            body.put("model", applied.model());
            body.put("baseUrlHost", host(applied.baseUrl()));
            return body;
        }
        Map<String, Object> body = probe(applied, gateway.chatClient());
        body.put("buildId", gateway.buildId());
        body.put("appliedAt", gateway.appliedAt() == null ? "" : gateway.appliedAt().toString());
        return body;
    }

    /** 把请求体解析成候选配置，并做启用态下的必要校验；密钥为掩码时沿用已存的。 */
    private ModelSettings candidate(Map<String, Object> body, ModelSettings existing) {
        boolean enabled = asBoolean(body.get("enabled"), existing.enabled());
        int timeout = asInt(body.get("timeoutSeconds"), existing.timeoutSeconds());
        String apiKey = resolveApiKey(body, existing);
        // 厂商收敛：用户只选厂商，地址 / 主模型 / 快速模型由映射解析。
        // 未显式传厂商（旧请求 / 测试）时，优先从本次提交的地址反推——否则从 DeepSeek 切到本地 Ollama
        // 会被反推回 DeepSeek，把用户新填的本地地址吞掉。没提交地址才退回已存地址。
        ModelVendor vendor;
        if (body.containsKey("vendor")) {
            vendor = ModelVendor.of(asText(body.get("vendor"), null));
        } else {
            String submitted = body.containsKey("baseUrl")
                    ? trim(asText(body.get("baseUrl"), null))
                    : existing.baseUrl();
            vendor = ModelVendor.infer(submitted);
        }

        String baseUrl;
        String model;
        FastModel fast;
        if (vendor == ModelVendor.CUSTOM) {
            // 本地 / 代理网关没有「厂商」概念，地址与模型名手填。
            baseUrl = trim(asText(body.get("baseUrl"), existing.baseUrl()));
            model = trim(asText(body.get("model"), existing.model()));
            fast = resolveFast(body, existing);
        } else {
            baseUrl = vendor.baseUrl();
            model = vendor.mainModel();
            // 同厂商同 key：快速模型复用主模型密钥；无单独快速模型（如 deepseek）则留空。
            fast = vendor.fastModel().isEmpty()
                    ? FastModel.none()
                    : new FastModel(vendor.baseUrl(), apiKey, vendor.fastModel());
        }
        if (enabled) {
            if (baseUrl.isEmpty()) {
                throw new IllegalArgumentException("启用模型时必须填写服务地址");
            }
            if (model.isEmpty()) {
                throw new IllegalArgumentException("启用模型时必须填写模型名");
            }
        }
        if (baseUrl.length() > MAX_URL_LENGTH || model.length() > MAX_MODEL_LENGTH) {
            throw new IllegalArgumentException("服务地址或模型名过长");
        }
        if (!baseUrl.isEmpty() && !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("服务地址需以 http:// 或 https:// 开头");
        }
        return new ModelSettings(enabled, baseUrl, apiKey, model, ModelSettings.clampTimeout(timeout),
                ModelSettings.Source.FILE, ModelSettings.keyStateOf(apiKey), fast);
    }

    /**
     * 解析快速模型（可选）：地址或模型名任一为空视为未配置（整组清空，避免半截配置）。
     * 快速模型不校验启用态——它随主模型一起启停，主模型关闭时自然不生效。
     */
    private static FastModel resolveFast(Map<String, Object> body, ModelSettings existing) {
        FastModel current = existing.fast() == null ? FastModel.none() : existing.fast();
        String fastBaseUrl = trim(asText(body.get("fastBaseUrl"), current.baseUrl()));
        String fastModel = trim(asText(body.get("fastModel"), current.model()));
        if (fastBaseUrl.isEmpty() || fastModel.isEmpty()) {
            return FastModel.none();
        }
        if (fastBaseUrl.length() > MAX_URL_LENGTH || fastModel.length() > MAX_MODEL_LENGTH) {
            throw new IllegalArgumentException("快速模型服务地址或模型名过长");
        }
        if (!fastBaseUrl.startsWith("http://") && !fastBaseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("快速模型服务地址需以 http:// 或 https:// 开头");
        }
        return new FastModel(fastBaseUrl, resolveFastApiKey(body, current), fastModel);
    }

    /** 快速模型密钥：掩码保持原值；clearFastApiKey 显式清除；缺省沿用已存。 */
    private static String resolveFastApiKey(Map<String, Object> body, FastModel existing) {
        if (asBoolean(body.get("clearFastApiKey"), false)) {
            return "";
        }
        if (!body.containsKey("fastApiKey")) {
            return existing.apiKey();
        }
        String submitted = asText(body.get("fastApiKey"), existing.apiKey());
        if (ConfigValues.MASKED.equals(submitted)) {
            return existing.apiKey();
        }
        return submitted == null ? "" : submitted.trim();
    }

    /** 掩码视为"保持原密钥"；clearApiKey 显式清除。 */
    private static String resolveApiKey(Map<String, Object> body, ModelSettings existing) {
        if (asBoolean(body.get("clearApiKey"), false)) {
            return "";
        }
        if (!body.containsKey("apiKey")) {
            return existing.apiKey();
        }
        String submitted = asText(body.get("apiKey"), existing.apiKey());
        if (ConfigValues.MASKED.equals(submitted)) {
            return existing.apiKey();
        }
        return submitted == null ? "" : submitted.trim();
    }

    private Map<String, Object> probe(ModelSettings settings, ChatClient client) {
        long started = System.nanoTime();
        try {
            String answer = client.prompt().user(PROBE_PROMPT).call().content();
            long latency = latencyMs(started);
            log.info("模型连接测试成功：{} 用时 {}ms", settings.model(), latency);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("latencyMs", latency);
            body.put("model", settings.model());
            body.put("baseUrlHost", host(settings.baseUrl()));
            body.put("errorCode", "");
            body.put("message", answer == null || answer.isBlank() ? "连接正常（模型返回空内容）" : "连接正常");
            return body;
        } catch (RuntimeException ex) {
            long latency = latencyMs(started);
            String code = classify(ex);
            String message = sanitize(ex.getMessage());
            log.warn("模型连接测试失败：{} code={} 用时 {}ms", settings.model(), code, latency);
            Map<String, Object> body = failed(code, message.isEmpty() ? "模型调用失败" : message);
            body.put("latencyMs", latency);
            body.put("model", settings.model());
            body.put("baseUrlHost", host(settings.baseUrl()));
            return body;
        }
    }

    private static Map<String, Object> failed(String errorCode, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("latencyMs", -1L);
        body.put("errorCode", errorCode);
        body.put("message", message);
        return body;
    }

    /** 按异常链归类，便于页面直接给处置建议而不是丢一坨堆栈。 */
    private static String classify(RuntimeException ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof UnauthorizedException) {
                return "AUTH";
            }
            if (current instanceof PermissionDeniedException) {
                return "PERMISSION";
            }
            if (current instanceof RateLimitException) {
                return "QUOTA";
            }
            if (current instanceof NotFoundException) {
                return "MODEL_NOT_FOUND";
            }
            if (current instanceof BadRequestException) {
                return "BAD_REQUEST";
            }
            if (current instanceof OpenAIIoException) {
                return "NETWORK";
            }
            if (current instanceof InternalServerException || current instanceof UnexpectedStatusCodeException) {
                return "SERVER";
            }
            if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException) {
                return "TIMEOUT";
            }
            if (current == current.getCause()) {
                break;
            }
        }
        if (ex instanceof OpenAIIoException) {
            return "NETWORK";
        }
        String text = String.valueOf(ex.getMessage()).toLowerCase(Locale.ROOT);
        if (text.contains("timeout") || text.contains("timed out")) {
            return "TIMEOUT";
        }
        return "UNKNOWN";
    }

    /** 出参前清洗密钥形态的串，避免把凭据回显到页面或日志。 */
    private static String sanitize(String message) {
        if (message == null) {
            return "";
        }
        String cleaned = SECRET_SHAPE.matcher(message).replaceAll(ConfigValues.MASKED);
        return cleaned.length() > 300 ? cleaned.substring(0, 300) : cleaned;
    }

    private static long latencyMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static String describeChange(ModelSettings before, ModelSettings after) {
        List<String> changes = new ArrayList<>();
        if (before.enabled() != after.enabled()) {
            changes.add("enabled=" + after.enabled());
        }
        if (!before.baseUrl().equals(after.baseUrl())) {
            changes.add("baseUrl=" + after.baseUrl());
        }
        if (!before.model().equals(after.model())) {
            changes.add("model=" + after.model());
        }
        if (before.timeoutSeconds() != after.timeoutSeconds()) {
            changes.add("timeoutSeconds=" + after.timeoutSeconds());
        }
        if (!Objects.equals(before.apiKey(), after.apiKey())) {
            changes.add("apiKey=" + (after.apiKey() == null || after.apiKey().isBlank() ? "(已清除)" : ConfigValues.MASKED));
        }
        return changes.isEmpty() ? "无字段变化" : String.join(" ", changes);
    }

    private static List<Map<String, Object>> presets() {
        return toPresets(ModelPresets.ALL);
    }

    private static List<Map<String, Object>> fastPresets() {
        return toPresets(ModelPresets.FAST_ALL);
    }

    private static List<Map<String, Object>> toPresets(List<ModelPresets.Preset> source) {
        List<Map<String, Object>> presets = new ArrayList<>();
        for (ModelPresets.Preset preset : source) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", preset.label());
            item.put("baseUrl", preset.baseUrl());
            item.put("model", preset.model());
            presets.add(item);
        }
        return presets;
    }

    private static String host(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            return uri.getHost() == null ? baseUrl : uri.getHost();
        } catch (IllegalArgumentException ex) {
            return baseUrl;
        }
    }

    private static boolean asBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text.trim());
        }
        return fallback;
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String asText(Object value, String fallback) {
        return value instanceof String text ? text : fallback;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}