package com.rover.admin.agent.model;

import com.rover.agent.runtime.llm.ChatModelGateway;
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
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Admin 的 OpenAI 兼容模型适配器：把页面保存的配置变成运行层可用的 {@link ChatClient}，
 * 并按"先构建成功再替换引用"的方式支持保存即生效（无需重启）。
 *
 * 这里刻意不依赖 Spring AI 的自动配置：{@code spring.ai.model.chat} 保持 {@code none}，
 * 全进程只有这一条模型构建路径，{@code available()} 才不会因为存在第二个 ChatModel 而失真。
 */
@Component
public class AdminChatModelGateway implements ChatModelGateway {

    private static final Logger log = LoggerFactory.getLogger(AdminChatModelGateway.class);

    private final ModelConfigStore store;
    private final ObjectProvider<ToolCallingManager> toolCallingManagers;

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
        this.store = store;
        this.toolCallingManagers = toolCallingManagers;
    }

    @PostConstruct
    void applyStoredSettings() {
        apply(store.current());
    }

    /**
     * 应用一份配置。
     *
     * 构建成功才替换生效引用；构建失败只记 {@link #lastError()}，保留上一个能用的客户端，
     * 避免一次改错把可用的诊断链弄坏。密钥解不开时明确停用（configured 为真、available 为假），
     * 因为这是配置状态问题，必须被看见，而不是假装"尚未配置模型"。
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

    /**
     * 按场景超时上限取客户端：只收紧不放宽，请求值不小于配置超时时直接返回当前客户端。
     *
     * 更短的上限才另建一个客户端并缓存下来（同一场景的下一次调用直接复用），
     * 因此这条路径不会每次调用都新建模型对象。
     */
    @Override
    public ChatClient chatClient(int timeoutSeconds) {
        ModelSettings settings = applied;
        // 配置了快速模型：廉价调用（意图/目标/规划）一律路由到快速模型，不再用思考模型硬扛。
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
     * 用一份配置建一个覆写超时的客户端：不落盘，也不改动当前生效配置。
     *
     * 页面连通性测试与运行层的场景超时客户端都走这里。
     *
     * @param timeoutSeconds 覆写的超时；越界值按 {@link ModelSettings#clampTimeout(int)} 收敛
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
     * 构建客户端。
     *
     * @param thinking 是否允许深度思考。只有主客户端开——它服务的是「AI 解读」这类需要推理质量的长调用；
     *                 场景客户端走 {@code false}：意图识别、目标解析、规划都是 10 秒上限的廉价调用，
     *                 让它们先想一遍既拖慢用户等待，也更容易撞上超时后回退规则，
     *                 反而丢掉了模型本该贡献的那点判断
     */
    protected ChatClient build(ModelSettings settings, boolean thinking) {
        ChatModel model = switch (vendorOf(settings.baseUrl())) {
            case ZHIPU, DEEPSEEK -> thinkingCapableModel(settings, thinking);
            case OPENAI_COMPATIBLE -> openAiCompatibleModel(settings);
        };
        return ChatClient.builder(model).build();
    }

    /**
     * 接入协议：由服务地址决定，用户只填地址与模型名，不必理解协议差异。
     *
     * 智谱与 DeepSeek 共走一条「能解析思考内容」的构建路径——两家的思考内容都放在
     * {@code delta.reasoning_content}，同属一套 Chat Completions 约定；其余服务走通用兼容协议。
     */
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
     * 能解析思考内容的 OpenAI 兼容客户端（智谱与 DeepSeek 共用）。
     *
     * 这里用 DeepSeek 的客户端实现，把它当作「一个会解析 {@code reasoning_content} 的
     * OpenAI 兼容客户端」，只换 baseUrl 与模型名。之所以不用智谱自己的
     * {@code ZhiPuAiChatModel}：{@code spring-ai-zhipuai} 的可用版本（2.0.0-M1～M4）
     * 都停留在 Spring AI 2.0.0-M1 的 API 上，连 {@code ModelOptionsUtils.copyToTarget/merge}
     * 这类基础方法在 2.0.1 都已被移除——补类型救不了：类加载能过，一调用就
     * {@code NoSuchMethodError}。两家的请求与响应结构一致，换 baseUrl 即可。
     *
     * 等智谱模块发布与 2.0.1 对齐的版本，把它换成 {@code ZhiPuAiChatModel} 即可。
     * 模型名支持深度思考时才带 thinking 参数——不认识的模型名一律不带，
     * 不传这个参数永远不会因参数不兼容而失败。
     */
    private ChatModel thinkingCapableModel(ModelSettings settings, boolean thinking) {
        DeepSeekChatOptions.Builder options = DeepSeekChatOptions.builder();
        options.model(settings.model());
        if (supportsThinking(settings.model())) {
            if (thinking) {
                options.enableThinking();
            } else {
                // 关键：glm 系列不传 thinking 参数时是「默认开启思考」的。意图识别、目标解析、规划
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

    /** 优先用自动配置的实例：它带着 spring.ai.tools.limits.* 的调用上限。 */
    private ToolCallingManager toolCallingManager() {
        return toolCallingManagers.getIfAvailable(() -> DefaultToolCallingManager.builder().build());
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
     * 带超时的 HTTP 客户端：原生协议的选项里没有超时字段，等待上限只能落在 HTTP 层。
     *
     * 这层超时是必要的——意图识别这类场景靠 {@code chatClient(timeoutSeconds)} 收紧等待，
     * 底层不设超时那层收紧就形同虚设。这条 RestClient 只服务非流式调用（意图识别、目标解析、
     * 规划、连接测试），流式对话走另一条 WebClient 路径，不经过这里。
     *
     * 用 {@link JdkClientHttpRequestFactory}（JDK 的 {@link HttpClient}）而不是
     * {@code SimpleClientHttpRequestFactory}：后者基于 {@code HttpURLConnection}，读超时后
     * keep-alive 连接可能被复用为半开连接，重试会读到上一个请求的残留响应、把
     * content-type 解析成 {@code application/octet-stream}。JDK 客户端连接管理更稳，不会复用坏连接。
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

    /**
     * 该模型是否支持深度思考。
     *
     * 按模型名判断而不是加一个配置开关：模型名本身就是最准确的判据，多一个开关只会多一处
     * 「配错就报错」的地方。不认识的模型名一律不开——不传这个参数，永远不会因参数不兼容而失败。
     */
    private static boolean supportsThinking(String model) {
        String name = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        return name.startsWith("glm-4.5") || name.startsWith("glm-4.6") || name.startsWith("glm-4.7")
                || name.startsWith("glm-5") || name.startsWith("glm-z1");
    }

    /**
     * 取一帧响应里的思考增量。
     *
     * 只有能解析 {@code reasoning_content} 的消息类型带这个字段（见
     * {@link #thinkingCapableModel}）；取不到一律返回空串——思考内容只是解释过程的陪衬，
     * 不能因为它缺失或类型不符而让一次解读失败。
     */
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