package com.rover.admin.agent;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rover.admin.client.ManageHttpClient;
import com.rover.common.constants.ManageApiPaths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 无模型环境下的一次端到端调查：从 Workbench 入口提问，最终拿到规则结论与证据。
 *
 * 这里刻意不装配任何 {@link ChatModel}——「模型没配上也能给出可用结论」是本项目的硬约定，
 * 只能靠真实编排链（意图识别 → 目标解析 → 事件选择 → 只读工具取数 → 规则综合）验证，
 * 单测某个环节替代不了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgentContextTest {

    @MockitoBean
    private ManageHttpClient manageHttpClient;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private ObjectProvider<ChatModel> models;

    @Test
    void workbenchQuestionIsDiagnosedWithoutModelCredentials() throws Exception {
        assertNull(models.getIfAvailable());
        ObjectNode target = JsonNodeFactory.instance.objectNode();
        target.put("serviceName", "demo").put("group", "11").put("weight", 100);
        ObjectNode route = JsonNodeFactory.instance.objectNode();
        route.put("id", "demo-tt").put("businessPrefix", "/api/demo/tt").putArray("targets").add(target);
        ObjectNode routesState = JsonNodeFactory.instance.objectNode();
        routesState.put("revision", 1).putArray("routes").add(route);
        when(manageHttpClient.getJson(anyString(), eq(ManageApiPaths.ROUTES))).thenReturn(routesState);
        when(manageHttpClient.getList(anyString(), eq(ManageApiPaths.INSTANCES))).thenReturn(List.of(
                Map.of("serviceName", "demo-service", "group", "", "host", "127.0.0.1",
                        "port", 8081, "healthy", true)));
        when(manageHttpClient.getJson(anyString(), eq(ManageApiPaths.STATUS))).thenReturn(
                JsonNodeFactory.instance.objectNode().put("discoveryType", "NAMESERVER"));
        when(manageHttpClient.getJson(anyString(), eq(ManageApiPaths.METRICS))).thenReturn(
                JsonNodeFactory.instance.objectNode().put("enabled", true));
        ObjectNode live = JsonNodeFactory.instance.objectNode();
        live.putObject("traffic").put("windowRequests", 0).putObject("status").put("5xx", 0);
        live.putObject("resources").putObject("rejects").put("noUpstream", 0);
        when(manageHttpClient.getJson(anyString(), startsWith(ManageApiPaths.METRICS_LIVE))).thenReturn(live);
        ObjectNode routeUpstreams = JsonNodeFactory.instance.objectNode();
        routeUpstreams.put("enabled", true).put("windowSeconds", 300).putArray("rows");
        when(manageHttpClient.getJson(anyString(), startsWith(ManageApiPaths.METRICS_ROUTES))).thenReturn(routeUpstreams);
        when(manageHttpClient.getJson(anyString(), startsWith(ManageApiPaths.TRACES))).thenReturn(
                JsonNodeFactory.instance.objectNode().put("sampleRate", 1.0)
                        .set("traces", JsonNodeFactory.instance.arrayNode()));

        // 走真实过滤器链：安全链在测试配置下不启用登录，但 CSRF 依然生效，写请求必须带令牌。
        String sessionId = mapper.readTree(mockMvc.perform(post("/api/agent/sessions").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("sessionId").asText();

        String created = mockMvc.perform(post("/api/agent/sessions/" + sessionId + "/messages")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"为什么 /api/demo/tt 调用失败？\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String taskId = mapper.readTree(created).path("taskId").asText();

        JsonNode task;
        long deadline = System.currentTimeMillis() + 5000;
        do {
            String response = mockMvc.perform(get("/api/agent/tasks/" + taskId))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            task = mapper.readTree(response);
            if ("COMPLETED".equals(task.path("status").asText())) break;
            Thread.sleep(10);
        } while (System.currentTimeMillis() < deadline);

        assertTrue(task.path("result").path("summary").asText().contains("demo / 11"), task.toString());
        assertTrue(task.path("result").path("evidence").size() >= 2, task.toString());
    }
}
