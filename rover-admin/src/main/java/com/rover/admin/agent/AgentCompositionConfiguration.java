package com.rover.admin.agent;

import com.rover.agent.runtime.AgentRuntimeConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 装配 Admin 的管理口适配器、模型接入和 Agent 运行层。
 * 指标注册表由 Spring Boot 管理。
 */
@Configuration(proxyBeanMethods = false)
@Import(AgentRuntimeConfiguration.class)
public class AgentCompositionConfiguration {
}
