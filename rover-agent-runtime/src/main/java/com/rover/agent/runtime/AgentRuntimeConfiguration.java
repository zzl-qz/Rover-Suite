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
import com.rover.agent.core.port.AgentAccess;
import com.rover.agent.core.port.EventReadPort;
import com.rover.agent.core.port.InstanceReadPort;
import com.rover.agent.core.port.KnowledgeReadPort;
import com.rover.agent.core.port.LogQueryPort;
import com.rover.agent.core.port.MetricReadPort;
import com.rover.agent.core.port.RouteControlPort;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.port.TraceReadPort;
import com.rover.agent.core.repository.AgentActionRepository;
import com.rover.agent.core.repository.AgentMessageRepository;
import com.rover.agent.core.repository.AgentCheckpointRepository;
import com.rover.agent.core.repository.AgentSessionRepository;
import com.rover.agent.core.repository.AgentTaskRepository;
import com.rover.agent.core.repository.IncidentRepository;
import com.rover.agent.runtime.action.ActionExecutor;
import com.rover.agent.runtime.action.AgentActionService;
import com.rover.agent.runtime.action.RouteWeightActionExecutor;
import com.rover.agent.runtime.journal.OpsJournal;
import com.rover.agent.runtime.knowledge.InMemoryKnowledgeStore;
import com.rover.agent.runtime.llm.ChatModelGateway;
import com.rover.agent.runtime.llm.ConversationModel;
import com.rover.agent.runtime.llm.SpringAiConversationModel;
import com.rover.agent.runtime.llm.ModelExplainer;
import com.rover.agent.runtime.llm.ModelTargetInterpreter;
import com.rover.agent.runtime.llm.NoopChatModelGateway;
import com.rover.agent.runtime.llm.SpringAiJsonCompletion;
import com.rover.agent.runtime.metrics.AgentMetrics;
import com.rover.agent.runtime.metrics.MicrometerAgentMetrics;
import com.rover.agent.runtime.planning.LlmInvestigationPlanner;
import com.rover.agent.runtime.repository.AgentStore;
import com.rover.agent.runtime.task.AgentExecutionSettings;
import com.rover.agent.runtime.task.AgentRunLimits;
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
 * 装配 Agent 运行层，端口实现由宿主进程提供。
 * 配置记录库路径时持久化，否则使用内存；资源笔记仅保存已验证根因。
 */
@Configuration(proxyBeanMethods = false)
public class AgentRuntimeConfiguration {
    @Bean
    public com.rover.agent.runtime.llm.ProviderIsolation.Policy modelIsolationPolicy(
            @Value("${rover.agent.llm.isolation.failure-threshold:3}") int failures,
            @Value("${rover.agent.llm.isolation.cooldown-seconds:30}") int cooldown,
            @Value("${rover.agent.llm.isolation.max-concurrent:4}") int concurrent) {
        return new com.rover.agent.runtime.llm.ProviderIsolation.Policy(failures,cooldown,concurrent);
    }

    /** 内存实现的容量上限：落库实现不受它约束，业务代码也不感知区别。 */
    private static final int SESSION_CAPACITY = 200;
    private static final int INCIDENT_CAPACITY = 500;
    private static final int MESSAGE_CAPACITY = 2000;
    private static final int TASK_CAPACITY = 200;
    private static final int ACTION_CAPACITY = 200;

