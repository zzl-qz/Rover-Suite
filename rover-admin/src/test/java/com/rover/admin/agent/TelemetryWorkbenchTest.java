package com.rover.admin.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rover.admin.agent.model.AdminChatModelGateway;
import com.rover.admin.client.ManageHttpClient;
import com.rover.agent.runtime.llm.ConversationModel;
import com.rover.common.constants.ManageApiPaths;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 工作台 → 真实会话/权限/任务/H2 → 三类回环 HTTP 源 → 可读证据；模型选择使用确定性脚本。 */
@SpringBootTest(properties = {"rover.admin.log-store-path=./target/test-data/TelemetryWorkbenchTest/rover", "rover.admin.gateway-control-target-id=telemetry-fixture"})
@AutoConfigureMockMvc
@DirtiesContext
class TelemetryWorkbenchTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger reads = new AtomicInteger();
    private static final HttpServer SOURCE = startSource();
    @Autowired MockMvc mvc;
    @MockitoBean ManageHttpClient manage;
    @MockitoBean AdminChatModelGateway gateway;

    @TestConfiguration static class Model {
        @Bean ConversationModel conversationModel() {
            return (system, question, tools, delta, thinking) -> tools.getApplicationMetrics() + "\n" + tools.getApplicationLogs() + "\n" + tools.getApplicationTraces();
        }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        for (int index = 0; index < 3; index++) {
            String prefix = "rover.agent.telemetry.sources[" + index + "].";
            String kind = List.of("PROMETHEUS", "LOKI", "TEMPO").get(index);
            Map<String, String> values = Map.ofEntries(Map.entry("id", kind.toLowerCase(Locale.ROOT)), Map.entry("kind", kind),
                    Map.entry("endpoint", "http://127.0.0.1:" + SOURCE.getAddress().getPort()), Map.entry("allow-http", "true"),
                    Map.entry("allowed-addresses[0]", "127.0.0.1"), Map.entry("scope.organization-id", "local"),
                    Map.entry("scope.team-id", "operators"), Map.entry("scope.environment-id", "local"), Map.entry("namespace", "apps"),
                    Map.entry("control-target-id", "telemetry-fixture"), Map.entry("metrics[0]", "http_requests_total"),
                    Map.entry("bearer-token", "fixture-workbench-secret"), Map.entry("bindings[0].target-type", "ROUTE"),
                    Map.entry("bindings[0].target", "/api/demo/tt"), Map.entry("bindings[0].service", "demo-service"));
            values.forEach((key, value) -> properties.add(prefix + key, () -> value));
        }
    }
    @AfterAll static void stopSource() { SOURCE.stop(0); }

    @Test void workbenchTaskPublishesThreeRedactedScopedEvidenceEnvelopes() throws Exception {
        when(gateway.available()).thenReturn(true); when(gateway.configured()).thenReturn(true); when(gateway.description()).thenReturn("fixture");
        when(manage.getJson(anyString(), eq(ManageApiPaths.ROUTES))).thenReturn(JSON.readTree("{\"revision\":1,\"routes\":[{\"id\":\"demo\",\"businessPrefix\":\"/api/demo/tt\",\"targets\":[{\"serviceName\":\"demo-service\",\"group\":\"v1\",\"weight\":100}]}]}"));
        when(manage.getList(anyString(), eq(ManageApiPaths.INSTANCES))).thenReturn(List.of(Map.of("serviceName", "demo-service", "host", "127.0.0.1", "port", 8080, "healthy", true)));
        String session = JSON.readTree(mvc.perform(post("/api/agent/sessions").with(user("admin")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("sessionId").asText();
        String taskId = JSON.readTree(mvc.perform(post("/api/agent/sessions/" + session + "/messages").with(user("admin")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"查看 /api/demo/tt 的应用遥测\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("taskId").asText();
        JsonNode task = null; long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        do {
            task = JSON.readTree(mvc.perform(get("/api/agent/tasks/" + taskId).with(user("admin"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            if ("COMPLETED".equals(task.path("status").asText())) { break; } Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        assertEquals("COMPLETED", task.path("status").asText(), task.toString()); assertEquals(3, reads.get());
        assertEquals(3, task.path("executedCapabilities").size());
        JsonNode evidence = task.path("result").path("evidence"); assertEquals(3, evidence.size(), task.toString());
        for (JsonNode item : evidence) {
            assertEquals("PARTIAL", item.path("envelope").path("qualityStatus").asText());
            assertEquals(1, item.path("envelope").path("sampleCount").asInt());
            assertEquals("local", item.path("envelope").path("context").path("scope").path("environmentId").asText());
            assertTrue(item.path("envelope").path("facts").path("telemetryRows").isArray());
        }
        assertFalse(task.toString().contains("fixture-workbench-secret")); assertFalse(task.toString().contains("plaintext-password"));
        assertTrue(task.toString().contains("[REDACTED]"));
        mvc.perform(get("/api/agent/tasks/" + taskId)).andExpect(status().isUnauthorized());
    }

    private static HttpServer startSource() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                reads.incrementAndGet(); long at = System.currentTimeMillis() - 2000;
                Map<String, String> labels = new HashMap<>(Map.of("organization_id", "local", "team_id", "operators", "environment", "local", "namespace", "apps", "service_name", "demo-service"));
                Object body;
                switch (exchange.getRequestURI().getPath()) {
                    case "/api/v1/query_range" -> { labels.put("__name__", "http_requests_total"); body = Map.of("status", "success", "data", Map.of("resultType", "matrix", "result", List.of(Map.of("metric", labels, "values", List.of(List.of(at / 1000.0, "7")))))); }
                    case "/loki/api/v1/query_range" -> body = Map.of("status", "success", "data", Map.of("resultType", "streams", "result", List.of(Map.of("stream", labels, "values", List.of(List.of(Long.toString(at * 1_000_000), "password=plaintext-password, token=fixture-workbench-secret, dependency timeout"))))));
                    default -> body = Map.of("traces", List.of(Map.of("traceID", "0123456789abcdef0123456789abcdef", "rootServiceName", "demo-service", "rootTraceName", "read", "startTimeUnixNano", Long.toString(at * 1_000_000), "durationMs", 1200)));
                }
                byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            }); server.start(); return server;
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }
}
