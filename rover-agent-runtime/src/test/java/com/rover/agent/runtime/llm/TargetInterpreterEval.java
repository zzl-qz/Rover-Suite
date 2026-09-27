package com.rover.agent.runtime.llm;

import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import io.micrometer.observation.ObservationRegistry;

/**
 * 目标解析准确率评测（手动运行，不是单元测试）。
 *
 * <p><b>为什么不是单测</b>：它依赖真实模型调用，结果不可复现，写进单测只会随机红或形同虚设
 * （与本册 02 文档 §9「模型层命中率刻意不写进单测」同一口径）。因此它是一个带 main 的评测程序，
 * 需要人显式跑一次、把结果抄进文档。
 *
 * <p><b>评测的是真实链路</b>：{@link ModelTargetInterpreter}（提示词、候选构造、{@code pick()}
 * 白名单兜底）完全是生产代码，本程序只替换它依赖的 {@link ChatModelGateway}，把「用哪个模型、
 * 思考开关怎么传」作为受控变量。
 *
 * <p><b>标注答案不经过任何模型</b>：期望答案由「问题文本点名了哪个候选对象」这一机械规则决定；
 * 问题点名的对象不在候选清单里时，期望答案是 UNKNOWN。因此不存在「用模型生成答案再考模型」的
 * 循环论证。
 */
public class TargetInterpreterEval {

    private static final String BASE_URL = "https://open.bigmodel.cn/api/paas/v4";
    private static final long NOW = System.currentTimeMillis();

    /** thinking 参数的三种传法，对应三个真实状态。 */
    private enum Thinking {
        /** 不传 thinking 参数：glm 系列会默认开启思考，这是「修复前」的行为。 */
        NOT_PASSED,
        /** 显式 thinking:{"type":"disabled"}：这是修复后廉价调用的传法。 */
        DISABLED
    }

    // ---------- 候选清单（取自真实网关命名习惯）----------

    private static final List<RouteSnapshot> SCENARIO_A = List.of(
            route("r1", "/api/order", "order-service"),
            route("r2", "/api/user", "user-service"),
            route("r3", "/api/pay", "pay-service"));

    private static final List<RouteSnapshot> SCENARIO_B = List.of(
            route("r1", "/api/order", "order-service"),
            route("r2", "/api/orders", "order-service"),
            route("r3", "/api/order-detail", "order-service"));

    private static final List<RouteSnapshot> SCENARIO_C = List.of(
            route("r1", "/api/demo/tt", "demo"));
    private static final List<InstanceSnapshot> SCENARIO_C_INSTANCES = List.of(
            instance("demo", "10.0.0.1", 8080),
            instance("demo", "10.0.0.2", 8080));

    private static final List<RouteSnapshot> SCENARIO_D = List.of(
            route("r1", "/api/gateway/route", "gateway-service"),
            route("r2", "/api/gateway/instance", "gateway-service"));

    private static final List<RouteSnapshot> SCENARIO_E = List.of(
            route("r1", "/api/trade", "trade-service"),
            route("r2", "/api/trade/query", "trade-service"));
    private static final List<InstanceSnapshot> SCENARIO_E_INSTANCES = List.of(
            instance("trade-service", "172.16.0.9", 7001));

    // ---------- 评测集：30 条 ----------

    private record Case(String id, String tier, String query, List<RouteSnapshot> routes,
                        List<InstanceSnapshot> instances, String expected) {

        /** 期望是否为「应该弃权（UNKNOWN）」。 */
        boolean expectsAbstain() {
            return expected == null;
        }
    }

