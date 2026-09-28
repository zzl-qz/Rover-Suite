package com.rover.agent.runtime.tool;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.capability.CapabilityExecutor;
import com.rover.agent.core.capability.CapabilityRegistry;
import com.rover.agent.core.model.Step;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.port.ConfigReadPort;
import com.rover.agent.core.port.EventReadPort;
import com.rover.agent.core.port.InstanceReadPort;
import com.rover.agent.core.port.KnowledgeEntry;
import com.rover.agent.core.port.KnowledgeReadPort;
import com.rover.agent.core.port.LogEntry;
import com.rover.agent.core.port.LogQueryPort;
import com.rover.agent.core.port.LogRequest;
import com.rover.agent.core.port.MetricReadPort;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.port.TraceReadPort;
import com.rover.agent.core.snapshot.ConfigEntrySnapshot;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.GatewayMetricSnapshot;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RegistryEventSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.core.snapshot.RouteUpstreamSnapshot;
import com.rover.agent.core.snapshot.TraceRow;
import com.rover.agent.core.snapshot.TraceSnapshot;
import com.rover.agent.runtime.ToolLoopService;
import com.rover.agent.runtime.llm.ChatModelGateway;
import com.rover.agent.runtime.llm.ConversationModel;
import com.rover.agent.runtime.task.InvestigationTask;
import com.rover.agent.runtime.task.InvestigationTaskRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import io.micrometer.observation.ObservationRegistry;

/**
 * 工具选择评测（手动运行，不是单元测试）。
 *
 * <p><b>测的是哪一层</b>：目标解析评测回答「有没有找对对象」，本评测回答「有没有调对工具」。
 * 这一层没有兜底——目标解析判错会退化成转澄清，工具选错则会取回不相干的证据，模型照样能
 * 基于错证据给出一份有依据感的结论。因此它必须被量化，而不是只靠「端到端看起来没问题」。
 *
 * <p><b>为什么不是单测</b>：与 {@code TargetInterpreterEval} 同一口径——依赖真实模型、结果不可复现，
 * 写进单测只会随机红。它是带 main 的评测程序，需要人显式跑一次并把数字抄进文档。
 *
 * <p><b>跑的是生产链路</b>：提示词、工具集与循环都是 {@link ToolLoopService} 与 {@link OpsTools} 的原物，
 * 本程序只换掉 {@link ConversationModel}（记录模型请求了哪些工具，用于交叉核对），
 * 判定则以任务落痕为准——取数必经 {@code CapabilityExecutor}，因此「执行过的能力 + 步骤详情」
 * 是工具被真正调用的可信证据，比流式帧更靠得住。
 *
 * <p><b>标注答案不经过模型</b>：期望由「这个问题要回答的事实只能来自哪份数据」这一机械规则决定，
 * 与模型输出无关，因此不存在用模型生成答案再考模型的循环论证。
 */
public class ToolSelectionEval {

    private static final String BASE_URL = "https://open.bigmodel.cn/api/paas/v4";
    private static final long NOW = System.currentTimeMillis();
    /** 单条上限：正常条目 10~30s 完成，超过这个数基本是连接出问题了，不该继续等。 */
    private static final int CASE_TIMEOUT_SECONDS = 180;

    /** 能力名与工具的对应：一个能力可能对应多个工具，靠步骤详情里的取数口径区分。 */
    private static final Set<String> ALL_CAPABILITIES = Set.of("ROUTE_QUERY", "INSTANCE_QUERY",
            "GATEWAY_METRICS_QUERY", "TRACE_QUERY", "CONFIG_READ", "EVENT_QUERY", "LOG_QUERY",
            "KNOWLEDGE_RETRIEVAL");

    /**
     * @param required 必调能力 → 步骤详情里应出现的文本（空串表示只要求调到，不判参数）
     * @param allowed  允许额外调用的能力（开放式调查本就该多查，不算多调）
     */
    private record Case(String id, String tier, String question, Map<String, String> required, Set<String> allowed) {

        boolean isNegative() {
            return required.isEmpty();
        }
    }

