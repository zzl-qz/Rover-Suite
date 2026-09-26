package com.rover.admin.agent;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rover.agent.core.context.AgentRequestOptions;
import com.rover.agent.core.event.TaskEvent;
import com.rover.agent.core.event.TaskEventSubscriber;
import com.rover.agent.core.event.TaskEventSubscription;
import com.rover.agent.core.event.TaskEventType;
import com.rover.agent.core.event.TaskSnapshot;
import com.rover.agent.core.model.AgentMessage;
import com.rover.agent.core.model.Incident;
import com.rover.agent.core.model.IncidentOrigin;
import com.rover.agent.core.model.IncidentSeverity;
import com.rover.agent.core.model.IncidentStatus;
import com.rover.agent.core.model.MessageRole;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.SessionStatus;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.runtime.AgentOrchestrator;
import com.rover.agent.runtime.task.SessionTaskRunningException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Agent Workbench 契约层测试：端点形状、身份来源与「高级上下文」到领域参数的翻译。
 *
 * 走真实过滤器链（不是 {@code @WebMvcTest}）：{@code Authentication} 参数由请求的 principal 解析，
 * 只有安全链装上包装器之后才有值——身份来源正是本层最需要验证的东西。
 * 编排本身（上下文装配、目标解析、事件复用）由 {@link AgentOrchestrator} 的测试覆盖，
 * 这里只验证 Web 层不越权：不信任前端提交的用户名，也不替编排层做业务判断。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgentControllerWebTest {

    private static final Session SESSION = new Session("s1", "alice", "为什么失败？", "inc-1",
            SessionStatus.ACTIVE, 1, 2, List.of("inc-1"));

    private static final Incident INCIDENT = new Incident("inc-1", "s1", IncidentOrigin.USER, "/api/demo/tt",
            IncidentStatus.INVESTIGATING, IncidentSeverity.UNKNOWN, ResourceTarget.route("/api/demo/tt"),
            TimeRange.unspecified(), "", 1, 2, List.of());

    private static final AgentMessage REPLY =
            new AgentMessage("m1", "s1", MessageRole.AGENT, "已开始调查", 3);

    private static final TaskView TASK = new TaskView("t1", "s1", "inc-1", TaskStatus.PENDING, null,
            "/api/demo/tt", ResourceTarget.route("/api/demo/tt"), "为什么失败？", 1, 0, List.of(), null, null, null);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgentOrchestrator agent;

    @Test
    void createsSessionForAuthenticatedUser() throws Exception {
        when(agent.startSession("alice", "order 502")).thenReturn(SESSION);

        mockMvc.perform(post("/api/agent/sessions").with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"order 502\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s1"))
                .andExpect(jsonPath("$.title").value("为什么失败？"));
    }

    /** 前端提交的 userId 一律忽略：归属只认后端认证上下文。 */
    @Test
    void ignoresUserIdSubmittedByClient() throws Exception {
        when(agent.startSession(eq("alice"), isNull())).thenReturn(SESSION);

        mockMvc.perform(post("/api/agent/sessions").with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"admin\"}"))
                .andExpect(status().isOk());

        verify(agent).startSession("alice", null);
        verify(agent, never()).startSession("admin", null);
    }

    /** 测试配置下未启用登录：没有对应用户，会话也不带归属。 */
    @Test
    void anonymousRequestHasNoUser() throws Exception {
        when(agent.startSession(null, null)).thenReturn(SESSION);

        mockMvc.perform(post("/api/agent/sessions").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        verify(agent).startSession(null, null);
    }

    @Test
    void listsSessionsOfCurrentUser() throws Exception {
        when(agent.sessions("alice")).thenReturn(List.of(SESSION));

        mockMvc.perform(get("/api/agent/sessions").with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sessionId").value("s1"));
    }

    @Test
    void sessionDetailCarriesMessagesAndActiveIncident() throws Exception {
        when(agent.session("s1", "alice")).thenReturn(Optional.of(SESSION));
        when(agent.conversation("s1", "alice")).thenReturn(List.of(REPLY));
        when(agent.incident("inc-1", "alice")).thenReturn(Optional.of(INCIDENT));

        mockMvc.perform(get("/api/agent/sessions/s1").with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.sessionId").value("s1"))
                .andExpect(jsonPath("$.messages[0].content").value("已开始调查"))
                .andExpect(jsonPath("$.activeIncident.incidentId").value("inc-1"));
    }

    @Test
    void unknownSessionIsNotFound() throws Exception {
        when(agent.session("missing", "alice")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/agent/sessions/missing").with(user("alice")))
                .andExpect(status().isNotFound());
    }

    /** 提问只登记任务：202 返回任务句柄，进度靠任务快照与事件流观察。 */
    @Test
    void sendsNaturalLanguageMessageWithoutAdvancedContext() throws Exception {
        when(agent.submit(eq("s1"), eq("alice"), eq("为什么 /api/demo/tt 调用失败？"),
                eq(AgentRequestOptions.none())))
                .thenReturn(Optional.of(TASK));

        mockMvc.perform(post("/api/agent/sessions/s1/messages").with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"为什么 /api/demo/tt 调用失败？\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sessionId").value("s1"))
                .andExpect(jsonPath("$.taskId").value("t1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    /** 高级上下文里手填的对象优先级最高，原样交给编排层，由解析器判断能不能落到路由上。 */
    @Test
    void advancedContextBecomesExplicitTarget() throws Exception {
        when(agent.submit(eq("s1"), eq("alice"), eq("最近怎么这么慢？"),
                eq(new AgentRequestOptions(ResourceTarget.service("order-service"), new TimeRange(100, 200)))))
                .thenReturn(Optional.of(TASK));

        mockMvc.perform(post("/api/agent/sessions/s1/messages").with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"最近怎么这么慢？\",\"service\":\"order-service\","
                                + "\"fromMillis\":100,\"toMillis\":200}"))
                .andExpect(status().isAccepted());
    }

    /** 三类手填对象互斥：路径优先于服务，服务优先于实例。 */
    @Test
    void pathWinsOverServiceAndInstance() throws Exception {
        when(agent.submit(eq("s1"), eq("alice"), eq("为什么失败？"),
                eq(new AgentRequestOptions(ResourceTarget.route("/api/demo/tt"), TimeRange.unspecified()))))
                .thenReturn(Optional.of(TASK));

        mockMvc.perform(post("/api/agent/sessions/s1/messages").with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"为什么失败？\",\"path\":\"/api/demo/tt\","
                                + "\"service\":\"order-service\",\"instance\":\"127.0.0.1:8081\"}"))
                .andExpect(status().isAccepted());
    }

    /** 时间范围要么不填，要么是合法闭区间；填半截就是参数错误，不能当默认窗口糊过去。 */
    @Test
    void halfSpecifiedTimeRangeIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/agent/sessions/s1/messages").with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"为什么失败？\",\"fromMillis\":100}"))
                .andExpect(status().isBadRequest());

        verify(agent, never()).submit(any(), any(), any(), any());
    }

    @Test
    void unknownSessionOnSendIsNotFound() throws Exception {
        when(agent.submit(eq("missing"), eq("alice"), any(), any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/agent/sessions/missing/messages").with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"为什么失败？\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void busyTaskQueueIsTooManyRequests() throws Exception {
        when(agent.submit(any(), any(), any(), any())).thenThrow(new RejectedExecutionException("满"));

        mockMvc.perform(post("/api/agent/sessions/s1/messages").with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"为什么失败？\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TASK_BUSY"));
    }

    /** 同一会话已有执行中的任务：409 让前端复用已有任务卡，而不是再开一张。 */
    @Test
    void secondMessageWhileTaskRunningIsConflict() throws Exception {
        when(agent.submit(any(), any(), any(), any())).thenThrow(new SessionTaskRunningException("t1"));

        mockMvc.perform(post("/api/agent/sessions/s1/messages").with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"再查一次\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_TASK_RUNNING"))
                .andExpect(jsonPath("$.taskId").value("t1"));
    }

    @Test
    void taskAndIncidentAreScopedToOwner() throws Exception {
        when(agent.task("t1", "alice")).thenReturn(Optional.of(TASK));
        when(agent.incident("inc-1", "alice")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/agent/tasks/t1").with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("t1"));
        mockMvc.perform(get("/api/agent/tasks/t2").with(user("alice")))
                .andExpect(status().isNotFound());
        // 别人的事件与不存在一样：只回 404，不透露它是否存在。
        mockMvc.perform(get("/api/agent/incidents/inc-1").with(user("alice")))
                .andExpect(status().isNotFound());
    }

    /** 聚合视图一次取齐会话、对话、事件与最近任务，默认窗口是 20 条。 */
    @Test
    void workspaceAggregatesSessionMessagesIncidentsAndTasks() throws Exception {
        when(agent.workspace("s1", "alice", 20)).thenReturn(Optional.of(new AgentOrchestrator.Workspace(
                SESSION, List.of(REPLY), List.of(INCIDENT), List.of(TASK), INCIDENT)));

        mockMvc.perform(get("/api/agent/sessions/s1/workspace").with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.sessionId").value("s1"))
                .andExpect(jsonPath("$.messages[0].content").value("已开始调查"))
                .andExpect(jsonPath("$.incidents[0].incidentId").value("inc-1"))
                .andExpect(jsonPath("$.tasks[0].taskId").value("t1"))
                .andExpect(jsonPath("$.activeIncident.incidentId").value("inc-1"));
    }

    /** limit 只约束任务条数，并被裁剪到 [1, 100]：不能让一个超大值把内存扫描拖长。 */
    @Test
    void workspaceTaskLimitIsCapped() throws Exception {
        AgentOrchestrator.Workspace empty = new AgentOrchestrator.Workspace(
                SESSION, List.of(), List.of(), List.of(), null);
        when(agent.workspace("s1", "alice", 100)).thenReturn(Optional.of(empty));
        when(agent.workspace("s1", "alice", 1)).thenReturn(Optional.of(empty));

        mockMvc.perform(get("/api/agent/sessions/s1/workspace").param("limit", "5000").with(user("alice")))
                .andExpect(status().isOk());
        verify(agent).workspace("s1", "alice", 100);

        mockMvc.perform(get("/api/agent/sessions/s1/workspace").param("limit", "0").with(user("alice")))
                .andExpect(status().isOk());
        verify(agent).workspace("s1", "alice", 1);
    }

    @Test
    void workspaceOfUnreadableSessionIsNotFound() throws Exception {
        when(agent.workspace("missing", "alice", 20)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/agent/sessions/missing/workspace").with(user("alice")))
                .andExpect(status().isNotFound());
    }

    /**
     * 任务事件流：事件名就是事件类型，数据是完整信封，前端不必再解析文本字段。
     *
     * 终态事件写完即收尾，前端据此结束本次观察；不属于当前用户的任务则与任务详情一样是 404。
     */
    @Test
    void taskEventsAreStreamedAsTypedEnvelopesAndEndOnTerminal() throws Exception {
        AtomicReference<TaskEventSubscriber> captured = new AtomicReference<>();
        when(agent.subscribeEvents(eq("t1"), eq("alice"), any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(2));
            return Optional.of(TaskEventSubscription.NONE);
        });

        MvcResult result = mockMvc.perform(get("/api/agent/tasks/t1/events").with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        TaskEventSubscriber subscriber = captured.get();
        assertNotNull(subscriber);
        subscriber.onEvent(new TaskEvent(0, "t1", TaskEventType.SNAPSHOT, 1, new TaskSnapshot(TASK, "", -1)));
        subscriber.onEvent(new TaskEvent(2, "t1", TaskEventType.STEP_STARTED, 2,
                Map.of("step", "读取路由快照")));
        subscriber.onEvent(new TaskEvent(3, "t1", TaskEventType.TASK_COMPLETED, 3, Map.of("status", "COMPLETED")));

        String body = result.getResponse().getContentAsString(UTF_8);
        assertTrue(body.contains("event:SNAPSHOT"), body);
        assertTrue(body.contains("event:STEP_STARTED"), body);
        // 信封字段必须完整：前端靠 eventId 去重、靠 type 分派、靠 payload 渲染。
        assertTrue(body.contains("\"eventId\":2"), body);
        assertTrue(body.contains("\"type\":\"STEP_STARTED\""), body);
        assertTrue(body.contains("\"payload\":{\"step\":\"读取路由快照\"}"), body);
        assertTrue(body.contains("event:TASK_COMPLETED"), body);
    }

    @Test
    void taskEventsOnForeignTaskIsNotFound() throws Exception {
        when(agent.subscribeEvents(eq("t1"), eq("alice"), any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/agent/tasks/t1/events").with(user("alice")))
                .andExpect(status().isNotFound());
    }
}