package com.rover.agent.runtime;

import com.rover.agent.core.capability.CapabilityExecutor;
import com.rover.agent.core.capability.CapabilityRegistry;
import com.rover.agent.core.context.AgentContextManager;
import com.rover.agent.core.context.TargetInterpreter;
import com.rover.agent.core.context.TargetResolver;
import com.rover.agent.core.planning.InvestigationPlanner;
import com.rover.agent.core.planning.PlanValidator;
import com.rover.agent.core.planning.PlanningLimits;
import com.rover.agent.core.planning.RuleBasedPlanner;
import com.rover.agent.core.port.ConfigReadPort;
import com.rover.agent.core.port.EventReadPort;
import com.rover.agent.core.port.InstanceReadPort;
import com.rover.agent.core.port.KnowledgeReadPort;
import com.rover.agent.core.port.LogQueryPort;
import com.rover.agent.core.port.MetricReadPort;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.port.TraceReadPort;
import com.rover.agent.core.repository.AgentMessageRepository;
import com.rover.agent.core.repository.AgentCheckpointRepository;
import com.rover.agent.core.repository.AgentSessionRepository;
import com.rover.agent.core.repository.AgentTaskRepository;
import com.rover.agent.core.repository.IncidentRepository;
import com.rover.agent.runtime.journal.OpsJournal;
import com.rover.agent.runtime.knowledge.InMemoryKnowledgeStore;
import com.rover.agent.runtime.llm.ChatModelGateway;
import com.rover.agent.runtime.llm.ConversationModel;
import com.rover.agent.runtime.llm.ModelExplainer;
import com.rover.agent.runtime.llm.ModelTargetInterpreter;
import com.rover.agent.runtime.llm.NoopChatModelGateway;
import com.rover.agent.runtime.llm.SpringAiJsonCompletion;
import com.rover.agent.runtime.metrics.AgentMetrics;
import com.rover.agent.runtime.metrics.MicrometerAgentMetrics;
import com.rover.agent.runtime.planning.LlmInvestigationPlanner;
import com.rover.agent.runtime.repository.AgentStore;
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
 * 会话、消息、事件、任务、步骤与证据在配置了记录库路径时写进同一份库，重启后整条链可恢复；
 * 没配路径（单测、无状态运行）时全部走内存。资源笔记只保存被证据确认的根因。
 */
@Configuration(proxyBeanMethods = false)
public class AgentRuntimeConfiguration {

    /** 内存实现的容量上限：落库实现不受它约束，业务代码也不感知区别。 */
    private static final int SESSION_CAPACITY = 200;
    private static final int INCIDENT_CAPACITY = 500;
    private static final int MESSAGE_CAPACITY = 2000;
    private static final int TASK_CAPACITY = 200;

    /** Agent 存储：没配记录库路径时用内存，单测不用落盘。 */
    @Bean(destroyMethod = "close")
    public AgentStore agentStore(@Value("${rover.admin.log-store-path:}") String logStorePath) {
        if (logStorePath == null || logStorePath.isBlank()) {
            return AgentStore.memory(SESSION_CAPACITY, INCIDENT_CAPACITY, MESSAGE_CAPACITY, TASK_CAPACITY);
        }
        return AgentStore.file(logStorePath);
    }

    @Bean
    public AgentSessionRepository agentSessionRepository(AgentStore agentStore) {
        return agentStore.sessions();
    }

    @Bean
    public AgentMessageRepository agentMessageRepository(AgentStore agentStore) {
        return agentStore.messages();
    }

    @Bean
    public IncidentRepository agentIncidentRepository(AgentStore agentStore) {
        return agentStore.incidents();
    }

    @Bean
    public AgentTaskRepository agentTaskRepository(AgentStore agentStore) {
        return agentStore.tasks();
    }

    @Bean
    public AgentCheckpointRepository agentCheckpointRepository(AgentStore agentStore) {
        return agentStore.checkpoints();
    }

