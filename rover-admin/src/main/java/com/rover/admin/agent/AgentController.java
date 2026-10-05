package com.rover.admin.agent;

import com.rover.agent.core.context.AgentRequestOptions;
import com.rover.agent.core.event.TaskEvent;
import com.rover.agent.core.event.TaskEventSubscriber;
import com.rover.agent.core.event.TaskEventSubscription;
import com.rover.agent.core.event.TaskEventType;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.runtime.AgentOrchestrator;
import com.rover.agent.runtime.metrics.AgentMetrics;
import com.rover.agent.runtime.task.SessionTaskRunningException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 会话、消息、任务与事件接口。
 * 用户身份取自认证上下文，调查流程委托 {@link AgentOrchestrator}。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    /** 事件流超时：调查期间可能长时间没有增量，超时后前端重连并靠补发快照对齐。 */
    private static final long STREAM_TIMEOUT_MILLIS = 180_000L;

    /** 聚合视图一次返回的任务条数上限：再多也只是把内存扫描拖长，历史任务应按需单独取。 */
    private static final int MAX_WORKSPACE_TASKS = 100;

    private final AgentOrchestrator agent;
    private final AgentMetrics metrics;

    public AgentController(AgentOrchestrator agent, AgentMetrics metrics) {
        this.agent = agent;
        this.metrics = metrics == null ? AgentMetrics.NOOP : metrics;
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

    /**
     * 登记消息并返回 202 任务句柄，调查由 Worker 异步执行。
     * 同会话已有任务返回 409；任务容量或队列已满返回 429。
     */
    @PostMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<?> sendMessage(@PathVariable String sessionId, Authentication authentication,
                                        @RequestBody MessageRequest request) {
        AgentRequestOptions options = request == null ? AgentRequestOptions.none() : request.toOptions();
        String message = request == null ? null : request.message();
        TaskView task;
        try {
            task = agent.submit(sessionId, user(authentication), message, options).orElse(null);
        } catch (SessionTaskRunningException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(commandError("SESSION_TASK_RUNNING",
                    ex.getMessage(), ex.runningTaskId()));
        } catch (RejectedExecutionException ex) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(commandError("TASK_BUSY", "调查任务繁忙，请稍后再试", null));
        }
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", task.sessionId());
        body.put("taskId", task.taskId());
        body.put("status", task.status());
        return ResponseEntity.accepted().body(body);
    }

    /** 命令被拒时的统一错误体：{@code code} 供前端判断，{@code message} 供展示。 */
    private static Map<String, Object> commandError(String code, String message, String taskId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        if (taskId != null) {
            body.put("taskId", taskId);
        }
        return body;
    }

    /**
     * 聚合会话、消息、事件和最近任务；任务按创建时间倒序。
     * limit 默认 20，上限 100，仅限制任务条数。
     */
    @GetMapping("/sessions/{sessionId}/workspace")
    public ResponseEntity<AgentOrchestrator.Workspace> workspace(@PathVariable String sessionId,
                                                                 @RequestParam(defaultValue = "20") int limit,
                                                                 Authentication authentication) {
        int capped = Math.min(Math.max(limit, 1), MAX_WORKSPACE_TASKS);
        return agent.workspace(sessionId, user(authentication), capped).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 任务详情：步骤、证据与结论。
     */
    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<TaskView> task(@PathVariable String taskId, Authentication authentication) {
        return agent.task(taskId, user(authentication)).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 协作式取消任务并发送 TASK_CANCELLED 事件。
     * 任务不存在或无权访问返回 404，任务已结束返回 409。
     */
    @PostMapping("/tasks/{taskId}/cancel")
    public ResponseEntity<?> cancelTask(@PathVariable String taskId, Authentication authentication) {
        if (agent.task(taskId, user(authentication)).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        boolean cancelled = agent.cancel(taskId, user(authentication));
        if (!cancelled) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(commandError("TASK_NOT_CANCELLABLE", "任务已结束，无法取消", taskId));
        }
        return ResponseEntity.ok().build();
    }

    /**
     * 接收告警事件，创建独立会话并异步调查，返回 202 任务视图。
     * 接口需要登录；告警会话不归属人工用户。
     */
    @PostMapping("/events/ingest")
    public ResponseEntity<?> ingestEvent(@RequestBody IngestRequest request, Authentication authentication) {
        if (request == null || request.path() == null || request.path().isBlank()) {
            return ResponseEntity.badRequest().body(commandError("BAD_REQUEST", "缺少 path（要排查的路由前缀）", null));
        }
        if ((request.fromMillis() == null) != (request.toMillis() == null)) {
            return ResponseEntity.badRequest()
                    .body(commandError("BAD_REQUEST", "fromMillis 与 toMillis 需同时提供", null));
        }
        try {
            TaskView task = agent.ingestAlert(request.path(), request.message(), request.fromMillis(), request.toMillis());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(task);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(commandError("BAD_REQUEST", ex.getMessage(), null));
        }
    }

    /** 事件接入请求体：路由前缀必填；告警文本与观测窗口可选。 */
    public record IngestRequest(String path, String message, Long fromMillis, Long toMillis) {
    }

    /**
     * 任务 SSE：先发送全量快照，再推送增量事件；终态或澄清时结束。
     * 事件类型作为名称，完整事件信封作为数据；发送由派发线程处理。
     * 任务不存在、不可观察或无权访问时返回 404。
     */
    @GetMapping(path = "/tasks/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> taskEvents(@PathVariable String taskId, Authentication authentication) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        TaskEventSseSubscriber subscriber = new TaskEventSseSubscriber(emitter, metrics);
        emitter.onCompletion(subscriber::cancel);
        emitter.onError(error -> subscriber.cancel());
        emitter.onTimeout(() -> {
            subscriber.cancel();
            emitter.complete();
        });
        TaskEventSubscription subscription = agent.subscribeEvents(taskId, user(authentication), subscriber)
                .orElse(null);
        if (subscription == null) {
            return ResponseEntity.notFound().build();
        }
        subscriber.attach(subscription);
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    /** 当前用户身份；未登录（含匿名）时返回空，不接受前端提交的用户名。 */
    private static String user(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getName();
    }

    /** 将任务事件写为 SSE；客户端断开时忽略发送和收尾异常。 */
    private static final class TaskEventSseSubscriber implements TaskEventSubscriber {

        private final SseEmitter emitter;
        private final AgentMetrics metrics;
        private final AtomicBoolean finished = new AtomicBoolean();

        /** 连接数指标的记账标志：只对「确实建立过的连接」加减，断开与收尾都不会把计数减成负数。 */
        private final AtomicBoolean counted = new AtomicBoolean();
        private volatile TaskEventSubscription subscription = TaskEventSubscription.NONE;

        private TaskEventSseSubscriber(SseEmitter emitter, AgentMetrics metrics) {
            this.emitter = emitter;
            this.metrics = metrics;
        }

        /** 订阅句柄在订阅成功之后才拿到；若期间连接已经结束，就地退订，避免留下无人消费的订阅。 */
        private void attach(TaskEventSubscription attached) {
            this.subscription = attached;
            if (finished.get()) {
                attached.cancel();
                return;
            }
            if (counted.compareAndSet(false, true)) {
                metrics.sseConnected();
            }
        }

        @Override
        public void onEvent(TaskEvent event) {
            if (finished.get()) {
                return;
            }
            write(event);
            // 终态与澄清点都是「本次不会再产出事件」：收尾让前端停止等待，否则只能等超时。
            if (event.type().terminal() || event.type() == TaskEventType.CLARIFICATION_REQUIRED) {
                finish();
            }
        }

        /** 断开、超时或出错：退订即可，任务执行不受影响，重连后靠快照对齐。 */
        private void cancel() {
            if (finished.compareAndSet(false, true)) {
                subscription.cancel();
            }
            release();
        }

        private void finish() {
            if (finished.compareAndSet(false, true)) {
                subscription.cancel();
                emitter.complete();
            }
            release();
        }

        private void write(TaskEvent event) {
            try {
                emitter.send(SseEmitter.event().name(event.type().name())
                        .data(event, MediaType.APPLICATION_JSON));
            } catch (Exception ex) {
                finished.set(true);
                subscription.cancel();
                emitter.completeWithError(ex);
                release();
            }
        }

        /** 连接收尾：只在「这次连接确实计过数」时减一次，重复回调不会再减。 */
        private void release() {
            if (counted.compareAndSet(true, false)) {
                metrics.sseDisconnected();
            }
        }
    }

    /** 新建会话请求：标题可选，留空则由第一句提问推导。 */
    public record SessionRequest(String title) { }

    /**
     * 提问请求：{@code message} 是唯一必填项；其余是「高级上下文」里可折叠的手工指定项，全部可选。
     *
     * 对象三类互斥，路径优先于服务、服务优先于实例；时间范围要么都不填，要么填成合法闭区间。
     */
    public record MessageRequest(String message, String path, String service, String instance,
                                 Long fromMillis, Long toMillis, String recallSessionId) {

        AgentRequestOptions toOptions() {
            return new AgentRequestOptions(target(), timeRange(), recallSessionId);
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