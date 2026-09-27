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
     * 发一条消息：只做校验与登记，随即返回任务句柄（202 Accepted）。
     *
     * 目标解析与调查执行在 Agent Worker 中进行，因此本接口不会被管理口 HTTP 或模型调用阻塞；
     * 进度经 {@code GET /api/agent/tasks/{taskId}} 与任务事件流观察。
     *
     * <ul>
     *   <li>202：{@code {sessionId, taskId, status}}，status 初始为 PENDING；</li>
     *   <li>409：同会话已有执行中的任务（{@code SESSION_TASK_RUNNING}）；</li>
     *   <li>429：任务容量或执行队列已满（{@code TASK_BUSY}）。</li>
     * </ul>
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
     * Workbench 聚合视图：会话、对话、事件与最近任务一次取齐，避免前端为每个任务各发一次请求。
     *
     * {@code limit} 只约束任务条数（默认 20，上限 100）；{@code tasks} 按创建时间倒序，
     * 完整任务详情仍由 {@code GET /tasks/{taskId}} 按需取。
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
     * 取消一个仍在执行中的任务（协作式）。
     *
     * <ul>
     *   <li>404：任务不存在或不属于当前用户（与任务详情同一归属判定）；</li>
     *   <li>409：任务已结束（{@code TASK_NOT_CANCELLABLE}），取消无意义；</li>
     *   <li>200：已标记取消并中断执行线程，结论不会再产出，事件流随 {@code TASK_CANCELLED} 收尾。</li>
     * </ul>
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
     * 事件接入：告警 / 网关切面异常等事件触发一次自动调查。
     *
     * <p>接入方把一次告警转成「对哪条路由、在什么窗口、怀疑什么」三要素即可；本端点会为它独立开一个会话、
     * 记一笔 {@code ALERT} 来源的事件、并复用与人工提问完全相同的取数链路开始调查。调查异步执行，
     * 返回 {@code 202} 与任务视图，接入方凭 {@code taskId} 轮询详情或订阅 {@code /tasks/{taskId}/events}。
     *
     * <p>考虑到这是机器对机器入口，需要登录（建议用服务账号）；但产生的会话不归属任何人工用户，
     * 以免与人工会话的并发锁冲突，也便于在事件视图里单独聚合。
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
     * 任务事件流（SSE）：事件名是事件类型（{@code SNAPSHOT}/{@code STEP_STARTED}/…），
     * 数据是完整的事件信封（{@code eventId}、{@code type}、{@code timestampMillis}、{@code payload}）。
     *
     * 订阅先补发一份任务快照，再按增量推送；任务进入终态或停在澄清点时收尾，
     * 前端据此结束本次观察而不是悬着等超时。发送在事件总线的派发线程上完成，
     * 不在任务锁里做客户端网络 IO，慢客户端也不会拖慢模型调用与调查执行。
     *
     * 任务不存在、已不可观察或不属于当前用户时返回 404——归属判定与任务详情一致，不靠 UUID 难猜。
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

    /**
     * 把结构化任务事件写成 SSE：事件名即事件类型，数据是完整信封。
     *
     * 发送与收尾都吞掉异常：客户端断开时这里不能再抛回派发线程，
     * 否则一次连接抖动就会连带影响本次调查的执行观测。
     */
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