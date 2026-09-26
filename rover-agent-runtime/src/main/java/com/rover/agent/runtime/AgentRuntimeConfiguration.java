package com.rover.agent.runtime;

import com.rover.agent.core.capability.CapabilityExecutor;
import com.rover.agent.core.capability.CapabilityRegistry;
import com.rover.agent.core.context.AgentContextManager;
import com.rover.agent.core.context.TargetInterpreter;
import com.rover.agent.core.context.TargetResolver;
import com.rover.agent.core.intent.IntentClassifier;
import com.rover.agent.core.intent.IntentInterpreter;
import com.rover.agent.core.intent.IntentService;
import com.rover.agent.core.planning.InvestigationPlanner;
import com.rover.agent.core.planning.PlanValidator;
import com.rover.agent.core.planning.PlanningLimits;
import com.rover.agent.core.planning.RuleBasedPlanner;
import com.rover.agent.core.port.ConfigReadPort;
import com.rover.agent.core.port.EventReadPort;
import com.rover.agent.core.port.InstanceReadPort;
import com.rover.agent.core.port.MetricReadPort;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.port.TraceReadPort;
import com.rover.agent.core.repository.AgentMessageRepository;
import com.rover.agent.core.repository.AgentSessionRepository;
import com.rover.agent.core.repository.AgentTaskRepository;
import com.rover.agent.core.repository.IncidentRepository;
import com.rover.agent.runtime.llm.ChatModelGateway;
import com.rover.agent.runtime.llm.LlmIntentInterpreter;
import com.rover.agent.runtime.llm.ModelExplainer;
import com.rover.agent.runtime.llm.ModelTargetInterpreter;
import com.rover.agent.runtime.llm.NoopChatModelGateway;
import com.rover.agent.runtime.llm.SpringAiJsonCompletion;
import com.rover.agent.runtime.metrics.AgentMetrics;
import com.rover.agent.runtime.metrics.MicrometerAgentMetrics;
import com.rover.agent.runtime.planning.LlmInvestigationPlanner;
import com.rover.agent.runtime.repository.InMemoryAgentMessageRepository;
import com.rover.agent.runtime.repository.InMemoryAgentSessionRepository;
import com.rover.agent.runtime.repository.InMemoryAgentTaskRepository;
import com.rover.agent.runtime.repository.InMemoryIncidentRepository;
import com.rover.agent.runtime.task.AgentExecutionSettings;
import com.rover.agent.runtime.task.IncidentRegistry;
import com.rover.agent.runtime.task.InvestigationTaskRegistry;
import com.rover.agent.runtime.task.TaskEventBus;
import com.rover.agent.runtime.task.WorkspaceRetention;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 运行层装配：只依赖只读端口与模型端口，不依赖任何具体数据源或 Web 框架。
 *
 * 端口实现（如 Admin 管理口适配器、Admin 模型适配器）由宿主进程提供，
 * 因此运行层可以在同一个 JVM 内复用，也可以整体搬到独立进程。
 *
 * 存储当前一律是内存实现（重启即失），但仓储在这里统一注册成 Bean：
 * 会话、事件、消息、任务必须共用同一份存储，否则任务快照与上下文会读到互不相干的两份数据。
 */
@Configuration(proxyBeanMethods = false)
public class AgentRuntimeConfiguration {

    /** 内存记录容量上限；接入持久化后由具体实现决定，业务代码不受影响。 */
    private static final int SESSION_CAPACITY = 200;
    private static final int INCIDENT_CAPACITY = 500;
    private static final int MESSAGE_CAPACITY = 2000;
    private static final int TASK_CAPACITY = 200;

    @Bean
    public AgentSessionRepository agentSessionRepository() {
        return new InMemoryAgentSessionRepository(SESSION_CAPACITY);
    }

    @Bean
    public AgentMessageRepository agentMessageRepository() {
        return new InMemoryAgentMessageRepository(MESSAGE_CAPACITY);
    }

    @Bean
    public IncidentRepository agentIncidentRepository() {
        return new InMemoryIncidentRepository(INCIDENT_CAPACITY);
    }

    @Bean
    public AgentTaskRepository agentTaskRepository() {
        return new InMemoryAgentTaskRepository(TASK_CAPACITY);
    }

    /**
     * 运行指标端口：宿主提供且仅提供一个 {@link MeterRegistry}、且未显式关闭时才启用。
     *
     * 未提供注册表（运行层脱离 Admin 单独复用）或 {@code rover.agent.metrics.enabled=false}（应急降级）
     * 时退化为空实现，调用方不做任何分支。指标只读注册表、不导出：不引 Actuator、不接 Prometheus。
     */
    @Bean
    public AgentMetrics agentMetrics(ObjectProvider<MeterRegistry> meterRegistries,
                                     @Value("${rover.agent.metrics.enabled:true}") boolean enabled) {
        MeterRegistry registry = meterRegistries.getIfAvailable();
        if (!enabled || registry == null) {
            return AgentMetrics.NOOP;
        }
        return new MicrometerAgentMetrics(registry);
    }

