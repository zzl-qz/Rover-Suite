package com.rover.agent.runtime;

import com.rover.agent.core.context.AgentContextManager;
import com.rover.agent.core.context.TargetInterpreter;
import com.rover.agent.core.context.TargetResolver;
import com.rover.agent.core.port.InstanceReadPort;
import com.rover.agent.core.port.MetricReadPort;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.port.TraceReadPort;
import com.rover.agent.core.repository.AgentMessageRepository;
import com.rover.agent.core.repository.AgentSessionRepository;
import com.rover.agent.core.repository.AgentTaskRepository;
import com.rover.agent.core.repository.IncidentRepository;
import com.rover.agent.runtime.llm.ChatModelGateway;
import com.rover.agent.runtime.llm.ModelExplainer;
import com.rover.agent.runtime.llm.ModelTargetInterpreter;
import com.rover.agent.runtime.llm.NoopChatModelGateway;
import com.rover.agent.runtime.metrics.AgentMetrics;
import com.rover.agent.runtime.metrics.MicrometerAgentMetrics;
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

    /** 规则解析不出对象时的模型辅助；模型未配置时退化为不推断。 */
    @Bean
    public TargetInterpreter agentTargetInterpreter(ObjectProvider<ChatModelGateway> chatModelGateways) {
        return new ModelTargetInterpreter(chatModelGateways.getIfAvailable(NoopChatModelGateway::new));
    }

    @Bean
    public TargetResolver agentTargetResolver(RouteReadPort routeReadPort, InstanceReadPort instanceReadPort,
                                              TargetInterpreter agentTargetInterpreter) {
        return new TargetResolver(routeReadPort, instanceReadPort, agentTargetInterpreter);
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
    public InvestigationService agentInvestigationService(RouteReadPort routeReadPort,
                                                          InstanceReadPort instanceReadPort,
                                                          MetricReadPort metricReadPort,
                                                          TraceReadPort traceReadPort,
                                                          IncidentRegistry agentIncidentRegistry,
                                                          InvestigationTaskRegistry agentTaskRegistry,
                                                          ModelExplainer agentModelExplainer) {
        return new InvestigationService(routeReadPort, instanceReadPort, metricReadPort, traceReadPort,
                agentIncidentRegistry, agentTaskRegistry, agentModelExplainer);
    }

    /** Agent 应用入口：会话/事件/消息/目标解析/任务启动的编排都在这里，HTTP 层只做契约映射。 */
    @Bean
    public AgentOrchestrator agentOrchestrator(AgentSessionRepository agentSessionRepository,
                                               IncidentRepository agentIncidentRepository,
                                               AgentMessageRepository agentMessageRepository,
                                               AgentTaskRepository agentTaskRepository,
                                               IncidentRegistry agentIncidentRegistry,
                                               AgentContextManager agentContextManager,
                                               TargetResolver agentTargetResolver,
                                               InvestigationService agentInvestigationService,
                                               WorkspaceRetention agentWorkspaceRetention) {
        return new AgentOrchestrator(agentSessionRepository, agentIncidentRepository, agentMessageRepository,
                agentTaskRepository, agentIncidentRegistry, agentContextManager, agentTargetResolver,
                agentInvestigationService, agentWorkspaceRetention);
    }
}