    private static List<Case> cases() {
        List<Case> cases = new ArrayList<>();
        // 场景 A：简单点名 + 陷阱 + 未点名
        cases.add(new Case("A1", "简单", "/api/order 下单为什么 5xx？", SCENARIO_A, List.of(), "/api/order"));
        cases.add(new Case("A2", "简单", "order-service 现在有几个实例在线？", SCENARIO_A, List.of(), "order-service"));
        cases.add(new Case("A3", "简单", "/api/pay 支付接口超时了", SCENARIO_A, List.of(), "/api/pay"));
        cases.add(new Case("A4", "简单", "user-service 健康检查一直失败", SCENARIO_A, List.of(), "user-service"));
        cases.add(new Case("A5", "简单", "帮我看看 /api/order 的响应耗时", SCENARIO_A, List.of(), "/api/order"));
        cases.add(new Case("A6", "陷阱", "/api/refund 退款接口挂了", SCENARIO_A, List.of(), null));
        cases.add(new Case("A7", "歧义", "网关现在总体怎么样？", SCENARIO_A, List.of(), null));
        cases.add(new Case("A8", "陷阱", "库存服务是不是有问题？", SCENARIO_A, List.of(), null));
        // 场景 B：相似前缀干扰 + 口语映射
        cases.add(new Case("B1", "干扰", "/api/orders 列表查询很慢", SCENARIO_B, List.of(), "/api/orders"));
        cases.add(new Case("B2", "干扰", "/api/order 创建订单失败", SCENARIO_B, List.of(), "/api/order"));
        cases.add(new Case("B3", "干扰", "/api/order-detail 详情接口 500", SCENARIO_B, List.of(), "/api/order-detail"));
        cases.add(new Case("B4", "口语", "订单服务压力很大", SCENARIO_B, List.of(), "order-service"));
        cases.add(new Case("B5", "陷阱", "/api/order/v2 新版下单接口报错", SCENARIO_B, List.of(), null));
        cases.add(new Case("B6", "歧义", "order 相关的接口都慢", SCENARIO_B, List.of(), null));
        // 场景 C：实例维度
        cases.add(new Case("C1", "实例", "10.0.0.2:8080 这台机器是不是有问题", SCENARIO_C, SCENARIO_C_INSTANCES,
                "10.0.0.2:8080"));
        cases.add(new Case("C2", "简单", "/api/demo/tt 调用失败", SCENARIO_C, SCENARIO_C_INSTANCES, "/api/demo/tt"));
        cases.add(new Case("C3", "简单", "demo 服务注册不上去", SCENARIO_C, SCENARIO_C_INSTANCES, "demo"));
        cases.add(new Case("C4", "实例", "10.0.0.1:8080 心跳正常吗", SCENARIO_C, SCENARIO_C_INSTANCES,
                "10.0.0.1:8080"));
        cases.add(new Case("C5", "陷阱", "192.168.1.1:9000 连不上", SCENARIO_C, SCENARIO_C_INSTANCES, null));
        cases.add(new Case("C6", "歧义", "demo 的那个实例负载高", SCENARIO_C, SCENARIO_C_INSTANCES, null));
        // 场景 D：口语化问法
        cases.add(new Case("D1", "口语", "咋回事，/api/gateway/route 一直报错", SCENARIO_D, List.of(),
                "/api/gateway/route"));
        cases.add(new Case("D2", "口语", "查实例列表的接口 /api/gateway/instance 超时了", SCENARIO_D, List.of(),
                "/api/gateway/instance"));
        cases.add(new Case("D3", "口语", "gateway-service 报 503", SCENARIO_D, List.of(), "gateway-service"));
        cases.add(new Case("D4", "陷阱", "帮我看看库存服务", SCENARIO_D, List.of(), null));
        cases.add(new Case("D5", "歧义", "网关接口的响应慢", SCENARIO_D, List.of(), null));
        cases.add(new Case("D6", "歧义", "/api/gateway/route 和 /api/gateway/instance 哪个慢？", SCENARIO_D,
                List.of(), null));
        // 场景 E：前缀重叠 + 服务/实例混合
        cases.add(new Case("E1", "干扰", "/api/trade/query 查询超时", SCENARIO_E, SCENARIO_E_INSTANCES,
                "/api/trade/query"));
        cases.add(new Case("E2", "简单", "trade-service 的实例挂了一个", SCENARIO_E, SCENARIO_E_INSTANCES,
                "trade-service"));
        cases.add(new Case("E3", "实例", "172.16.0.9:7001 是不是掉线了", SCENARIO_E, SCENARIO_E_INSTANCES,
                "172.16.0.9:7001"));
        cases.add(new Case("E4", "简单", "/api/trade 下单报错", SCENARIO_E, SCENARIO_E_INSTANCES, "/api/trade"));
        return cases;
    }

    // ---------- 评测执行 ----------

    private record Outcome(int hit, int wrong, int abstain, long totalMillis) {
    }