    /**
     * 存储保留策略：内存容量的"丢谁"决策集中在这里。
     *
     * 容量常量与各仓储共用同一批定义，避免"仓储 200、清理按 100 做"这类口径错位。
     */
    @Bean
    public WorkspaceRetention agentWorkspaceRetention(AgentSessionRepository agentSessionRepository,
                                                      IncidentRepository agentIncidentRepository,
                                                      AgentMessageRepository agentMessageRepository,
                                                      InvestigationTaskRegistry agentTaskRegistry) {
        return new WorkspaceRetention(agentSessionRepository, agentIncidentRepository, agentMessageRepository,
                agentTaskRegistry, SESSION_CAPACITY, INCIDENT_CAPACITY, MESSAGE_CAPACITY);
    }

    @Bean
    public IncidentRegistry agentIncidentRegistry(AgentSessionRepository agentSessionRepository,
                                                  IncidentRepository agentIncidentRepository,
                                                  WorkspaceRetention agentWorkspaceRetention) {
        return new IncidentRegistry(agentSessionRepository, agentIncidentRepository, agentWorkspaceRetention);
    }

    @Bean
    public InvestigationTaskRegistry agentTaskRegistry(AgentTaskRepository agentTaskRepository,
                                                       @Value("${rover.agent.execution.worker-threads:2}")
                                                       int workerThreads,
                                                       @Value("${rover.agent.execution.queue-capacity:16}")
                                                       int queueCapacity,
                                                       @Value("${rover.agent.execution.task-capacity:200}")
                                                       int taskCapacity,
                                                       AgentMetrics agentMetrics) {
        // 参数越界时在装配阶段直接失败：配置错误必须早暴露，而不是运行期以「任务莫名被拒」出现。
        AgentExecutionSettings settings = new AgentExecutionSettings(workerThreads, queueCapacity, taskCapacity);
        return new InvestigationTaskRegistry(agentTaskRepository, settings, new TaskEventBus(), agentMetrics);
    }

    @Bean
    public ModelExplainer agentModelExplainer(ObjectProvider<ChatModelGateway> chatModelGateways,
                                              AgentMetrics agentMetrics) {
        return new ModelExplainer(chatModelGateways.getIfAvailable(NoopChatModelGateway::new), agentMetrics);
    }

    /**
     * 规则解析不出对象时的模型辅助；模型未配置时退化为不推断。
     *
     * 目标解析与意图识别一样是"失败就兜底"的廉价调用，用 {@code rover.agent.llm.quick-timeout-seconds}
     * 收紧等待上限，避免一次网络卡顿让用户等满模型配置里的超时。
     */
    @Bean
    public TargetInterpreter agentTargetInterpreter(ObjectProvider<ChatModelGateway> chatModelGateways,
                                                    @Value("${rover.agent.llm.quick-timeout-seconds:10}")
                                                    int quickTimeoutSeconds) {
        return new ModelTargetInterpreter(chatModelGateways.getIfAvailable(NoopChatModelGateway::new),
                quickTimeoutSeconds);
    }

    @Bean
    public TargetResolver agentTargetResolver(RouteReadPort routeReadPort, InstanceReadPort instanceReadPort,
                                              TargetInterpreter agentTargetInterpreter) {
        return new TargetResolver(routeReadPort, instanceReadPort, agentTargetInterpreter);
    }

    /** 能力注册表：能力清单、规划可选集合与执行映射共用同一份，避免口径分叉。 */
    @Bean
    public CapabilityRegistry agentCapabilityRegistry() {
        return CapabilityRegistry.standard();
    }

    /** 只读能力执行器：模型与生产数据之间唯一的执行口，所有取数都经过它。 */
    @Bean
    public CapabilityExecutor agentCapabilityExecutor(RouteReadPort routeReadPort, InstanceReadPort instanceReadPort,
                                                     MetricReadPort metricReadPort, TraceReadPort traceReadPort,
                                                     ConfigReadPort configReadPort, EventReadPort eventReadPort,
                                                     CapabilityRegistry agentCapabilityRegistry) {
        return new CapabilityExecutor(routeReadPort, instanceReadPort, metricReadPort, traceReadPort,
                configReadPort, eventReadPort, agentCapabilityRegistry);
    }

    /**
     * 规划限制：动态调查的硬边界，由代码执行而不是提示词。
     *
     * 参数越界时装配直接失败（{@link PlanningLimits} 构造器校验），配置错误必须早暴露。
     */
    @Bean
    public PlanningLimits agentPlanningLimits(@Value("${rover.agent.planning.max-rounds:3}") int maxRounds,
                                             @Value("${rover.agent.planning.max-tool-calls:10}") int maxToolCalls,
                                             @Value("${rover.agent.planning.max-plan-steps:6}") int maxPlanSteps) {
        return new PlanningLimits(maxRounds, maxToolCalls, maxPlanSteps);
    }

