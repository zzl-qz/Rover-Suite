package com.rover.admin.agent;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rover.agent.core.model.AgentMessage;
import com.rover.agent.core.model.AgentResponse;
import com.rover.agent.core.model.MessageRole;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.SessionStatus;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.runtime.AgentOrchestrator;
import com.rover.agent.runtime.InvestigationService;
import com.rover.agent.runtime.task.AnalysisStreamListener;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(DiagnosisController.class)
class DiagnosisControllerWebTest {

    private static final TaskView TASK = new TaskView("task-1", "session-1", "incident-1", TaskStatus.PENDING,
            null, "/api/demo/tt", ResourceTarget.route("/api/demo/tt"), "为什么失败？", 1, 0, List.of(), null, null);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgentOrchestrator agent;

    @MockitoBean
    private InvestigationService investigations;

    /** 旧提交入口已转发给编排层，但响应体仍是任务视图，老前端不用改。 */
    @Test
    void forwardsSubmissionToOrchestratorAndStillReturnsTaskView() throws Exception {
        Session session = new Session("session-1", null, "为什么失败？", "incident-1", SessionStatus.ACTIVE,
                1, 1, List.of("incident-1"));
        AgentMessage reply = new AgentMessage("msg-1", "session-1", MessageRole.AGENT, "已开始调查", 1);
        when(agent.oneShot(eq(null), eq(ResourceTarget.route("/api/demo/tt")), eq("为什么失败？")))
                .thenReturn(new AgentResponse(session, null, TASK, reply, null));
        when(investigations.get("task-1")).thenReturn(TASK);

        mockMvc.perform(post("/api/agent/diagnoses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/api/demo/tt\",\"question\":\"为什么失败？\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.incidentId").value("incident-1"));
        mockMvc.perform(get("/api/agent/diagnoses/task-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        mockMvc.perform(get("/api/agent/diagnoses/missing"))
                .andExpect(status().isNotFound());
    }

    /** 老接口必须继续把「路径不合法」当参数错误挡掉，不能悄悄降级成按问题文本解析。 */
    @Test
    void rejectsMissingPathWithoutTouchingOrchestrator() throws Exception {
        mockMvc.perform(post("/api/agent/diagnoses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"\",\"question\":\"为什么失败？\"}"))
                .andExpect(status().isBadRequest());
        verify(agent, never()).oneShot(any(), any(), any());
    }

    @Test
    void reportsBusyWhenTaskQueueIsFull() throws Exception {
        when(agent.oneShot(any(), any(), any()))
                .thenThrow(new java.util.concurrent.RejectedExecutionException("满"));

        mockMvc.perform(post("/api/agent/diagnoses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/api/demo/tt\",\"question\":\"为什么失败？\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void streamsAnalysisAsServerSentEvents() throws Exception {
        AtomicReference<AnalysisStreamListener> captured = new AtomicReference<>();
        when(investigations.subscribeAnalysis(eq("task-1"), any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(1));
            return true;
        });

        MvcResult result = mockMvc.perform(get("/api/agent/diagnoses/task-1/stream"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        AnalysisStreamListener listener = captured.get();
        assertNotNull(listener);
        listener.onSnapshot("已产生的解释");
        listener.onDelta("新增\n第二行");
        listener.onComplete();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("event:snapshot"), body);
        assertTrue(body.contains("data:已产生的解释"), body);
        // 多行增量必须拆成多条 data: 行：换行直接写进事件流会被浏览器当成非法字段而丢掉后半段
        assertTrue(body.contains("event:delta"), body);
        assertTrue(body.contains("data:新增\ndata:第二行"), body);
        assertTrue(body.contains("event:end"), body);
    }

    @Test
    void streamOnUnknownTaskIsNotFound() throws Exception {
        when(investigations.subscribeAnalysis(eq("missing"), any())).thenReturn(false);

        mockMvc.perform(get("/api/agent/diagnoses/missing/stream"))
                .andExpect(status().isNotFound());
    }
}