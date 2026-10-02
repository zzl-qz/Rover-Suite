package com.rover.agent.runtime.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.rover.agent.core.capability.CapabilityExecutor;
import com.rover.agent.core.capability.CapabilityRegistry;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.planning.PlanValidator;
import com.rover.agent.core.planning.PlanningLimits;
import com.rover.agent.core.planning.RuleBasedPlanner;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.runtime.ToolLoopService;
import com.rover.agent.runtime.InvestigationService;
import com.rover.agent.runtime.metrics.AgentMetrics;
import com.rover.agent.runtime.planning.LlmInvestigationPlanner;
import com.rover.agent.runtime.repository.InMemoryAgentCheckpointRepository;
import com.rover.agent.runtime.repository.InMemoryAgentTaskRepository;
import com.rover.agent.runtime.task.AgentExecutionSettings;
import com.rover.agent.runtime.task.AgentRunBudget;
import com.rover.agent.runtime.task.AgentRunLimits;
import com.rover.agent.runtime.task.InvestigationTask;
import com.rover.agent.runtime.task.IncidentRegistry;
import com.rover.agent.runtime.task.InvestigationTaskRegistry;
import com.rover.agent.runtime.task.TaskEventBus;
import com.rover.agent.runtime.tool.OpsTools;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import reactor.core.publisher.Flux;

/** 用真实 ChatClient 工具循环和脚本 ChatModel 验证限制，不连接模型服务。 */
class AgentExecutionLimitsTest {

    @BeforeAll
    static void warmTokenizer() {
        Gateway gateway = new Gateway(prompt -> Flux.just(answer("ok")));
        AgentBudgetAdvisor.client(gateway.client, new AgentRunBudget(AgentRunLimits.defaults()), gateway::reasoningDelta)
                .prompt().user("warmup").call().content();
        InvestigationTaskRegistry registry = registry(AgentRunLimits.defaults());
        try {
            new ToolLoopService(executor(new AtomicInteger()), gateway).run(registry.register("warmup", "预热"), "");
        } finally {
            registry.stop();
        }
    }

    @Test
    void modelCallLimitIsCheckedInsideTheRealToolLoop() {
        AtomicInteger calls = new AtomicInteger();
        Gateway gateway = new Gateway(prompt -> {
            calls.incrementAndGet();
            assertTrue(prompt.getOptions().getMaxTokens() <= 64);
            return Flux.just(tool("listRoutes", "{}"));
        });
        InvestigationTaskRegistry registry = registry(limits(2, 64, 10000, Duration.ofSeconds(5)));
        try {
            InvestigationTask task = registry.register("rounds", "查路由");
            AtomicInteger reads = new AtomicInteger();
            OpsTools tools = tools(task, reads);
            var failure = assertThrows(RuntimeException.class,
                    () -> new SpringAiConversationModel(gateway).converse("system", "user", tools, null, null));
            assertTrue(task.budget().stopReason().contains("模型调用次数"), failure.toString());
            assertEquals(2, calls.get(), "第三次模型请求必须在发送前被阻止");
            assertEquals(2, reads.get());
        } finally {
            registry.stop();
        }
    }

    @Test
    void quickCallsAndConversationShareTheSameTaskBudget() {
        AtomicInteger calls = new AtomicInteger();
        Gateway gateway = new Gateway(prompt -> {
            calls.incrementAndGet();
            return Flux.just(answer("ok"));
        });
        InvestigationTaskRegistry registry = registry(limits(1, 64, 10000, Duration.ofSeconds(5)));
        InvestigationTask task = registry.register("shared", "查路由");
        task.start();
        task.markRunning();
        try {
            assertEquals("ok", QuickModelCall.content(gateway, 10, "目标解析",
                    client -> client.prompt().user("目标").call().content()));
            assertThrows(RuntimeException.class, () -> new SpringAiConversationModel(gateway)
                    .converse("system", "user", tools(task, new AtomicInteger()), null, null));
            assertEquals(1, calls.get());
            assertTrue(task.cancelled());
            assertEquals(TaskStatus.FAILED, task.view().status(), "预算异常被降级也不能让任务继续成功");
        } finally {
            task.clearRunning();
            registry.stop();
        }
        assertNull(AgentRunBudget.current(), "线程复用前必须清除任务预算");
    }

