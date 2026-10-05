package com.rover.admin.agent.model;

import com.rover.agent.runtime.llm.ChatModelGateway;
import com.rover.agent.runtime.llm.TracingToolCallingManager;
import com.rover.agent.runtime.task.AgentRunLimits;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 将模型配置转换为 {@link ChatClient}，构建成功后替换当前客户端。
 * 模型由本类显式创建，禁用 Spring AI 模型自动配置。
 */
@Component
public class AdminChatModelGateway implements ChatModelGateway {

    private static final Logger log = LoggerFactory.getLogger(AdminChatModelGateway.class);

    private final ModelConfigStore store;
    private final ObjectProvider<ToolCallingManager> toolCallingManagers;
    private final AgentRunLimits runLimits;

    private volatile ModelSettings applied = ModelSettings.none();
    private volatile ChatClient client;
    /** 快速模型客户端：承接廉价调用；未配置或构建失败时为 null（回退主模型）。 */
    private volatile ChatClient fastClient;
    private volatile String lastError;
    /** 场景超时客户端按超时秒数缓存；配置每次成功生效时整体换新，避免复用旧配置建出的客户端。 */
    private volatile Map<Integer, ChatClient> sceneClients = new ConcurrentHashMap<>();
    /** 快速模型的场景超时客户端缓存。 */
    private volatile Map<Integer, ChatClient> fastSceneClients = new ConcurrentHashMap<>();
    private final AtomicLong buildSequence = new AtomicLong();
    private volatile long buildId;
    private volatile Instant appliedAt;

    public AdminChatModelGateway(ModelConfigStore store, ObjectProvider<ToolCallingManager> toolCallingManagers) {
        this(store, toolCallingManagers, AgentRunLimits.defaults());
    }

    @Autowired
    public AdminChatModelGateway(ModelConfigStore store, ObjectProvider<ToolCallingManager> toolCallingManagers,
                                AgentRunLimits runLimits) {
        this.store = store;
        this.toolCallingManagers = toolCallingManagers;
        this.runLimits = runLimits;
    }

    @PostConstruct
    void applyStoredSettings() {
        apply(store.current());
    }

    /**
     * 构建成功后应用配置；失败保留原客户端并记录 lastError。
     * 密钥解密失败时停用客户端，保留已配置状态。
     */
    public void apply(ModelSettings candidate) {
        if (!candidate.configured()) {
            this.applied = candidate;
            this.client = null;
            this.fastClient = null;
            this.sceneClients = new ConcurrentHashMap<>();
            this.fastSceneClients = new ConcurrentHashMap<>();
            this.lastError = null;
            return;
        }
        if (candidate.keyState() == ModelSettings.KeyState.UNREADABLE) {
            this.applied = candidate;
            this.client = null;
            this.fastClient = null;
            this.sceneClients = new ConcurrentHashMap<>();
            this.fastSceneClients = new ConcurrentHashMap<>();
            this.lastError = "API 密钥无法解密，请重新填写";
            log.warn("模型 API 密钥无法解密，模型停用");
            return;
        }
        try {
            ChatClient built = build(candidate);
            ChatClient fastBuilt = null;
            if (candidate.fastConfigured()) {
                try {
                    fastBuilt = buildFast(candidate, candidate.timeoutSeconds());
                } catch (RuntimeException | LinkageError ex) {
                    // 快速模型只是加速项：构建失败只降级（fastClient=null → 回退主模型），不拖累主诊断链。
                    log.warn("快速模型客户端构建失败，廉价调用回退主模型：{}", ex.toString());
                }
            }
            this.applied = candidate;
            this.client = built;
            this.fastClient = fastBuilt;
            this.sceneClients = new ConcurrentHashMap<>();
            this.fastSceneClients = new ConcurrentHashMap<>();
            this.lastError = null;
            this.buildId = buildSequence.incrementAndGet();
            this.appliedAt = Instant.now();
            log.info("模型已生效：{}（buildId={}）", description(), buildId);
        } catch (RuntimeException | LinkageError ex) {
            // LinkageError（类缺失、依赖版本不匹配）也在这里收敛：模型建不起来只是「模型不可用」，
            // 规则诊断与其余功能都不需要模型，不该让一件配置问题把整个 Admin 拖得住不了。
            this.lastError = "模型客户端构建失败：" + describe(ex);
            log.warn("模型客户端构建失败，继续使用上一个生效配置：{}", ex.toString());
        }
    }

    /** 构建失败的说明文本：类加载失败只给出类名，对使用者没有意义，补一句可行动的方向。 */
    private static String describe(Throwable failure) {
        if (failure instanceof LinkageError) {
            return "依赖版本不匹配（缺少 " + failure.getMessage() + "）";
        }
        return failure.getMessage();
    }

    @Override
    public boolean configured() {
        return applied.configured();
    }

    @Override
    public boolean available() {
        return applied.configured() && client != null;
    }

    @Override
    public ChatClient chatClient() {
        ChatClient current = client;
        if (current == null) {
            throw new IllegalStateException(lastError != null ? "模型不可用：" + lastError : "模型未就绪");
        }
        return current;
    }