    /** 意图解释器：模型只补「规则说不清」的场合，输出取值受限，越界一律丢弃。 */
    @Bean
    public IntentInterpreter agentIntentInterpreter(ObjectProvider<ChatModelGateway> chatModelGateways,
                                                    AgentMetrics agentMetrics,
                                                    @Value("${rover.agent.llm.quick-timeout-seconds:10}")
                                                    int quickTimeoutSeconds) {
        return new LlmIntentInterpreter(new SpringAiJsonCompletion(
                chatModelGateways.getIfAvailable(NoopChatModelGateway::new), agentMetrics, "意图识别",
                quickTimeoutSeconds));
    }

    @Bean
    public IntentService agentIntentService(IntentInterpreter agentIntentInterpreter) {
        return new IntentService(agentIntentInterpreter, new IntentClassifier());
    }

    /** 调查规划器：确定性规则打底，模型只提出候选，越界步骤由 {@link PlanValidator} 丢弃。 */
    @Bean
    public InvestigationPlanner agentInvestigationPlanner(CapabilityRegistry agentCapabilityRegistry,
                                                         ObjectProvider<ChatModelGateway> chatModelGateways,
                                                         AgentMetrics agentMetrics,
                                                         @Value("${rover.agent.llm.quick-timeout-seconds:10}")
                                                         int quickTimeoutSeconds) {
        return new LlmInvestigationPlanner(new RuleBasedPlanner(agentCapabilityRegistry),
                new SpringAiJsonCompletion(chatModelGateways.getIfAvailable(NoopChatModelGateway::new), agentMetrics,
                        "调查规划", quickTimeoutSeconds), agentCapabilityRegistry);
    }

    @Bean
    public PlanValidator agentPlanValidator(CapabilityRegistry agentCapabilityRegistry, PlanningLimits planningLimits) {
        return new PlanValidator(agentCapabilityRegistry, planningLimits);
    }

    @Bean
    public AgentContextManager agentContextManager(AgentSessionRepository agentSessionRepository,
                                                   IncidentRepository agentIncidentRepository,
                                                   AgentMessageRepository agentMessageRepository,
                                                   AgentTaskRepository agentTaskRepository,
                                                   RouteReadPort routeReadPort,
                                                   InstanceReadPort instanceReadPort,
                                                   @Value("${rover.agent.context.recent-message-limit:8}")
                                                   int recentMessageLimit) {
        return new AgentContextManager(agentSessionRepository, agentIncidentRepository, agentMessageRepository,
                agentTaskRepository, routeReadPort, instanceReadPort, recentMessageLimit);
    }

    @Bean
    public InvestigationService agentInvestigationService(IncidentRegistry agentIncidentRegistry,
                                                          InvestigationTaskRegistry agentTaskRegistry,
                                                          ModelExplainer agentModelExplainer,
                                                          InvestigationPlanner agentInvestigationPlanner,
                                                          PlanValidator agentPlanValidator,
                                                          CapabilityExecutor agentCapabilityExecutor,
                                                          PlanningLimits agentPlanningLimits) {
        return new InvestigationService(agentIncidentRegistry, agentTaskRegistry, agentModelExplainer,
                agentInvestigationPlanner, agentPlanValidator, agentCapabilityExecutor, agentPlanningLimits);
    }

    /** 状态查询：单一只读能力直接回答，不规划、不跑调查。 */
    @Bean
    public QueryStateService agentQueryStateService(CapabilityExecutor agentCapabilityExecutor) {
        return new QueryStateService(agentCapabilityExecutor);
    }

    /** 解释用例：能力清单来自注册表，结论解释复用会话里已有的调查结论。 */
    @Bean
    public ExplainService agentExplainService(CapabilityRegistry agentCapabilityRegistry,
                                              IncidentRegistry agentIncidentRegistry,
                                              AgentTaskRepository agentTaskRepository) {
        return new ExplainService(agentCapabilityRegistry, agentIncidentRegistry, agentTaskRepository);
    }

    /** 处置计划：只读预检后产出不可执行的计划，本阶段不执行任何写操作。 */
    @Bean
    public ActionPlanService agentActionPlanService(CapabilityExecutor agentCapabilityExecutor) {
        return new ActionPlanService(agentCapabilityExecutor);
    }

    /** Agent 应用入口：意图/目标/事件/任务/执行的编排都在这里，HTTP 层只做契约映射。 */
    @Bean
    public AgentOrchestrator agentOrchestrator(AgentSessionRepository agentSessionRepository,
                                               IncidentRepository agentIncidentRepository,
                                               AgentMessageRepository agentMessageRepository,
                                               AgentTaskRepository agentTaskRepository,
                                               IncidentRegistry agentIncidentRegistry,
                                               AgentContextManager agentContextManager,
                                               TargetResolver agentTargetResolver,
                                               InvestigationService agentInvestigationService,
                                               WorkspaceRetention agentWorkspaceRetention,
                                               IntentService agentIntentService,
                                               QueryStateService agentQueryStateService,
                                               ExplainService agentExplainService,
                                               ActionPlanService agentActionPlanService) {
        return new AgentOrchestrator(agentSessionRepository, agentIncidentRepository, agentMessageRepository,
                agentTaskRepository, agentIncidentRegistry, agentContextManager, agentTargetResolver,
                agentInvestigationService, agentWorkspaceRetention, agentIntentService, agentQueryStateService,
                agentExplainService, agentActionPlanService);
    }
}