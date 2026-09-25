package com.rover.admin.agent;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class DiagnosisContextTest {

    @MockitoBean
    private ManageHttpClient manageHttpClient;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private ObjectProvider<ChatModel> models;

    @Test
    void diagnosesRouteSnapshotWithoutModelCredentials() throws Exception {
        assertNull(models.getIfAvailable());
        when(manageHttpClient.getList(anyString(), eq(ManageApiPaths.ROUTES))).thenReturn(List.of(
                Map.of("id", "demo-tt", "businessPrefix", "/api/demo/tt", "serviceName", "demo", "group", "11")));
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
        when(manageHttpClient.getJson(anyString(), startsWith(ManageApiPaths.TRACES))).thenReturn(
                JsonNodeFactory.instance.objectNode().put("sampleRate", 1.0)
                        .set("traces", JsonNodeFactory.instance.arrayNode()));

        String created = mockMvc.perform(post("/api/agent/diagnoses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/api/demo/tt\",\"question\":\"为什么失败？\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String taskId = mapper.readTree(created).path("taskId").asText();

        JsonNode task;
        long deadline = System.currentTimeMillis() + 5000;
        do {
            String response = mockMvc.perform(get("/api/agent/diagnoses/" + taskId))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            task = mapper.readTree(response);
            if ("COMPLETED".equals(task.path("status").asText())) break;
            Thread.sleep(10);
        } while (System.currentTimeMillis() < deadline);

        assertTrue(task.path("result").path("summary").asText().contains("demo / 11"));
        assertTrue(task.path("result").path("evidence").size() >= 2);
    }
}
