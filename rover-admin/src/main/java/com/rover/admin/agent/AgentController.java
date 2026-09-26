package com.rover.admin.agent;

import com.rover.agent.core.context.AgentRequestOptions;
import com.rover.agent.core.model.AgentResponse;
import com.rover.agent.core.model.Incident;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.runtime.AgentOrchestrator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent Workbench 接口：会话、消息、任务与事件的只读查询与提问入口。
 *
 * 本层只做三件事：从认证上下文取用户身份、把 HTTP 入参翻译成领域参数、把结果映射成响应。
 * 上下文装配、目标解析、事件复用与调查执行全部在 {@link AgentOrchestrator}（应用层）里完成，
 * 这里不直接调用路由/实例/指标/追踪查询，也不直接调用模型。
 *
 * 用户身份一律来自后端认证上下文：未启用登录时为空，此时会话也不带归属；
 * 前端提交的任何 userId 字段都会被忽略（请求体里根本没有这个字段）。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentOrchestrator agent;

    public AgentController(AgentOrchestrator agent) {
        this.agent = agent;
    }

    /** 新建会话。 */
    @PostMapping("/sessions")
    public ResponseEntity<Session> createSession(Authentication authentication,
                                                 @RequestBody(required = false) SessionRequest request) {
        String title = request == null ? null : request.title();
        return ResponseEntity.ok(agent.startSession(user(authentication), title));
    }

    /** 当前用户的会话列表（按创建顺序）。 */
    @GetMapping("/sessions")
    public ResponseEntity<List<Session>> sessions(Authentication authentication) {
        return ResponseEntity.ok(agent.sessions(user(authentication)));
    }

    /** 会话详情：会话本体 + 对话记录 + 当前事件（没有事件时为 null）。 */
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<Map<String, Object>> session(@PathVariable String sessionId,
                                                       Authentication authentication) {
        String userId = user(authentication);
        Session session = agent.session(sessionId, userId).orElse(null);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session", session);
        body.put("messages", agent.conversation(sessionId, userId));
        body.put("activeIncident", session.activeIncidentId() == null
                ? null : agent.incident(session.activeIncidentId(), userId).orElse(null));
        return ResponseEntity.ok(body);
    }

    /**
     * 发一条消息：可以是新问题，也可以是对当前事件的追问。
     *
     * 返回 {@link AgentResponse}：要么带 task（已开始调查），要么带 clarification（需要澄清目标）。
     * 任务容量已满时回 429，由前端提示稍后再试。
     */
    @PostMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<?> sendMessage(@PathVariable String sessionId, Authentication authentication,
                                        @RequestBody MessageRequest request) {
        AgentRequestOptions options = request == null ? AgentRequestOptions.none() : request.toOptions();
        String message = request == null ? null : request.message();
        try {
            AgentResponse response = agent.send(sessionId, user(authentication), message, options).orElse(null);
            return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
        } catch (RejectedExecutionException ex) {
            return ResponseEntity.status(429).body(Map.of("message", "调查任务繁忙，请稍后再试"));
        }
    }

    /** 任务详情：步骤、证据与结论。 */
    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<TaskView> task(@PathVariable String taskId, Authentication authentication) {
        return agent.task(taskId, user(authentication)).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 事件详情：目标、时间范围与最新结论。 */
    @GetMapping("/incidents/{incidentId}")
    public ResponseEntity<Incident> incident(@PathVariable String incidentId, Authentication authentication) {
        return agent.incident(incidentId, user(authentication)).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 当前用户身份；未登录（含匿名）时返回空，不接受前端提交的用户名。 */
    private static String user(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getName();
    }

    /** 新建会话请求：标题可选，留空则由第一句提问推导。 */
    public record SessionRequest(String title) { }

    /**
     * 提问请求：{@code message} 是唯一必填项；其余是「高级上下文」里可折叠的手工指定项，全部可选。
     *
     * 对象三类互斥，路径优先于服务、服务优先于实例；时间范围要么都不填，要么填成合法闭区间。
     */
    public record MessageRequest(String message, String path, String service, String instance,
                                 Long fromMillis, Long toMillis) {

        AgentRequestOptions toOptions() {
            return new AgentRequestOptions(target(), timeRange());
        }

        private ResourceTarget target() {
            if (text(path) != null) {
                return ResourceTarget.route(text(path));
            }
            if (text(service) != null) {
                return ResourceTarget.service(text(service));
            }
            if (text(instance) != null) {
                return ResourceTarget.instance(text(instance));
            }
            return ResourceTarget.unknown();
        }

        private TimeRange timeRange() {
            if (fromMillis == null && toMillis == null) {
                return TimeRange.unspecified();
            }
            if (fromMillis == null || toMillis == null || fromMillis <= 0 || toMillis < fromMillis) {
                throw new IllegalArgumentException("时间范围不合法");
            }
            return new TimeRange(fromMillis, toMillis);
        }

        private static String text(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }
}