    @Bean
    public OpsJournal opsJournal(AgentStore agentStore) {
        return agentStore.journal();
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
                                                       AgentCheckpointRepository agentCheckpointRepository,
                                                       @Value("${rover.agent.execution.worker-threads:2}")
                                                       int workerThreads,
                                                       @Value("${rover.agent.execution.queue-capacity:16}")
                                                       int queueCapacity,
                                                       @Value("${rover.agent.execution.task-capacity:200}")
                                                       int taskCapacity,
                                                       AgentMetrics agentMetrics) {
        // 参数越界时在装配阶段直接失败：配置错误必须早暴露，而不是运行期以「任务莫名被拒」出现。
        AgentExecutionSettings settings = new AgentExecutionSettings(workerThreads, queueCapacity, taskCapacity);
        return new InvestigationTaskRegistry(agentTaskRepository, settings, new TaskEventBus(), agentMetrics,
                agentCheckpointRepository);
    }

    @Bean
    public ModelExplainer agentModelExplainer(ObjectProvider<ChatModelGateway> chatModelGateways,
                                              AgentMetrics agentMetrics) {
        return new ModelExplainer(chatModelGateways.getIfAvailable(NoopChatModelGateway::new), agentMetrics);
    }

    /**
     * 规则解析不出对象时的模型辅助；模型未配置时退化为不推断。
     *
     * 目标解析是"失败就兜底"的廉价调用，用 {@code rover.agent.llm.quick-timeout-seconds}
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

    /** 运维知识库：内存 FAQ 种子，回答「怎么配置 / 怎么接入 / 怎么排查」；换落盘或向量检索时替换此实现。 */
    @Bean
    public KnowledgeReadPort knowledgeReadPort() {
        return new InMemoryKnowledgeStore(InMemoryKnowledgeStore.seedFaq());
    }

    /** 只读能力执行器：模型与生产数据之间唯一的执行口，所有取数都经过它。 */
    @Bean
    public CapabilityExecutor agentCapabilityExecutor(RouteReadPort routeReadPort, InstanceReadPort instanceReadPort,
                                                     MetricReadPort metricReadPort, TraceReadPort traceReadPort,
                                                     ConfigReadPort configReadPort, EventReadPort eventReadPort,
                                                     LogQueryPort logQueryPort, KnowledgeReadPort knowledgeReadPort,
                                                     CapabilityRegistry agentCapabilityRegistry) {
        return new CapabilityExecutor(routeReadPort, instanceReadPort, metricReadPort, traceReadPort,
                configReadPort, eventReadPort, logQueryPort, knowledgeReadPort, agentCapabilityRegistry);
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
                                                          PlanningLimits agentPlanningLimits,
                                                          OpsJournal opsJournal) {
        return new InvestigationService(agentIncidentRegistry, agentTaskRegistry, agentModelExplainer,
                agentInvestigationPlanner, agentPlanValidator, agentCapabilityExecutor, agentPlanningLimits,
                opsJournal);
    }

    /**
     * 对话主路径：模型自主决定查什么、查几次、怎么答；工具来自只读能力执行器。
     *
     * 刻意不收紧超时：一次对话可能包含多轮工具调用（先看实例、再查它的指标），
     * 用十秒级的快速上限会把它掐断在取数途中。等得久一些但答得完整，比快而残缺更符合这个入口的用途。
     */
    @Bean
    public ToolLoopService agentToolLoopService(CapabilityExecutor agentCapabilityExecutor,
                                               ObjectProvider<ChatModelGateway> chatModelGateways,
                                               ObjectProvider<ConversationModel> conversationModels) {
        // 模型侧留一个可替换的入口：端到端测试用它注入脚本，从而在不依赖真实模型的前提下
        // 验证「工具真的取数、证据真的落库」这条链路——这是单测替代不了的部分。
        return new ToolLoopService(agentCapabilityExecutor,
                chatModelGateways.getIfAvailable(NoopChatModelGateway::new),
                conversationModels.getIfAvailable());
    }

    /** Agent 应用入口：目标/事件/任务/执行的编排都在这里，HTTP 层只做契约映射。 */
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
                                               ToolLoopService agentToolLoopService,
                                               OpsJournal opsJournal) {
        return new AgentOrchestrator(agentSessionRepository, agentIncidentRepository, agentMessageRepository,
                agentTaskRepository, agentIncidentRegistry, agentContextManager, agentTargetResolver,
                agentInvestigationService, agentWorkspaceRetention, agentToolLoopService, opsJournal);
    }
}