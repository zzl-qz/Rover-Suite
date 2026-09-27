package com.rover.admin.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.rover.admin.agent.model.AdminChatModelGateway;
import com.rover.admin.client.ManageHttpClient;
import com.rover.agent.runtime.llm.ConversationModel;
import com.rover.common.constants.ManageApiPaths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 一次端到端对话：从 Workbench 入口提问，模型调工具取真实数据，结论与证据回到任务上。
 *
 * 这里刻意不装配任何 {@link ChatModel}——「模型说什么」由下面的脚本给定，因此这条用例验证的是
 * <b>编排链本身</b>：HTTP 入口 → 会话与任务 → 目标解析 → 事件绑定 → 工具执行 → 真实取数
 * （经管理口客户端读到 mock 的路由与实例）→ 证据随结论落库。这一段单测替代不了，
 * 而它恰好是最容易在重构中悄悄断掉的部分。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgentContextTest {

    /** 脚本化的模型侧：真实编排链完整执行，只有「模型说什么」是固定的。 */
    @TestConfiguration
    static class ScriptedModelConfig {

        @Bean
        ConversationModel conversationModel() {
            return (systemPrompt, userMessage, tools, onDelta, onThinking) -> {
                String facts = tools.listInstances("demo-service");
                return "demo-service 的注册实例情况：\n" + facts;
            };
        }
    }

    @MockitoBean
    private ManageHttpClient manageHttpClient;

    /**
     * 声称可用的模型网关：真实客户端不会被调用，模型侧由脚本承担。
     *
     * 按具体类型覆盖（而不是按接口）：同一个 bean 也被模型配置服务按具体类型注入，
     * 换成接口的 Mock 会让那一步的类型要求落空。
     */
    @MockitoBean
    private AdminChatModelGateway chatModelGateway;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private ObjectProvider<ChatModel> models;

    @Test
    void workbenchQuestionReachesRealDataThroughTheToolLoop() throws Exception {
        assertNull(models.getIfAvailable(), "本用例刻意不配置真实模型，模型侧由脚本承担");
        when(chatModelGateway.configured()).thenReturn(true);
        when(chatModelGateway.available()).thenReturn(true);
        when(chatModelGateway.description()).thenReturn("scripted");
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

        assertEquals("CONVERSATION", task.path("taskType").asText(), task.toString());
        assertTrue(task.path("result").path("summary").asText().contains("demo-service"), task.toString());
        assertEquals(1, task.path("executedCapabilities").size(),
                "工具必须真的被执行，而不是只写在提示词里：" + task);
        assertEquals("INSTANCE_QUERY", task.path("executedCapabilities").get(0).asText());
        assertFalse(task.path("result").path("evidence").isEmpty(), "取到的事实必须随结论落库：" + task);
    }
}