    /** Agent 存储：没配记录库路径时用内存，单测不用落盘。 */
    @Bean(destroyMethod = "close")
    public AgentStore agentStore(@Value("${rover.admin.log-store-path:}") String logStorePath,
                                 @Value("${rover.agent.production-mode:false}") boolean productionMode) {
        if (logStorePath == null || logStorePath.isBlank()) {
            if (productionMode) { throw new IllegalStateException("生产模式必须配置持久 H2，禁止回退内存"); }
            return AgentStore.memory(SESSION_CAPACITY, INCIDENT_CAPACITY, MESSAGE_CAPACITY, TASK_CAPACITY,
                    ACTION_CAPACITY);
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

    /** 变更记录：Agent 提议、人批准、执行器落地的全过程都存这里。 */
    @Bean
    public AgentActionRepository agentActionRepository(AgentStore agentStore) {
        return agentStore.actions();
    }

    @Bean
    public OpsJournal opsJournal(AgentStore agentStore, RouteControlPort routeControlPort) {
        var journal = agentStore.journal().scoped(routeControlPort.resourceNamespace());
        if (!routeControlPort.requiresResourceIdentity()) { return journal; }
        return journal.identified(key -> routeControlPort.state().routes().stream()
                .filter(route -> route.resourceRef() != null && (key.equals(route.routeId())
                        || key.equals(route.businessPrefix()) || key.startsWith(route.businessPrefix() + "/")))
                .max(java.util.Comparator.comparingInt(route -> route.businessPrefix().length()))
                .map(route -> route.resourceRef().resourceUid() + ":" + route.resourceRef().generation()).orElse(null));
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
                                                      AgentActionRepository agentActionRepository,
                                                      InvestigationTaskRegistry agentTaskRegistry) {
        return new WorkspaceRetention(agentSessionRepository, agentIncidentRepository, agentMessageRepository,
                agentTaskRegistry, agentActionRepository, SESSION_CAPACITY, INCIDENT_CAPACITY, MESSAGE_CAPACITY);
    }

    @Bean
    public IncidentRegistry agentIncidentRegistry(AgentSessionRepository agentSessionRepository,
                                                  IncidentRepository agentIncidentRepository,
                                                  WorkspaceRetention agentWorkspaceRetention,
                                                  RouteControlPort routeControlPort) {
        return new IncidentRegistry(agentSessionRepository, agentIncidentRepository, agentWorkspaceRetention,
                routeControlPort.resourceScope(), routeControlPort.resourceNamespace());
    }

    @Bean
    public AgentRunLimits agentRunLimits(
            @Value("${rover.agent.limits.max-model-calls:20}") int maxModelCalls,
            @Value("${rover.agent.limits.max-output-tokens:4096}") int maxOutputTokens,
            @Value("${rover.agent.limits.max-total-tokens:100000}") long maxTotalTokens,
            @Value("${rover.agent.limits.task-timeout-seconds:300}") int timeoutSeconds,
            @Value("${rover.agent.limits.max-repeated-tool-results:3}") int maxRepeatedToolResults) {
        return new AgentRunLimits(maxModelCalls, maxOutputTokens, maxTotalTokens,
                java.time.Duration.ofSeconds(timeoutSeconds), maxRepeatedToolResults);
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
                                                       AgentMetrics agentMetrics, AgentRunLimits agentRunLimits, AgentStore store) {
        // 参数越界时在装配阶段直接失败：配置错误必须早暴露，而不是运行期以「任务莫名被拒」出现。
        AgentExecutionSettings settings = new AgentExecutionSettings(workerThreads, queueCapacity, taskCapacity);
        var registry = new InvestigationTaskRegistry(agentTaskRepository, settings, new TaskEventBus(), agentMetrics,
                agentCheckpointRepository, agentRunLimits);
        if (store.persistent()) { registry.persistence(store.durable()); }
        return registry;
    }

    @Bean
    public ModelExplainer agentModelExplainer(ObjectProvider<ChatModelGateway> chatModelGateways,
                                              AgentMetrics agentMetrics,
                                              @Value("${rover.agent.llm.report-timeout-seconds:30}") int timeoutSeconds) {
        return new ModelExplainer(chatModelGateways.getIfAvailable(NoopChatModelGateway::new), agentMetrics, timeoutSeconds);
    }

    /**
     * 规则解析不出对象时的模型辅助；模型未配置时退化为不推断。
     *
     * 目标解析是"失败就兜底"的廉价调用，用 {@code rover.agent.llm.quick-timeout-seconds}
     * 收紧等待上限，避免一次网络卡顿让用户等满模型配置里的超时。
     */
    @Bean
    public TargetInterpreter agentTargetInterpreter(ObjectProvider<ChatModelGateway> chatModelGateways,
                                                    @Value("${rover.agent.llm.target-timeout-seconds:${rover.agent.llm.quick-timeout-seconds:10}}")
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
    public CapabilityRegistry agentCapabilityRegistry(ObjectProvider<com.rover.agent.core.port.TelemetryReadPort> telemetry) {
        return CapabilityRegistry.withTelemetry(telemetry.getIfAvailable(com.rover.agent.core.port.TelemetryReadPort::disconnected).capabilities());
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
                                                     CapabilityRegistry agentCapabilityRegistry,
                                                     AgentTaskRepository tasks, AgentSessionRepository sessions,
                                                     RouteControlPort control, ObjectProvider<AgentAccess> access,
                                                     InvestigationTaskRegistry running,
                                                     com.rover.agent.runtime.timeline.ChangeTimelineService timeline,
                                                     ObjectProvider<com.rover.agent.core.port.TelemetryReadPort> telemetry) {
        return new CapabilityExecutor(routeReadPort, instanceReadPort, metricReadPort, traceReadPort,
                configReadPort, eventReadPort, logQueryPort, knowledgeReadPort, agentCapabilityRegistry, taskId -> {
                    AgentAccess policy = access.getIfAvailable(AgentAccess::local);
                    if (!policy.managed()) { return; }
                    var task = tasks.find(taskId).orElseThrow(() -> new AgentAccess.Denied("任务已不存在"));
                    var session = sessions.find(task.sessionId()).orElseThrow(() -> new AgentAccess.Denied("会话已不存在"));
                    if (!java.util.Objects.equals(control.resourceScope(), session.scope())
                            || !control.resourceNamespace().equals(session.targetNamespace())) {
                        throw new AgentAccess.Denied("任务的资源范围已失效");
                    }
                    policy.require(session.userId(), AgentAccess.Permission.READ);
                    var execution = running.find(taskId);
                    if (execution != null) { execution.budget().reserveTool(); }
                }, taskId -> {
                    var task = tasks.find(taskId).orElse(null);
                    var session = task == null ? null : sessions.find(task.sessionId()).orElse(null);
                    var execution = running.find(taskId);
                    java.util.Map<String, String> budget = java.util.Map.of();
                    if (execution != null) {
                        var state = execution.budget().snapshot();
                        budget = java.util.Map.of("modelCalls", Integer.toString(state.modelCalls()),
                                "toolCalls", Integer.toString(state.toolCalls()), "tokens", Long.toString(state.tokens()),
                                "deadlineAtMillis", Long.toString(state.deadlineAtMillis()));
                    }
                    return new com.rover.agent.core.model.InvestigationContext(taskId,
                            task == null ? null : task.incidentId(), session == null ? control.resourceScope() : session.scope(),
                            control.controlTargetId(), control.resourceNamespace(), task == null ? null : task.target(),
                            execution == null ? null : execution.timeRange(), budget,
                            access.getIfAvailable(AgentAccess::local).managed() ? "LIVE_RBAC_V1" : "LOCAL_V1");
                }, timeline, telemetry.getIfAvailable(com.rover.agent.core.port.TelemetryReadPort::disconnected));
    }

    @Bean
    public com.rover.agent.runtime.timeline.ChangeTimelineService changeTimelineService(AgentStore store, RouteControlPort control,
            @Value("${rover.admin.log-collect-interval-seconds:30}") long collectSeconds) {
        return new com.rover.agent.runtime.timeline.ChangeTimelineService(store, control, Math.max(5, collectSeconds) * 1000);
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
                                                         @Value("${rover.agent.llm.planning-timeout-seconds:60}") int timeoutSeconds,
                                                         @Value("${rover.agent.llm.planning-max-output-tokens:2048}") int outputTokens,
                                                         @Value("${rover.agent.llm.planning-thinking-enabled:false}") boolean thinkingEnabled,
                                                         @Value("${rover.agent.llm.planning-evidence-context-chars:24000}") int contextChars) {
        return new LlmInvestigationPlanner(new RuleBasedPlanner(agentCapabilityRegistry),
                SpringAiJsonCompletion.investigation(chatModelGateways.getIfAvailable(NoopChatModelGateway::new), agentMetrics,
                        timeoutSeconds, outputTokens, thinkingEnabled), agentCapabilityRegistry, contextChars);
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
                                                          OpsJournal opsJournal,
                                                          AgentActionService agentActionService,
                                                          @Value("${rover.agent.alerts.owner-user-id:}")
                                                          String alertOwnerUserId, ObjectProvider<AgentAccess> access) {
        var service = new InvestigationService(agentIncidentRegistry, agentTaskRegistry, agentModelExplainer,
                agentInvestigationPlanner, agentPlanValidator, agentCapabilityExecutor, agentPlanningLimits,
                opsJournal, alertOwnerUserId, agentActionService);
        service.access(access.getIfAvailable(AgentAccess::local));
        return service;
    }

    /**
     * 对话主路径：模型自主决定查什么、查几次、怎么答；工具来自只读能力执行器。
     *
     * 对话沿用模型配置的单次超时；任务层另设总截止时间，预算 Advisor 约束每轮请求。
     */
    @Bean
    public ToolLoopService agentToolLoopService(CapabilityExecutor agentCapabilityExecutor,
                                               ObjectProvider<ChatModelGateway> chatModelGateways,
                                               ObjectProvider<ConversationModel> conversationModels,
                                               AgentActionService agentActionService,
                                               AgentMetrics agentMetrics) {
        // 模型侧留一个可替换的入口：端到端测试用它注入脚本，从而在不依赖真实模型的前提下
        // 验证「工具真的取数、证据真的落库」这条链路——这是单测替代不了的部分。
        // 变更提议入口同样注入：模型能走到的最远处就是「登记一条待审批变更」，执行在 ActionExecutor 里。
        ChatModelGateway gateway = chatModelGateways.getIfAvailable(NoopChatModelGateway::new);
        return new ToolLoopService(agentCapabilityExecutor, gateway,
                conversationModels.getIfAvailable(() -> new SpringAiConversationModel(gateway, agentMetrics)),
                agentActionService);
    }

    /** 装配依赖路由控制端口和变更仓储的权重执行器。 */
    @Bean
    public ActionExecutor agentActionExecutor(RouteControlPort routeControlPort,
                                             AgentActionRepository agentActionRepository,
                                             ObjectProvider<AgentAccess> access) {
        return new RouteWeightActionExecutor(routeControlPort, agentActionRepository, access.getIfAvailable(AgentAccess::local));
    }

    /** 装配受控变更服务；模型仅可调用 propose，人工接口负责审批和后续操作。 */
    @Bean
    public AgentActionService agentActionService(AgentActionRepository agentActionRepository,
                                                 AgentSessionRepository agentSessionRepository,
                                                 RouteControlPort routeControlPort,
                                                 ActionExecutor agentActionExecutor, ObjectProvider<AgentAccess> access) {
        return new AgentActionService(agentActionRepository, agentSessionRepository, routeControlPort,
                agentActionExecutor, access.getIfAvailable(AgentAccess::local));
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
                                               OpsJournal opsJournal, ObjectProvider<AgentAccess> access) {
        var orchestrator = new AgentOrchestrator(agentSessionRepository, agentIncidentRepository, agentMessageRepository,
                agentTaskRepository, agentIncidentRegistry, agentContextManager, agentTargetResolver,
                agentInvestigationService, agentWorkspaceRetention, agentToolLoopService, opsJournal);
        orchestrator.access(access.getIfAvailable(AgentAccess::local));
        return orchestrator;
    }
}
