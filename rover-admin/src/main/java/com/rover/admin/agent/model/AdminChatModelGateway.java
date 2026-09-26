package com.rover.admin.agent.model;

import com.rover.agent.runtime.llm.ChatModelGateway;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

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
    private volatile String lastError;
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
            this.lastError = null;
            return;
        }
        if (candidate.keyState() == ModelSettings.KeyState.UNREADABLE) {
            this.applied = candidate;
            this.client = null;
            this.lastError = "API 密钥无法解密，请重新填写";
            log.warn("模型 API 密钥无法解密，模型停用");
            return;
        }
        try {
            ChatClient built = build(candidate);
            this.applied = candidate;
            this.client = built;
            this.lastError = null;
            this.buildId = buildSequence.incrementAndGet();
            this.appliedAt = Instant.now();
            log.info("模型已生效：{}（buildId={}）", description(), buildId);
        } catch (RuntimeException ex) {
            this.lastError = "模型客户端构建失败：" + ex.getMessage();
            log.warn("模型客户端构建失败，继续使用上一个生效配置：{}", ex.getMessage());
        }
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

    @Override
    public String description() {
        if (!applied.configured()) {
            return "未配置模型";
        }
        return applied.model() + " @ " + host(applied.baseUrl());
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
     * 用候选项建一个一次性客户端做连通性测试：不落盘，也不改动当前生效配置。
     *
     * @param timeoutSeconds 探测用的超时；覆盖候选项里的值，避免页面等了半分钟才报错
     */
    public ChatClient transientClient(ModelSettings candidate, int timeoutSeconds) {
        return build(new ModelSettings(candidate.enabled(), candidate.baseUrl(), candidate.apiKey(), candidate.model(),
                ModelSettings.clampTimeout(timeoutSeconds), candidate.source(), candidate.keyState()));
    }

    /** 构建客户端；构建不成功必须抛异常，由 {@link #apply(ModelSettings)} 决定是否保留上一个可用客户端。 */
    protected ChatClient build(ModelSettings settings) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(settings.baseUrl())
                // 空串（非 null）会走成无鉴权客户端，正是本地 Ollama / vLLM 需要的形态。
                .apiKey(settings.apiKey() == null ? "" : settings.apiKey())
                .model(settings.model())
                .timeout(Duration.ofSeconds(ModelSettings.clampTimeout(settings.timeoutSeconds())))
                .maxRetries(0)
                .build();
        ChatModel model = OpenAiChatModel.builder()
                .options(options)
                .observationRegistry(ObservationRegistry.NOOP)
                .meterRegistry(new SimpleMeterRegistry())
                // 优先用自动配置的实例：它带着 spring.ai.tools.limits.* 的调用上限。
                .toolCallingManager(toolCallingManagers.getIfAvailable(() -> DefaultToolCallingManager.builder().build()))
                .build();
        return ChatClient.builder(model).build();
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