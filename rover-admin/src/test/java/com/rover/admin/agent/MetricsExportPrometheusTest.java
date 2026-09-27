package com.rover.admin.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.admin.agent.model.AdminChatModelGateway;
import com.rover.admin.client.ManageHttpClient;
import com.rover.agent.runtime.llm.ConversationModel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 指标对外出口：开启 {@code management.metrics.export.prometheus.enabled=true} 后，
 * Spring Boot 把全局注册表切换为 {@link PrometheusMeterRegistry}，Agent 运行层照旧写数、
 * 指标名与标签口径不变，抓取端直接拿到 Prometheus 文本格式。
 */
@SpringBootTest(properties = {
        "management.metrics.export.prometheus.enabled=true",
        "management.endpoints.web.exposure.include=health,info,prometheus"
})
@AutoConfigureMockMvc
class MetricsExportPrometheusTest {

    @TestConfiguration
    static class ScriptedModelConfig {
        @Bean
        ConversationModel conversationModel() {
            return (systemPrompt, userMessage, tools, onDelta, onThinking) -> "ok";
        }
    }

    @MockitoBean
    private ManageHttpClient manageHttpClient;
    @MockitoBean
    private AdminChatModelGateway chatModelGateway;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void registryIsPrometheusAndScrapeExposesAgentMetrics() {
        assertTrue(meterRegistry instanceof PrometheusMeterRegistry,
                "开启 prometheus 后注册表应为 PrometheusMeterRegistry");
        // 用与 Agent 指标相同命名风格的探针，确认口径（点号→下划线）对外可读。
        meterRegistry.counter("rover.agent.test.probe").increment();
        String scrape = ((PrometheusMeterRegistry) meterRegistry).scrape();
        assertTrue(scrape.contains("rover_agent_test_probe"),
                "Prometheus 抓取结果应暴露 rover.agent.* 指标");
    }
}
