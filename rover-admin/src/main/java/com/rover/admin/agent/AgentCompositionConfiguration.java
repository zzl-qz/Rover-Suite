package com.rover.admin.agent;

import com.rover.agent.runtime.AgentRuntimeConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Agent 组合根：Admin 侧负责提供只读端口实现（管理口适配器）、模型接入与进程级基础设施，
 * 调查编排、任务生命周期与模型解读由运行层装配。
 *
 * 这样拆分的意义在于：Admin 只处理「管理口数据 → 只读快照」的翻译与 HTTP 契约，
 * 运行层只依赖领域端口，领域规则完全不依赖 Spring 与具体数据源。
 *
 * <p>指标注册表不再在此处硬编码：交由 Spring Boot 统一管理（默认进程内 {@code SimpleMeterRegistry}，
 * 开启 {@code management.metrics.export.prometheus.enabled=true} 后即为 {@code PrometheusMeterRegistry}），
 * 运行层通过 {@code ObjectProvider<MeterRegistry>} 取用，指标名与标签口径不受影响。
 */
@Configuration(proxyBeanMethods = false)
@Import(AgentRuntimeConfiguration.class)
public class AgentCompositionConfiguration {
}
