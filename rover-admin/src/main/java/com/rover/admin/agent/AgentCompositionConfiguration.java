package com.rover.admin.agent;

import com.rover.agent.runtime.AgentRuntimeConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Agent 组合根：Admin 侧负责提供只读端口实现（管理口适配器）、模型接入与进程级基础设施，
 * 调查编排、任务生命周期与模型解读由运行层装配。
 *
 * 这样拆分的意义在于：Admin 只处理「管理口数据 → 只读快照」的翻译与 HTTP 契约，
 * 运行层只依赖领域端口，领域规则完全不依赖 Spring 与具体数据源。
 */
@Configuration(proxyBeanMethods = false)
@Import(AgentRuntimeConfiguration.class)
public class AgentCompositionConfiguration {

    /**
     * 进程内唯一的指标注册表：Agent 运行时的计数、计时与仪表都写进这里。
     *
     * 用最简单的内存注册表即可——本阶段不引 Actuator、不接 Prometheus，指标只在进程内供
     * 诊断与容量观察使用。注册表由组合根（宿主）提供，运行层只依赖端口，
     * 因此运行层脱离 Admin 单独复用时指标整体退化为空实现，而不是硬编码一个全局注册表。
     */
    @Bean
    public MeterRegistry agentMeterRegistry() {
        return new SimpleMeterRegistry();
    }
}