    /** 按场景收紧超时并缓存客户端；请求上限不小于配置值时复用当前客户端。 */
    @Override
    public ChatClient chatClient(int timeoutSeconds) {
        ModelSettings settings = applied;
        // 配置了快速模型：廉价调用（目标/规划）一律路由到快速模型，不再用思考模型硬扛。
        if (settings.fastConfigured()) {
            return fastScene(timeoutSeconds);
        }
        ChatClient current = chatClient();
        int configured = ModelSettings.clampTimeout(settings.timeoutSeconds());
        if (timeoutSeconds <= 0 || timeoutSeconds >= configured) {
            return current;
        }
        Map<Integer, ChatClient> cache = sceneClients;
        ChatClient cached = cache.get(timeoutSeconds);
        if (cached != null) {
            return cached;
        }
        ChatClient scene = transientClient(settings, timeoutSeconds);
        cache.put(timeoutSeconds, scene);
        return scene;
    }

    /** 配置了快速模型时的廉价调用入口：走快速模型；构建失败则回退主模型场景客户端。 */
    private ChatClient fastScene(int timeoutSeconds) {
        ChatClient current = fastClient;
        if (current == null) {
            return transientClient(applied, timeoutSeconds);
        }
        int configured = ModelSettings.clampTimeout(applied.timeoutSeconds());
        if (timeoutSeconds <= 0 || timeoutSeconds >= configured) {
            return current;
        }
        Map<Integer, ChatClient> cache = fastSceneClients;
        ChatClient cached = cache.get(timeoutSeconds);
        if (cached != null) {
            return cached;
        }
        ChatClient scene = buildFast(applied, timeoutSeconds);
        cache.put(timeoutSeconds, scene);
        return scene;
    }

    /** 用快速模型配置建客户端：不做深度思考，也不落盘、不改当前生效配置。 */
    private ChatClient buildFast(ModelSettings settings, int timeoutSeconds) {
        FastModel fast = settings.fast();
        ModelSettings fastSettings = new ModelSettings(true, fast.baseUrl(), fast.apiKey(), fast.model(),
                ModelSettings.clampTimeout(timeoutSeconds), settings.source(), ModelSettings.keyStateOf(fast.apiKey()));
        return build(fastSettings, false);
    }

    @Override
    public String description() {
        if (!applied.configured()) {
            return "未配置模型";
        }
        String main = applied.model() + " @ " + host(applied.baseUrl());
        if (applied.fastConfigured()) {
            FastModel fast = applied.fast();
            return main + " / 快速 " + fast.model() + " @ " + host(fast.baseUrl());
        }
        return main;
    }

    /** 当前生效的配置（含明文密钥，仅供服务层做差异与掩码，不得直接返回给页面）。 */
    public ModelSettings appliedSettings() {
        return applied;
    }

    /** 生效版本号，每次成功构建自增；用来证明"生效的正是这次保存的配置"。 */
    public long buildId() {
        return buildId;
    }

    /** 最近一次成功生效的时间；从未成功过返回 null。 */
    public Instant appliedAt() {
        return appliedAt;
    }

    /** 最近一次构建/解密失败的原因；当前状态正常时为 null。 */
    public String lastError() {
        return lastError;
    }

    /**
     * 构建覆写超时的客户端，不修改当前配置或文件。
     * @param timeoutSeconds 超时秒数，按 {@link ModelSettings#clampTimeout(int)} 限制范围
     */
    public ChatClient transientClient(ModelSettings settings, int timeoutSeconds) {
        // 场景客户端服务的是「廉价调用」（见 ChatModelGateway#chatClient(int)），因此不开深度思考：
        // 那些调用上限只有 10 秒，让模型先想一遍既拖慢等待，也更容易撞上超时后回退规则。
        return build(new ModelSettings(settings.enabled(), settings.baseUrl(), settings.apiKey(), settings.model(),
                ModelSettings.clampTimeout(timeoutSeconds), settings.source(), settings.keyState()), false);
    }

    /** 构建客户端；构建不成功必须抛异常，由 {@link #apply(ModelSettings)} 决定是否保留上一个可用客户端。 */
    protected ChatClient build(ModelSettings settings) {
        return build(settings, true);
    }

    /**
     * 构建模型客户端。
     * @param thinking 主客户端允许深度思考，场景客户端禁用
     */
    protected ChatClient build(ModelSettings settings, boolean thinking) {
        ChatModel model = switch (vendorOf(settings.baseUrl())) {
            case ZHIPU, DEEPSEEK -> thinkingCapableModel(settings, thinking);
            case OPENAI_COMPATIBLE -> openAiCompatibleModel(settings);
        };
        // 工具循环在 ChatClient 的 advisor 里跑，不走模型上的 ToolCallingManager。
        // 调用号必须从这里绑上，证据才能对上这一次 tool call。
        return ChatClient.builder(model, ObservationRegistry.NOOP, null, null,
                ToolCallingAdvisor.builder().toolCallingManager(toolCallingManager())).build();
    }

    /** 按服务地址选择协议；智谱和 DeepSeek 使用支持 reasoning_content 的客户端。 */
    private enum Vendor {
        ZHIPU,
        DEEPSEEK,
        OPENAI_COMPATIBLE
    }