    public static void main(String[] args) throws Exception {
        String apiKey = loadApiKey();
        List<Case> cases = cases();
        Map<String, Outcome> results = new LinkedHashMap<>();

        if (args.length > 0) {
            // 只跑指定模型：用于追加对比候选（如 glm-4-flash），不必重跑全部配置。
            results.put(args[0], run(args[0], Thinking.NOT_PASSED, apiKey, cases));
        } else {
            // 切换前：glm-4.6 不传 thinking 参数（默认开思考）
            results.put("glm-4.6 思考（切换前）", run("glm-4.6", Thinking.NOT_PASSED, apiKey, cases));
            // 中间态：glm-4.6 显式关思考
            results.put("glm-4.6 关思考（修 bug 后）", run("glm-4.6", Thinking.DISABLED, apiKey, cases));
            // 切换后：glm-4-air（不在 supportsThinking 名单，生产代码同样不传 thinking）
            results.put("glm-4-air（双模型后）", run("glm-4-air", Thinking.NOT_PASSED, apiKey, cases));
        }

        System.out.println();
        System.out.println("========== 汇总（共 " + cases.size() + " 条）==========");
        System.out.printf("%-26s %8s %8s %8s %10s%n", "配置", "命中", "错误", "弃权", "平均延迟");
        for (Map.Entry<String, Outcome> e : results.entrySet()) {
            Outcome o = e.getValue();
            System.out.printf("%-26s %7.1f%% %7.1f%% %7.1f%% %8dms%n",
                    e.getKey(),
                    pct(o.hit(), cases.size()),
                    pct(o.wrong(), cases.size()),
                    pct(o.abstain(), cases.size()),
                    o.totalMillis() / cases.size());
        }
    }

    private static Outcome run(String model, Thinking thinking, String apiKey, List<Case> cases) {
        ChatModelGateway gateway = gateway(model, thinking, apiKey);
        ModelTargetInterpreter interpreter = new ModelTargetInterpreter(gateway, 10);
        int hit = 0;
        int wrong = 0;
        int abstain = 0;
        long total = 0;
        System.out.println();
        System.out.println("---------- " + model + " / " + thinking + " ----------");
        for (Case c : cases) {
            long t0 = System.nanoTime();
            Optional<ResourceTarget> actual;
            try {
                actual = interpreter.infer(c.query(), c.routes(), c.instances());
            } catch (RuntimeException ex) {
                actual = Optional.empty();
                System.out.printf("%-4s %-4s 异常: %s%n", c.id(), c.tier(), ex.getMessage());
            }
            long millis = (System.nanoTime() - t0) / 1_000_000;
            total += millis;
            String actualValue = actual.map(ResourceTarget::value).orElse(null);
            String verdict;
            if (c.expectsAbstain()) {
                if (actualValue == null) {
                    verdict = "正确弃权";
                    hit++;
                } else {
                    verdict = "错误(应弃权却选了 " + actualValue + ")";
                    wrong++;
                }
            } else if (c.expected().equals(actualValue)) {
                verdict = "命中";
                hit++;
            } else if (actualValue == null) {
                verdict = "弃权(期望 " + c.expected() + ")";
                abstain++;
            } else {
                verdict = "错误(期望 " + c.expected() + " 实得 " + actualValue + ")";
                wrong++;
            }
            System.out.printf("%-4s %-4s %6dms %s%n", c.id(), c.tier(), millis, verdict);
        }
        return new Outcome(hit, wrong, abstain, total);
    }

    private static double pct(int value, int total) {
        return value * 100.0 / total;
    }

    // ---------- gateway / 模型构造（只替换 gateway，判定逻辑仍走生产代码）----------

    private static ChatModelGateway gateway(String model, Thinking thinking, String apiKey) {
        ChatClient client = ChatClient.builder(chatModel(model, thinking, apiKey)).build();
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
                return model + " / " + thinking;
            }

            @Override
            public String reasoningDelta(ChatResponse response) {
                return "";
            }
        };
    }

    private static ChatModel chatModel(String model, Thinking thinking, String apiKey) {
        DeepSeekChatOptions.Builder options = DeepSeekChatOptions.builder().model(model);
        if (thinking == Thinking.DISABLED) {
            options.disableThinking();
        }
        DeepSeekApi api = DeepSeekApi.builder()
                .baseUrl(BASE_URL)
                .apiKey(apiKey)
                .restClientBuilder(restClient())
                .build();
        return DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .options(options.build())
                .toolCallingManager(DefaultToolCallingManager.builder().build())
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0)))
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }

    private static org.springframework.web.client.RestClient.Builder restClient() {
        java.time.Duration timeout = java.time.Duration.ofSeconds(30);
        java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        return org.springframework.web.client.RestClient.builder().requestFactory(factory);
    }

    // ---------- 辅助 ----------

    private static RouteSnapshot route(String id, String prefix, String service) {
        return new RouteSnapshot(id, prefix, service, "", "", "", NOW);
    }

    private static InstanceSnapshot instance(String service, String host, int port) {
        return new InstanceSnapshot(service, "", "", host, port, true, 100, true, NOW);
    }

    /** 从 Admin 的落盘配置里解出真实 key（与 AdminChatModelGateway 同套 SecretCipher 机制）。 */
    private static String loadApiKey() throws Exception {
        byte[] masterKey = Base64.getDecoder()
                .decode(Files.readString(Path.of("config/master.key")).trim());
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
}
