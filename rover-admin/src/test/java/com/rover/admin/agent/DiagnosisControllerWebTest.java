package com.rover.admin.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

import com.rover.agent.core.event.TaskEvent;
import com.rover.agent.core.event.TaskEventSubscriber;
import com.rover.agent.core.event.TaskEventSubscription;
import com.rover.agent.core.event.TaskEventType;
import com.rover.agent.core.event.TaskSnapshot;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.runtime.AgentOrchestrator;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
            null, "/api/demo/tt", ResourceTarget.route("/api/demo/tt"), "为什么失败？", 1, 0, List.of(), null, null,
            null);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgentOrchestrator agent;

    /** 旧详情入口同样要走编排层的归属校验：任务不存在或不属于当前用户都是 404。 */
    @Test
    void forwardsSubmissionToOrchestratorAndStillReturnsTaskView() throws Exception {
        when(agent.oneShot(eq(null), eq(ResourceTarget.route("/api/demo/tt")), eq("为什么失败？")))
                .thenReturn(TASK);
        when(agent.task(eq("task-1"), any())).thenReturn(Optional.of(TASK));

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

    /** 旧接口的 SSE 字段名保持不变，但事件来源已是统一的任务事件流，且同样经过归属校验。 */
    @Test
    void streamsAnalysisAsServerSentEvents() throws Exception {
        AtomicReference<TaskEventSubscriber> captured = new AtomicReference<>();
        when(agent.subscribeEvents(eq("task-1"), any(), any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(2));
            return Optional.of(TaskEventSubscription.NONE);
        });

        MvcResult result = mockMvc.perform(get("/api/agent/diagnoses/task-1/stream"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        TaskEventSubscriber subscriber = captured.get();
        assertNotNull(subscriber);
        subscriber.onEvent(snapshot("已产生的解释"));
        subscriber.onEvent(delta("新增\n第二行"));
        // 步骤类事件与解读无关，旧接口不该把它们写进这条流
        subscriber.onEvent(new TaskEvent(3, "task-1", TaskEventType.STEP_COMPLETED, 3, Map.of("step", "x")));
        subscriber.onEvent(new TaskEvent(4, "task-1", TaskEventType.TASK_COMPLETED, 4, Map.of("status", "COMPLETED")));

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("event:snapshot"), body);
        assertTrue(body.contains("data:已产生的解释"), body);
        // 多行增量必须拆成多条 data: 行：换行直接写进事件流会被浏览器当成非法字段而丢掉后半段
        assertTrue(body.contains("event:delta"), body);
        assertTrue(body.contains("data:新增\ndata:第二行"), body);
        assertTrue(body.contains("event:end"), body);
        assertFalse(body.contains("STEP_COMPLETED"), body);
    }

    /** 等待用户补充信息的任务不会再产出解读，流同样要收尾，不能让前端悬着等超时。 */
    @Test
    void clarificationEndsTheStream() throws Exception {
        AtomicReference<TaskEventSubscriber> captured = new AtomicReference<>();
        when(agent.subscribeEvents(eq("task-1"), any(), any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(2));
            return Optional.of(TaskEventSubscription.NONE);
        });

        MvcResult result = mockMvc.perform(get("/api/agent/diagnoses/task-1/stream"))
                .andExpect(request().asyncStarted())
                .andReturn();

        captured.get().onEvent(new TaskEvent(1, "task-1", TaskEventType.CLARIFICATION_REQUIRED, 1,
                Map.of("clarification", "请指明要调查的路由")));

        assertTrue(result.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("event:end"));
    }

    @Test
    void streamOnUnknownTaskIsNotFound() throws Exception {
        // 编排层归属校验不通过（不存在或不属于当前用户）一律 404，与任务详情口径一致。
        mockMvc.perform(get("/api/agent/diagnoses/missing/stream"))
                .andExpect(status().isNotFound());
    }

    private static TaskEvent snapshot(String analysis) {
        return new TaskEvent(0, "task-1", TaskEventType.SNAPSHOT, 0, new TaskSnapshot(TASK, analysis, 0));
    }

    private static TaskEvent delta(String text) {
        return new TaskEvent(2, "task-1", TaskEventType.ANALYSIS_DELTA, 2, Map.of("text", text));
    }
}