    @Test
    void rejectsOversizedInputBeforeSendingAModelRequest() {
        AtomicInteger calls = new AtomicInteger();
        Gateway gateway = new Gateway(prompt -> {
            calls.incrementAndGet();
            return Flux.just(answer("ok"));
        });
        AgentRunBudget budget = new AgentRunBudget(limits(3, 32, 10, Duration.ofSeconds(5)));
        assertThrows(RuntimeException.class, () -> AgentBudgetAdvisor.client(gateway.client, budget, gateway::reasoningDelta)
                .prompt().user("输入太长 ".repeat(100)).call().content());
        assertEquals(0, calls.get());
        assertTrue(budget.stopReason().contains("累计 token"));
    }

    @Test
    void cumulativeUsageInEveryFrameIsNotChargedRepeatedly() {
        Gateway gateway = new Gateway(prompt -> Flux.range(0, 5).map(index -> new ChatResponse(
                List.of(new Generation(new AssistantMessage("hi"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(1000, 10, 1010)).build())));
        AgentRunBudget budget = new AgentRunBudget(limits(3, 64, 1500, Duration.ofSeconds(5)));
        String result = AgentBudgetAdvisor.client(gateway.client, budget, gateway::reasoningDelta)
                .prompt().user("hi").stream().content().collectList().block(Duration.ofSeconds(3)).toString();
        assertTrue(result.contains("hi"));
        assertNull(budget.stopReason());
        assertThrows(RuntimeException.class, () -> AgentBudgetAdvisor.client(gateway.client, budget, gateway::reasoningDelta)
                .prompt().user("hi").call().content(), "不同请求的用量必须累计");
        assertTrue(budget.stopReason().contains("累计 token"));
    }

    @Test
    void reasoningWithoutUsageStillHitsTheOutputLimitAndCancelsTheStream() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1);
        Gateway gateway = new Gateway(prompt -> Flux.interval(Duration.ofMillis(10))
                .map(index -> new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("").properties(Map.of("thought", "思考 ".repeat(40))).build()))))
                .doOnCancel(cancelled::countDown));
        AgentRunBudget budget = new AgentRunBudget(limits(3, 16, 10000, Duration.ofSeconds(5)));
        assertThrows(RuntimeException.class, () -> AgentBudgetAdvisor.client(gateway.client, budget, gateway::reasoningDelta)
                .prompt().user("hi").stream().content().blockLast(Duration.ofSeconds(3)));
        assertTrue(budget.stopReason().contains("单次模型输出"));
        assertTrue(cancelled.await(3, TimeUnit.SECONDS));
    }

    @Test
    void repeatedToolResultsStopTheLoopAndParameterOrderDoesNotResetTheCounter() {
        AtomicInteger calls = new AtomicInteger();
        EchoTools tools = new EchoTools(false);
        Gateway gateway = new Gateway(prompt -> {
            int index = calls.incrementAndGet();
            return Flux.just(tool("echo", index % 2 == 0 ? "{\"b\":2,\"a\":1}" : "{\"a\":1,\"b\":2}"));
        });
        AgentRunBudget budget = new AgentRunBudget(limits(20, 64, 10000, Duration.ofSeconds(5)));
        assertThrows(RuntimeException.class, () -> AgentBudgetAdvisor.client(gateway.client, budget, gateway::reasoningDelta)
                .prompt().user("hi").tools(tools).stream().content().blockLast(Duration.ofSeconds(3)));
        assertEquals(3, tools.calls.get());
        assertEquals(3, calls.get(), "不能把停止消息喂回模型后再请求第四轮");
        assertTrue(budget.stopReason().contains("没有取得新信息"));
    }

    @Test
    void changingToolResultsAreAllowed() {
        AtomicInteger calls = new AtomicInteger();
        Gateway gateway = new Gateway(prompt -> Flux.just(calls.incrementAndGet() <= 4
                ? tool("echo", "{\"a\":1,\"b\":2}") : answer("done")));
        AgentRunBudget budget = new AgentRunBudget(limits(5, 64, 10000, Duration.ofSeconds(5)));
        EchoTools tools = new EchoTools(true);
        assertEquals("done", AgentBudgetAdvisor.client(gateway.client, budget, gateway::reasoningDelta)
                .prompt().user("hi").tools(tools).call().content());
        assertEquals(4, tools.calls.get());
    }

    @Test
    void repetitionLimitAlsoStopsTheRemainingToolsInTheSameModelResponse() {
        Gateway gateway = new Gateway(prompt -> Flux.just(new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(java.util.stream.IntStream.range(0, 4)
                        .mapToObj(index -> new AssistantMessage.ToolCall("call-" + index, "function", "echo",
                                "{\"a\":1,\"b\":2}")).toList()).build())))));
        AgentRunBudget budget = new AgentRunBudget(limits(20, 256, 10000, Duration.ofSeconds(5)));
        EchoTools tools = new EchoTools(false);
        assertThrows(RuntimeException.class, () -> AgentBudgetAdvisor.client(gateway.client, budget, gateway::reasoningDelta)
                .prompt().user("hi").tools(tools).call().content());
        assertEquals(3, tools.calls.get(), "同一批的第四个工具也不得执行");
        assertTrue(budget.stopReason().contains("没有取得新信息"));
    }

    @Test
    void concurrentTasksDoNotShareTheirBudgets() {
        Gateway gateway = new Gateway(prompt -> Flux.just(answer("ok")));
        AgentRunLimits limits = limits(1, 64, 10000, Duration.ofSeconds(5));
        var first = CompletableFuture.supplyAsync(() -> AgentBudgetAdvisor.client(gateway.client,
                new AgentRunBudget(limits), gateway::reasoningDelta).prompt().user("first").call().content());
        var second = CompletableFuture.supplyAsync(() -> AgentBudgetAdvisor.client(gateway.client,
                new AgentRunBudget(limits), gateway::reasoningDelta).prompt().user("second").call().content());
        assertEquals("ok", first.join());
        assertEquals("ok", second.join());
    }

    @Test
    void planningGraphAndExplanationShareTheTaskBudget() {
        AtomicInteger calls = new AtomicInteger();
        Gateway gateway = new Gateway(prompt -> {
            calls.incrementAndGet();
            return Flux.just(answer("{\"steps\":[]}"));
        });
        CapabilityRegistry capabilities = CapabilityRegistry.standard();
        PlanningLimits planningLimits = PlanningLimits.defaults();
        var planner = new LlmInvestigationPlanner(new RuleBasedPlanner(capabilities),
                new SpringAiJsonCompletion(gateway, AgentMetrics.NOOP, "规划", 10), capabilities);
        InvestigationTaskRegistry registry = registry(limits(1, 256, 100000, Duration.ofSeconds(5)));
        InvestigationTask task = registry.register("graph-budget", "排查路径");
        task.bind("incident", "/api", ResourceTarget.route("/api"));
        try {
            new InvestigationService(new IncidentRegistry(), registry, new ModelExplainer(gateway), planner,
                    new PlanValidator(capabilities, planningLimits), executor(new AtomicInteger()), planningLimits)
                    .run(task);
            assertEquals(1, calls.get(), "图节点的模型规划必须消耗同一个预算");
            assertEquals(TaskStatus.FAILED, task.view().status(), "不能通过规则降级把预算耗尽变为成功");
            assertTrue(task.view().error().contains("模型调用次数"));
            assertFalse(task.view().evidence().isEmpty(), "失败也应保留已采集的证据");
        } finally {
            registry.stop();
        }
        assertNull(AgentRunBudget.current());
    }

    @Test
    void timeoutRetryCannotResetTheModelCallBudget() {
        AtomicInteger calls = new AtomicInteger();
        Gateway gateway = new Gateway(prompt -> {
            calls.incrementAndGet();
            return Flux.error(new RuntimeException(new java.net.SocketTimeoutException("timeout")));
        });
        InvestigationTaskRegistry registry = registry(limits(1, 64, 10000, Duration.ofSeconds(5)));
        InvestigationTask task = registry.register("retry-budget", "hi");
        task.start();
        task.markRunning();
        try {
            assertThrows(RuntimeException.class, () -> QuickModelCall.content(gateway, 1, "目标解析",
                    client -> client.prompt().user("hi").call().content()));
            assertEquals(1, calls.get());
            assertTrue(task.budget().stopReason().contains("模型调用次数"));
        } finally {
            task.clearRunning();
            registry.stop();
        }
    }

    @Test
    void totalDeadlineCancelsABlockingModelCallAndReleasesTheWorker() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Gateway gateway = new Gateway(prompt -> Flux.<ChatResponse>never()
                .doOnSubscribe(subscription -> subscribed.countDown()).doOnCancel(cancelled::countDown));
        InvestigationTaskRegistry registry = registry(limits(20, 4096, 100000, Duration.ofSeconds(1)));
        InvestigationTask task = registry.register("blocking-deadline", "hi");
        try {
            registry.execute(() -> {
                task.start();
                task.markRunning();
                try {
                    assertThrows(RuntimeException.class, () -> QuickModelCall.content(gateway, 10, "目标解析",
                            client -> client.prompt().user("hi").call().content()));
                    assertTrue(task.cancelled());
                } catch (Throwable failure) {
                    workerFailure.set(failure);
                } finally {
                    task.clearRunning();
                    done.countDown();
                }
            });
            assertTrue(subscribed.await(3, TimeUnit.SECONDS));
            assertTrue(cancelled.await(3, TimeUnit.SECONDS));
            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertNull(workerFailure.get());
            assertEquals(TaskStatus.FAILED, task.view().status());
            assertTrue(task.view().error().contains("总执行时限"));
        } finally {
            registry.stop();
        }
    }

    @Test
    void totalDeadlineStopsBothSilentAndContinuouslyActiveStreams() throws Exception {
        for (boolean silent : List.of(true, false)) {
            CountDownLatch subscribed = new CountDownLatch(1);
            CountDownLatch cancelled = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(1);
            Gateway gateway = new Gateway(prompt -> (silent ? Flux.<ChatResponse>never()
                    : Flux.interval(Duration.ofMillis(10)).map(index -> answer("a")))
                    .doOnSubscribe(subscription -> subscribed.countDown())
                    .doOnCancel(cancelled::countDown));
            InvestigationTaskRegistry registry = registry(limits(20, 4096, 100000, Duration.ofSeconds(1)));
            InvestigationTask task = registry.register("deadline-" + silent, "hi");
            ToolLoopService service = new ToolLoopService(executor(new AtomicInteger()), gateway);
            try {
                registry.execute(() -> {
                    task.start();
                    task.markRunning();
                    try {
                        service.run(task, "");
                        task.complete(new com.rover.agent.core.model.InvestigationReport("迟到回答",
                                com.rover.agent.core.model.Confidence.LOW, List.of(), List.of(), List.of(), null));
                        task.fail("迟到失败");
                    } finally {
                        task.clearRunning();
                        done.countDown();
                    }
                });
                assertTrue(subscribed.await(3, TimeUnit.SECONDS), "必须实际订阅模型请求");
                assertTrue(cancelled.await(3, TimeUnit.SECONDS),
                        "必须取消上游响应流，silent=" + silent + ", error=" + task.view().error());
                assertTrue(done.await(3, TimeUnit.SECONDS), "必须释放 Worker");
                assertEquals(TaskStatus.FAILED, task.view().status());
                assertTrue(task.view().error().contains("总执行时限"), task.view().error());
                assertNull(task.view().result(), "迟到结果不能覆盖超时失败");
            } finally {
                registry.stop();
            }
        }
    }

    private static AgentRunLimits limits(int calls, int output, long total, Duration timeout) {
        return new AgentRunLimits(calls, output, total, timeout, 3);
    }

    private static InvestigationTaskRegistry registry(AgentRunLimits limits) {
        return new InvestigationTaskRegistry(new InMemoryAgentTaskRepository(30), AgentExecutionSettings.defaults(),
                new TaskEventBus(), AgentMetrics.NOOP, new InMemoryAgentCheckpointRepository(), limits);
    }

    private static CapabilityExecutor executor(AtomicInteger reads) {
        return new CapabilityExecutor(new RouteReadPort() {
            @Override public List<RouteSnapshot> routes() {
                reads.incrementAndGet();
                return List.of();
            }
            @Override public DiscoveryMode discoveryMode() { return DiscoveryMode.UNKNOWN; }
        }, null, null, null, null, null, null, null, CapabilityRegistry.standard());
    }

    private static OpsTools tools(InvestigationTask task, AtomicInteger reads) {
        return new OpsTools(executor(reads), task);
    }

    private static ChatResponse answer(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static ChatResponse tool(String name, String arguments) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + System.nanoTime(), "function", name, arguments)))
                .build())));
    }

    public static final class EchoTools {
        final AtomicInteger calls = new AtomicInteger();
        private final boolean changing;
        EchoTools(boolean changing) { this.changing = changing; }
        @Tool(description = "echo")
        public String echo(int a, int b) {
            int count = calls.incrementAndGet();
            return changing ? "result-" + count : "same";
        }
    }

    private static final class Gateway implements ChatModelGateway {
        final ChatClient client;
        Gateway(Function<Prompt, Flux<ChatResponse>> script) {
            client = ChatClient.create(new ChatModel() {
                @Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
                @Override public ChatResponse call(Prompt prompt) { return script.apply(prompt).blockLast(); }
                @Override public Flux<ChatResponse> stream(Prompt prompt) { return script.apply(prompt); }
            });
        }
        @Override public boolean configured() { return true; }
        @Override public boolean available() { return true; }
        @Override public ChatClient chatClient() { return client; }
        @Override public ChatClient chatClient(int timeoutSeconds) { return client; }
        @Override public String description() { return "scripted"; }
        @Override public String reasoningDelta(ChatResponse response) {
            return response.getResult() == null ? ""
                    : String.valueOf(response.getResult().getOutput().getMetadata().getOrDefault("thought", ""));
        }
    }
}