    private static List<Case> cases() {
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("E01", "状态", "网关现在 QPS 多少？",
                Map.of("GATEWAY_METRICS_QUERY", ""), Set.of()));
        cases.add(new Case("E02", "状态", "/api/order 最近一分钟有多少 5xx？",
                Map.of("GATEWAY_METRICS_QUERY", "/api/order"), Set.of()));
        cases.add(new Case("E03", "状态", "order-service 有几个健康实例？",
                Map.of("INSTANCE_QUERY", "order-service"), Set.of()));
        cases.add(new Case("E04", "状态", "现在注册中心一共注册了几个实例？",
                Map.of("INSTANCE_QUERY", "全部实例"), Set.of()));
        cases.add(new Case("E05", "路由", "/api/pay 这条路径打到哪个服务？",
                Map.of("ROUTE_QUERY", "/api/pay"), Set.of()));
        cases.add(new Case("E06", "路由", "现在一共配了几条路由？分别指向哪里？",
                Map.of("ROUTE_QUERY", "全部路由"), Set.of()));
        cases.add(new Case("E07", "追踪", "/api/order 最近一次调用是在哪一跳失败的？",
                Map.of("TRACE_QUERY", "/api/order"), Set.of()));
        cases.add(new Case("E08", "配置", "限流阈值现在配的是多少？",
                Map.of("CONFIG_READ", ""), Set.of()));
        cases.add(new Case("E09", "事件", "最近有没有实例上下线？",
                Map.of("EVENT_QUERY", ""), Set.of()));
        cases.add(new Case("E10", "历史", "最近改过什么配置吗？",
                Map.of("LOG_QUERY", ""), Set.of()));
        cases.add(new Case("E11", "历史", "/api/order 这条路由最近有没有回滚过？",
                Map.of("LOG_QUERY", "/api/order"), Set.of()));
        cases.add(new Case("E12", "知识", "限流怎么配置？",
                Map.of("KNOWLEDGE_RETRIEVAL", ""), Set.of()));
        cases.add(new Case("E13", "知识", "新服务怎么接入注册中心？",
                Map.of("KNOWLEDGE_RETRIEVAL", ""), Set.of()));
        cases.add(new Case("E14", "调查", "为什么 /api/order 调用失败？",
                Map.of("ROUTE_QUERY", "/api/order", "GATEWAY_METRICS_QUERY", ""), ALL_CAPABILITIES));
        cases.add(new Case("E15", "调查", "order-service 好像挂了，帮我查一下",
                Map.of("INSTANCE_QUERY", ""), ALL_CAPABILITIES));
        cases.add(new Case("E16", "负向", "今天天气怎么样？", Map.of(), Set.of()));
        cases.add(new Case("E17", "负向", "你好", Map.of(), Set.of()));
        cases.add(new Case("E18", "负向", "你能做什么？", Map.of(), Set.of()));
        cases.add(new Case("E19", "负向", "把 /api/order 这条路由下线吧", Map.of(), Set.of("ROUTE_QUERY")));
        cases.add(new Case("E20", "多问", "/api/order 打到哪个服务？它现在有几个健康实例？",
                Map.of("ROUTE_QUERY", "/api/order", "INSTANCE_QUERY", ""), Set.of()));
        return cases;
    }

    public static void main(String[] args) throws Exception {
        String model = args.length > 0 ? args[0] : "glm-4.6";
        int limit = args.length > 1 ? Integer.parseInt(args[1]) : Integer.MAX_VALUE;
        String apiKey = loadApiKey();

        List<Case> cases = cases().stream().limit(limit).toList();
        System.out.println("---------- 工具选择评测 / " + model + " / 共 " + cases.size() + " 条 ----------");

        int passed = 0;
        int missed = 0;
        int extra = 0;
        int argChecks = 0;
        int badArgs = 0;
        int negativeTotal = 0;
        int negativeClean = 0;
        int unfinished = 0;
        long totalMillis = 0;

        for (Case item : cases) {
            // 每条独立 client：一旦某条因网络重置把连接池搞坏，不至于污染后面所有条目。
            ChatModelGateway gateway = gateway(model, ChatClient.builder(chatModel(model, apiKey)).build());
            Recording recorder = new Recording(gateway);
            InvestigationTask task = new InvestigationTaskRegistry().register("eval-" + item.id(), item.question());
            ToolLoopService service = new ToolLoopService(executor(), gateway, recorder);
            long startedAt = System.currentTimeMillis();
            String failure = runWithTimeout(service, task);
            long cost = System.currentTimeMillis() - startedAt;
            totalMillis += cost;
            if (failure != null) {
                unfinished++;
                System.out.println(item.id() + ' ' + item.tier() + ' ' + String.format("%6dms ", cost) + "未完成 "
                        + failure);
                continue;
            }

            TaskView view = task.view();
            Set<String> actual = new LinkedHashSet<>();
            view.executedCapabilities().forEach(capability -> actual.add(capability.name()));
            String details = view.steps().stream()
                    .map(ToolSelectionEval::stepText)
                    .collect(Collectors.joining(" | "));

            Set<String> missing = new LinkedHashSet<>();
            List<String> argProblems = new ArrayList<>();
            for (Map.Entry<String, String> entry : item.required().entrySet()) {
                String capability = entry.getKey();
                String expect = entry.getValue();
                if (!actual.contains(capability)) {
                    missing.add(capability);
                    continue;
                }
                if (expect.isEmpty()) {
                    continue;
                }
                argChecks++;
                if (!details.contains(expect)) {
                    argProblems.add(capability + " 未体现 " + expect);
                    badArgs++;
                }
            }
            Set<String> extraCalls = new LinkedHashSet<>(actual);
            extraCalls.removeAll(item.required().keySet());
            extraCalls.removeAll(item.allowed());

            boolean pass = missing.isEmpty() && extraCalls.isEmpty() && argProblems.isEmpty();
            if (pass) {
                passed++;
            }
            if (!missing.isEmpty()) {
                missed++;
            }
            if (!extraCalls.isEmpty()) {
                extra++;
            }
            if (item.isNegative()) {
                negativeTotal++;
                if (actual.isEmpty()) {
                    negativeClean++;
                }
            }

            StringBuilder line = new StringBuilder();
            line.append(item.id()).append(' ').append(item.tier()).append(' ').append(String.format("%6dms ", cost))
                    .append(pass ? "正确" : "不符");
            line.append(" 实际[").append(actual.isEmpty() ? "无" : String.join(",", shortNames(actual))).append(']');
            if (!missing.isEmpty()) {
                line.append(" 漏调[").append(String.join(",", shortNames(missing))).append(']');
            }
            if (!extraCalls.isEmpty()) {
                line.append(" 多调[").append(String.join(",", shortNames(extraCalls))).append(']');
            }
            if (!argProblems.isEmpty()) {
                line.append(' ').append(String.join("；", argProblems));
            }
            if (!recorder.calls().isEmpty()) {
                line.append(" 流观测[").append(recorder.calls()).append(']');
            }
            System.out.println(line);
            System.out.println("    问：" + item.question());
        }

        int total = cases.size();
        int graded = total - unfinished;
        System.out.println();
        System.out.println("========== 汇总（共 " + total + " 条，有效样本 " + graded + " 条）==========");
        System.out.printf("%-12s %9s %9s %9s %11s %10s%n", "口径", "正确率", "漏调率", "多调率", "参数不符率", "平均延迟");
        System.out.printf("%-12s %8.1f%% %8.1f%% %8.1f%% %9.1f%% %9dms%n", model,
                pct(passed, graded), pct(missed, graded), pct(extra, graded), pct(badArgs, Math.max(1, argChecks)),
                totalMillis / Math.max(1, graded));
        System.out.println("负向条目（不该取数）：" + negativeClean + "/" + negativeTotal + " 零取数"
                + "；参数检查 " + argChecks + " 项，不符 " + badArgs + " 项"
                + (unfinished > 0 ? "；" + unfinished + " 条因网络/超时未完成，未计入各率分母" : ""));
        System.out.println("判据：取数必经 CapabilityExecutor，以「执行过的能力 + 步骤详情」为准；"
                + "开放式调查（调查类）允许额外取数，不计多调。");
    }

    /**
     * 单条硬超时。上一轮出过「一条卡在网络重置上 47 分钟，把后面几条全部拖成假失败」，
     * 所以这里既超时熔断、又每条独立 client，让一次网络抖动只损失它自己那一条。
     */
    private static String runWithTimeout(ToolLoopService service, InvestigationTask task) {
        java.util.concurrent.atomic.AtomicReference<String> error = new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                service.run(task, "");
            } catch (RuntimeException ex) {
                error.set("执行异常：" + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            }
        }, "case-run");
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(CASE_TIMEOUT_SECONDS * 1000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "被中断";
        }
        if (!worker.isAlive()) {
            return error.get();
        }
        worker.interrupt();
        return "超时（>" + CASE_TIMEOUT_SECONDS + "s 未完成）";
    }

    private static String stepText(Step step) {
        return step.name() + "：" + nullToEmpty(step.inputSummary()) + "→" + nullToEmpty(step.outputSummary());
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    private static List<String> shortNames(Set<String> capabilities) {
        return capabilities.stream()
                .map(name -> name.replace("_QUERY", "").replace("GATEWAY_METRICS", "METRIC")
                        .replace("KNOWLEDGE_RETRIEVAL", "KNOWLEDGE").replace("CONFIG_READ", "CONFIG"))
                .toList();
    }

    private static double pct(int count, int total) {
        return count * 100.0 / total;
    }

    // ---------- 模型与观测 ----------

    /** 记录模型在流里请求的工具：只作交叉核对，不作为判据。 */
    private static final class Recording implements ConversationModel {

        private final ChatModelGateway gateway;
        private final Map<String, String> names = new LinkedHashMap<>();
        private final Map<String, StringBuilder> arguments = new LinkedHashMap<>();

        private Recording(ChatModelGateway gateway) {
            this.gateway = gateway;
        }

        @Override
        public String converse(String systemPrompt, String userMessage, OpsTools tools,
                               Consumer<String> onDelta, Consumer<String> onThinking) {
            StringBuilder answer = new StringBuilder();
            gateway.chatClient().prompt()
                    .system(systemPrompt)
                    .user(userMessage)
                    .tools(tools)
                    .stream()
                    .chatResponse()
                    .doOnNext(response -> collect(response, answer, onDelta))
                    .blockLast();
            return answer.toString().trim();
        }

        private void collect(ChatResponse response, StringBuilder answer, Consumer<String> onDelta) {
            if (response == null) {
                return;
            }
            Generation generation = response.getResult();
            AssistantMessage message = generation == null ? null : generation.getOutput();
            if (message == null) {
                return;
            }
            if (message.hasToolCalls()) {
                for (AssistantMessage.ToolCall call : message.getToolCalls()) {
                    names.putIfAbsent(call.id(), call.name());
                    arguments.computeIfAbsent(call.id(), key -> new StringBuilder())
                            .append(call.arguments() == null ? "" : call.arguments());
                }
                return;
            }
            String chunk = message.getText();
            if (chunk != null && !chunk.isEmpty()) {
                answer.append(chunk);
                if (onDelta != null) {
                    onDelta.accept(chunk);
                }
            }
        }

        private String calls() {
            List<String> calls = new ArrayList<>();
            names.forEach((id, name) -> calls.add(name));
            return String.join(",", calls);
        }
    }

    private static ChatModelGateway gateway(String model, ChatClient client) {
        return new ChatModelGateway() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public ChatClient chatClient() {
                return client;
            }

            @Override
            public ChatClient chatClient(int timeoutSeconds) {
                return client;
            }

            @Override
            public String description() {
                return model;
            }

            @Override
            public String reasoningDelta(ChatResponse response) {
                return "";
            }
        };
    }

    private static ChatModel chatModel(String model, String apiKey) {
        java.time.Duration timeout = java.time.Duration.ofSeconds(120);
        java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        DeepSeekApi api = DeepSeekApi.builder()
                .baseUrl(BASE_URL)
                .apiKey(apiKey)
                .restClientBuilder(RestClient.builder().requestFactory(factory))
                .build();
        return DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .options(DeepSeekChatOptions.builder().model(model).build())
                .toolCallingManager(DefaultToolCallingManager.builder().build())
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0)))
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }

    /** 从 Admin 的落盘配置里解出真实 key（与 AdminChatModelGateway 同套 SecretCipher 机制）。 */
    private static String loadApiKey() throws Exception {
        byte[] masterKey = Base64.getDecoder().decode(Files.readString(Path.of("config/master.key")).trim());
        String enc = null;
        for (String line : Files.readAllLines(Path.of("config/admin-model.properties"))) {
            if (line.startsWith("api-key-enc=")) {
                enc = line.substring("api-key-enc=".length()).trim();
            }
        }
        byte[] payload = Base64.getDecoder().decode(enc);
        byte[] iv = Arrays.copyOfRange(payload, 0, 12);
        byte[] ct = Arrays.copyOfRange(payload, 12, payload.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
    }

    // ---------- 只读端口桩：给模型一份「查得到东西」的环境 ----------

    private static CapabilityExecutor executor() {
        return new CapabilityExecutor(new StubRoutes(), new StubInstances(), new StubMetrics(), new StubTraces(),
                new StubConfigs(), new StubEvents(), new StubLogs(), new StubKnowledge(),
                CapabilityRegistry.standard());
    }

    private static final class StubRoutes implements RouteReadPort {

        @Override
        public List<RouteSnapshot> routes() {
            return List.of(
                    new RouteSnapshot("r1", "/api/order", "order-service", "default", "", "", NOW),
                    new RouteSnapshot("r2", "/api/user", "user-service", "default", "", "", NOW),
                    new RouteSnapshot("r3", "/api/pay", "pay-service", "default", "", "", NOW));
        }

        @Override
        public DiscoveryMode discoveryMode() {
            return DiscoveryMode.NAMESERVER;
        }
    }

    private static final class StubInstances implements InstanceReadPort {

        @Override
        public List<InstanceSnapshot> instances() {
            return List.of(
                    new InstanceSnapshot("order-service", "default", "i1", "10.0.0.1", 8080, true, 100, true, NOW),
                    new InstanceSnapshot("order-service", "default", "i2", "10.0.0.2", 8080, false, 100, true, NOW),
                    new InstanceSnapshot("user-service", "default", "i3", "10.0.0.3", 8081, true, 100, true, NOW));
        }
    }

    private static final class StubMetrics implements MetricReadPort {

        @Override
        public GatewayMetricSnapshot gatewayWindow(int windowSeconds) {
            return new GatewayMetricSnapshot(1200L, 35L, 2L, NOW);
        }

        @Override
        public List<RouteUpstreamSnapshot> routeUpstreams(String path, int windowSeconds) {
            return List.of(
                    new RouteUpstreamSnapshot("r1", "10.0.0.1:8080", "default", windowSeconds, 800L, 30L, 0L, 1L,
                            42.5D, 90L, NOW),
                    new RouteUpstreamSnapshot("r1", "10.0.0.2:8080", "default", windowSeconds, 400L, 5L, 2L, 0L,
                            35.0D, 80L, NOW));
        }
    }

    private static final class StubTraces implements TraceReadPort {

        @Override
        public TraceSnapshot byPath(String path) {
            return new TraceSnapshot(true, 0.1D, List.of(new TraceRow("t1", path, 500, NOW - 60_000L)), NOW);
        }
    }

    private static final class StubConfigs implements ConfigReadPort {

        @Override
        public List<ConfigEntrySnapshot> configs() {
            return List.of(
                    new ConfigEntrySnapshot("Gateway", "rate-limit.qps", "每秒请求数上限", "100", "100", "runtime",
                            true, false),
                    new ConfigEntrySnapshot("Gateway", "circuit-breaker.error-rate", "熔断错误率阈值", "0.5", "0.5",
                            "runtime", true, false));
        }
    }

    private static final class StubEvents implements EventReadPort {

        @Override
        public List<RegistryEventSnapshot> events() {
            return List.of(
                    new RegistryEventSnapshot(NOW - 600_000L, "REGISTER", "order-service", "i1", "实例注册"),
                    new RegistryEventSnapshot(NOW - 300_000L, "UNHEALTHY", "order-service", "i2", "标记不健康"));
        }
    }

    private static final class StubLogs implements LogQueryPort {

        @Override
        public List<LogEntry> query(LogRequest request) {
            return List.of(
                    new LogEntry(NOW - 3_600_000L, "config_change", "Gateway", "限流阈值 100 → 80"),
                    new LogEntry(NOW - 7_200_000L, "rollback", "/api/order", "路由回滚到上一版本"));
        }
    }

    private static final class StubKnowledge implements KnowledgeReadPort {

        @Override
        public List<KnowledgeEntry> search(String query, int limit) {
            return List.of(
                    new KnowledgeEntry("k1", "限流配置", "在控制台 Gateway 配置页修改 QPS 阈值，保存后热更新生效。",
                            List.of("限流", "阈值", "QPS")),
                    new KnowledgeEntry("k2", "服务接入", "引入 nameserver-client 依赖并配置注册地址即可完成接入。",
                            List.of("接入", "注册", "服务")));
        }
    }
}
