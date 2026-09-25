package com.rover.agent.runtime;

import com.rover.agent.core.port.InstanceReadPort;
import com.rover.agent.core.port.MetricReadPort;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.port.TraceReadPort;
import com.rover.agent.runtime.llm.ModelExplainer;
import com.rover.agent.runtime.task.IncidentRegistry;
import com.rover.agent.runtime.task.InvestigationTaskRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 运行层装配：只依赖只读端口与模型，不依赖任何具体数据源或 Web 框架。
 *
 * 端口实现（如 Admin 管理口适配器）与模型接入由宿主进程提供，
 * 因此运行层可以在同一个 JVM 内复用，也可以整体搬到独立进程。
 */
@Configuration(proxyBeanMethods = false)
public class AgentRuntimeConfiguration {

    @Bean
    public IncidentRegistry agentIncidentRegistry() {
        return new IncidentRegistry();
    }

    @Bean
    public InvestigationTaskRegistry agentTaskRegistry() {
        return new InvestigationTaskRegistry();
    }

    @Bean
    public ModelExplainer agentModelExplainer(ObjectProvider<ChatModel> chatModels,
                                              ObjectProvider<ChatClient.Builder> chatBuilders) {
        return new ModelExplainer(chatModels, chatBuilders);
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
}