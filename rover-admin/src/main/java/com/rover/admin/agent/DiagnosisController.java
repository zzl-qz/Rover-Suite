package com.rover.admin.agent;

import com.rover.agent.core.event.TaskEvent;
import com.rover.agent.core.event.TaskEventSubscriber;
import com.rover.agent.core.event.TaskEventSubscription;
import com.rover.agent.core.event.TaskEventType;
import com.rover.agent.core.event.TaskSnapshot;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.runtime.AgentOrchestrator;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 诊断任务接口：整体已废弃，仅为仍按「请求路径 + 问题」提交的旧前端保留兼容。
 *
 * <p>三个入口都转发给 {@link AgentOrchestrator}，与 Agent Workbench 共用同一条编排链路与同一套归属校验：
 * 任务详情对应 {@code GET /api/agent/tasks/{taskId}}，增量订阅对应 {@code GET /api/agent/tasks/{taskId}/events}。
 * 响应体形状保持不变，前端改到新端点即可，本类不再直接调用调查服务。
 */
@Deprecated
@RestController
@RequestMapping("/api/agent/diagnoses")
public class DiagnosisController {

    /** SSE 连接超时：解读可能长时间没有增量，超时后由前端重连并靠补发对齐，不会丢内容。 */
    private static final long STREAM_TIMEOUT_MILLIS = 180_000L;

    /** 旧接口的路径长度上限，保持与原实现一致。 */
    private static final int MAX_PATH_LENGTH = 512;

    private static final MediaType EVENT_DATA_TYPE =
            new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);

    private final AgentOrchestrator agent;

    public DiagnosisController(AgentOrchestrator agent) {
        this.agent = agent;
    }

    /** @deprecated 请改用 {@code POST /api/agent/sessions/{sessionId}/messages}。 */
    @Deprecated
    @PostMapping
    public ResponseEntity<?> create(Authentication authentication, @RequestBody DiagnosisRequest request) {
        try {
            String path = request == null ? null : request.path();
            String question = request == null ? null : request.question();
            TaskView task = agent.oneShot(user(authentication), routeTarget(path), question);
            return ResponseEntity.accepted().body(task);
        } catch (RejectedExecutionException ex) {
            return ResponseEntity.status(429).body(Map.of("message", "诊断任务繁忙，请稍后再试"));
        }
    }

    /** @deprecated 请改用 {@code GET /api/agent/tasks/{taskId}}；两者归属校验一致，不属于当前用户的任务返回 404。 */
    @Deprecated
    @GetMapping("/{taskId}")
    public ResponseEntity<TaskView> get(@PathVariable String taskId, Authentication authentication) {
        return agent.task(taskId, user(authentication)).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 订阅「AI 解读」的增量：先补发已产生的全文（snapshot），随后按增量推送（delta），最后推一次 end。
     *
     * <p>本类只把运行层的事件翻译成旧版 SSE 字段；发送发生在事件总线的派发线程上，
     * 不在任务锁里做客户端网络 IO，慢客户端也不会拖慢模型调用与调查执行。
     * 只推解读文本，步骤与任务状态仍由 {@code GET /{taskId}} 轮询兜底，前端断开后不影响调查执行。
     *
     * @deprecated 请改用 {@code GET /api/agent/tasks/{taskId}/events}：它推送全部结构化事件
     *     （事件名即事件类型、数据是完整信封），前端不必再靠轮询补齐步骤与状态。
     */
    @Deprecated
    @GetMapping(path = "/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@PathVariable String taskId, Authentication authentication) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        AnalysisSseSubscriber subscriber = new AnalysisSseSubscriber(emitter);
        emitter.onCompletion(subscriber::cancel);
        emitter.onError(error -> subscriber.cancel());
        emitter.onTimeout(() -> {
            subscriber.cancel();
            emitter.complete();
        });
        TaskEventSubscription subscription =
                agent.subscribeEvents(taskId, user(authentication), subscriber).orElse(null);
        if (subscription == null) {
            return ResponseEntity.notFound().build();
        }
        subscriber.attach(subscription);
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    /**
     * 把运行层的任务事件写成旧版 SSE 字段（snapshot / delta / end）。
     *
     * 发送与结束都吞掉异常：客户端早已断开时这里不能再抛回派发线程，
     * 否则一次连接抖动就会连带把整次模型解读变成降级结果。
     */
    private static final class AnalysisSseSubscriber implements TaskEventSubscriber {

        private final SseEmitter emitter;
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile TaskEventSubscription subscription = TaskEventSubscription.NONE;

        private AnalysisSseSubscriber(SseEmitter emitter) {
            this.emitter = emitter;
        }

        /** 订阅句柄在订阅成功之后才拿到；若期间连接已经结束，就地退订，避免留下无人消费的订阅。 */
        private void attach(TaskEventSubscription attached) {
            this.subscription = attached;
            if (finished.get()) {
                attached.cancel();
            }
        }

        @Override
        public void onEvent(TaskEvent event) {
            if (finished.get()) {
                return;
            }
            switch (event.type()) {
                case SNAPSHOT -> snapshot(event);
                case ANALYSIS_DELTA -> delta(event);
                // 等待用户补充信息也意味着本次「解读」不会再产出内容，收尾让前端停止等待。
                default -> {
                    if (event.type().terminal() || event.type() == TaskEventType.CLARIFICATION_REQUIRED) {
                        finish();
                    }
                }
            }
        }

        private void snapshot(TaskEvent event) {
            TaskSnapshot snapshot = (TaskSnapshot) event.payload();
            if (!snapshot.analysis().isEmpty()) {
                write("snapshot", snapshot.analysis());
            }
        }

        private void delta(TaskEvent event) {
            if (event.payload() instanceof Map<?, ?> payload && payload.get("text") instanceof String text
                    && !text.isEmpty()) {
                write("delta", text);
            }
        }

        /** 结束本次观察：客户端断开、超时、出错与任务进入终态都走这里。 */
        private void cancel() {
            if (finished.compareAndSet(false, true)) {
                subscription.cancel();
            }
        }

        private void finish() {
            if (finished.compareAndSet(false, true)) {
                subscription.cancel();
                write("end", "end");
                emitter.complete();
            }
        }

        private void write(String name, String data) {
            try {
                emitter.send(SseEmitter.event().name(name).data(data, EVENT_DATA_TYPE));
            } catch (Exception ex) {
                finished.set(true);
                subscription.cancel();
                emitter.completeWithError(ex);
            }
        }
    }

    /**
     * 旧接口要求显式请求路径：老调用方传了不合法的路径属于参数错误，不能悄悄改成「按问题文本解析」。
     *
     * 只保留入口层的格式校验；该路径是否命中现有路由属于调查口径，交给编排层按既有行为如实时报告。
     */
    private static ResourceTarget routeTarget(String path) {
        String target = path == null ? null : path.trim();
        if (target == null || target.isBlank() || target.length() > MAX_PATH_LENGTH
                || !target.startsWith("/") || target.startsWith("//")
                || target.indexOf('?') >= 0 || target.indexOf('#') >= 0
                || target.chars().anyMatch(ch -> Character.isWhitespace(ch) || Character.isISOControl(ch))) {
            throw new IllegalArgumentException("请输入不含查询参数的请求路径");
        }
        return ResourceTarget.route(target);
    }

    /** 当前用户身份；未登录（含匿名）时返回空，不接受前端提交的用户名。 */
    private static String user(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getName();
    }

    /** 诊断请求：被调查的请求路径与用户问题。 */
    public record DiagnosisRequest(String path, String question) { }
}