package com.rover.admin.agent;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rover.agent.core.context.AgentRequestOptions;
import com.rover.agent.core.model.AgentMessage;
import com.rover.agent.core.model.AgentResponse;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
            "/api/demo/tt", ResourceTarget.route("/api/demo/tt"), "为什么失败？", 1, 0, List.of(), null, null);

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

    @Test
    void sendsNaturalLanguageMessageWithoutAdvancedContext() throws Exception {
        when(agent.send(eq("s1"), eq("alice"), eq("为什么 /api/demo/tt 调用失败？"),
                eq(AgentRequestOptions.none())))
                .thenReturn(Optional.of(new AgentResponse(SESSION, INCIDENT, TASK, REPLY, null)));

        mockMvc.perform(post("/api/agent/sessions/s1/messages").with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"为什么 /api/demo/tt 调用失败？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value("t1"))
                .andExpect(jsonPath("$.incident.incidentId").value("inc-1"))
                .andExpect(jsonPath("$.clarification").doesNotExist());
    }

    /** 高级上下文里手填的对象优先级最高，原样交给编排层，由解析器判断能不能落到路由上。 */
    @Test
    void advancedContextBecomesExplicitTarget() throws Exception {
        when(agent.send(eq("s1"), eq("alice"), eq("最近怎么这么慢？"),
                eq(new AgentRequestOptions(ResourceTarget.service("order-service"), new TimeRange(100, 200)))))
                .thenReturn(Optional.of(new AgentResponse(SESSION, INCIDENT, TASK, REPLY, null)));

        mockMvc.perform(post("/api/agent/sessions/s1/messages").with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"最近怎么这么慢？\",\"service\":\"order-service\","
                                + "\"fromMillis\":100,\"toMillis\":200}"))
                .andExpect(status().isOk());
    }

    /** 三类手填对象互斥：路径优先于服务，服务优先于实例。 */
    @Test
    void pathWinsOverServiceAndInstance() throws Exception {
        when(agent.send(eq("s1"), eq("alice"), eq("为什么失败？"),
                eq(new AgentRequestOptions(ResourceTarget.route("/api/demo/tt"), TimeRange.unspecified()))))
                .thenReturn(Optional.of(new AgentResponse(SESSION, INCIDENT, TASK, REPLY, null)));

        mockMvc.perform(post("/api/agent/sessions/s1/messages").with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"为什么失败？\",\"path\":\"/api/demo/tt\","
                                + "\"service\":\"order-service\",\"instance\":\"127.0.0.1:8081\"}"))
                .andExpect(status().isOk());
    }

    /** 时间范围要么不填，要么是合法闭区间；填半截就是参数错误，不能当默认窗口糊过去。 */
    @Test
    void halfSpecifiedTimeRangeIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/agent/sessions/s1/messages").with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"为什么失败？\",\"fromMillis\":100}"))
                .andExpect(status().isBadRequest());

        verify(agent, never()).send(any(), any(), any(), any());
    }

    @Test
    void unknownSessionOnSendIsNotFound() throws Exception {
        when(agent.send(eq("missing"), eq("alice"), any(), any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/agent/sessions/missing/messages").with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"为什么失败？\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void busyTaskQueueIsTooManyRequests() throws Exception {
        when(agent.send(any(), any(), any(), any())).thenThrow(new RejectedExecutionException("满"));

        mockMvc.perform(post("/api/agent/sessions/s1/messages").with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"为什么失败？\"}"))
                .andExpect(status().isTooManyRequests());
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
}