    private static Vendor vendorOf(String baseUrl) {
        String host = host(baseUrl).toLowerCase(Locale.ROOT);
        if (host.contains("bigmodel.cn") || host.contains("zhipu")) {
            return Vendor.ZHIPU;
        }
        if (host.contains("deepseek.com")) {
            return Vendor.DEEPSEEK;
        }
        return Vendor.OPENAI_COMPATIBLE;
    }

    /**
     * 通过 DeepSeek 客户端接入智谱和 DeepSeek，解析 reasoning_content。
     * 智谱模块的 Spring AI API 版本不兼容；兼容后可改用 ZhiPuAiChatModel。
     * 仅为支持深度思考的模型传递 thinking 参数。
     */
    private ChatModel thinkingCapableModel(ModelSettings settings, boolean thinking) {
        DeepSeekChatOptions.Builder options = DeepSeekChatOptions.builder();
        options.model(settings.model());
        options.maxTokens(runLimits.maxOutputTokens());
        if (supportsThinking(settings.model())) {
            if (thinking) {
                options.enableThinking();
            } else {
                // 关键：glm 系列不传 thinking 参数时是「默认开启思考」的。目标解析、调查规划
                // 这些廉价调用都走 thinking=false，若不显式禁用，会背上 11 秒以上的思考时间，
                // 撞上 10 秒的场景超时，表现就是「每次都超时、重试后转澄清」。显式禁用后降到约 3 秒。
                options.disableThinking();
            }
        }
        DeepSeekApi api = DeepSeekApi.builder()
                .baseUrl(settings.baseUrl())
                .apiKey(key(settings))
                .restClientBuilder(restClient(settings))
                .build();
        return DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .options(options.build())
                .toolCallingManager(toolCallingManager())
                .retryTemplate(noRetry())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }

    /** OpenAI 兼容客户端：本地 Ollama / vLLM 与其余兼容服务都走这条。 */
    private ChatModel openAiCompatibleModel(ModelSettings settings) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(settings.baseUrl())
                .apiKey(key(settings))
                .model(settings.model())
                .maxTokens(runLimits.maxOutputTokens())
                .timeout(Duration.ofSeconds(ModelSettings.clampTimeout(settings.timeoutSeconds())))
                .maxRetries(0)
                .build();
        return OpenAiChatModel.builder()
                .options(options)
                .observationRegistry(ObservationRegistry.NOOP)
                .meterRegistry(new SimpleMeterRegistry())
                .toolCallingManager(toolCallingManager())
                .build();
    }

    /** 空串（非 null）会走成无鉴权客户端，正是本地 Ollama / vLLM 需要的形态。 */
    private static String key(ModelSettings settings) {
        return settings.apiKey() == null ? "" : settings.apiKey();
    }

    /** 优先用自动配置的实例：它带着 spring.ai.tools.limits.* 的调用上限。外包一层，把 toolCallId 写进证据。 */
    private ToolCallingManager toolCallingManager() {
        ToolCallingManager delegate = toolCallingManagers.getIfAvailable(
                () -> DefaultToolCallingManager.builder().build());
        if (delegate instanceof TracingToolCallingManager) {
            return delegate;
        }
        return new TracingToolCallingManager(delegate);
    }

    /**
     * 不重试的重试模板：超时重试由运行层的 {@code QuickModelCall} 统一负责。
     *
     * 两处都重试会让等待时间成倍放大——快速调用本就限了 10 秒上限，底层再叠重试就失去意义。
     */
    private static RetryTemplate noRetry() {
        return new RetryTemplate(RetryPolicy.withMaxRetries(0));
    }

    /**
     * 为非流式调用配置 HTTP 超时；流式调用使用独立 WebClient。
     * 使用 JDK 客户端，避免 HttpURLConnection 在超时后复用异常连接。
     */
    private static RestClient.Builder restClient(ModelSettings settings) {
        Duration timeout = Duration.ofSeconds(ModelSettings.clampTimeout(settings.timeoutSeconds()));
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(factory);
    }

    /** 按模型名判断深度思考支持；未知模型默认禁用。 */
    private static boolean supportsThinking(String model) {
        String name = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        return name.startsWith("glm-4.5") || name.startsWith("glm-4.6") || name.startsWith("glm-4.7")
                || name.startsWith("glm-5") || name.startsWith("glm-z1");
    }

    /** 读取响应帧中的 reasoning_content 增量；字段缺失或类型不符时返回空串。 */
    @Override
    public String reasoningDelta(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return "";
        }
        AssistantMessage message = response.getResult().getOutput();
        if (message instanceof DeepSeekAssistantMessage deepSeek) {
            String reasoning = deepSeek.getReasoningContent();
            return reasoning == null ? "" : reasoning;
        }
        return "";
    }

    /** 只取主机名用于展示，避免把可能带凭据的完整地址写进日志或响应。 */
    private static String host(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            return uri.getHost() == null ? baseUrl : uri.getHost();
        } catch (IllegalArgumentException ex) {
            return baseUrl;
        }
    }
}
