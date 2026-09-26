package com.rover.admin.agent;

import com.rover.agent.core.model.AgentResponse;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.runtime.AgentOrchestrator;
import com.rover.agent.runtime.InvestigationService;
import com.rover.agent.runtime.task.AnalysisStreamListener;
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
 * 诊断任务接口：保留给仍按「请求路径 + 问题」提交的旧前端，以及任务详情与 AI 解读增量订阅。
 *
 * 提交动作（{@code POST}）已废弃并内部转发给 {@link AgentOrchestrator}，与 Agent Workbench 的会话入口
 * 共用同一条编排链路，本层不再直接调用调查服务；两个只读接口行为与响应体保持不变。
 */
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
    private final InvestigationService investigations;

    public DiagnosisController(AgentOrchestrator agent, InvestigationService investigations) {
        this.agent = agent;
        this.investigations = investigations;
    }

    /** @deprecated 请改用 {@code POST /api/agent/sessions/{sessionId}/messages}。 */
    @Deprecated
    @PostMapping
    public ResponseEntity<?> create(Authentication authentication, @RequestBody DiagnosisRequest request) {
        try {
            String path = request == null ? null : request.path();
            String question = request == null ? null : request.question();
            AgentResponse response = agent.oneShot(user(authentication), routeTarget(path), question);
            return ResponseEntity.accepted().body(response.task());
        } catch (RejectedExecutionException ex) {
            return ResponseEntity.status(429).body(Map.of("message", "诊断任务繁忙，请稍后再试"));
        }
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<TaskView> get(@PathVariable String taskId) {
        TaskView task = investigations.get(taskId);
        return task == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(task);
    }

    /**
     * 订阅「AI 解读」的增量：先补发已产生的全文（snapshot），随后按增量推送（delta），最后推一次 end。
     * 只推解读文本，步骤与任务状态仍由 {@code GET /{taskId}} 轮询兜底，前端断开后不影响调查执行。
     */
    @GetMapping(path = "/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@PathVariable String taskId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        AnalysisStreamListener listener = new SseAnalysisListener(emitter);
        Runnable detach = () -> investigations.unsubscribeAnalysis(taskId, listener);
        emitter.onCompletion(detach);
        emitter.onError(error -> detach.run());
        emitter.onTimeout(() -> {
            detach.run();
            emitter.complete();
        });
        if (!investigations.subscribeAnalysis(taskId, listener)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    /**
     * 把运行层的解读增量写成 SSE 事件。
     *
     * 发送与结束都吞掉异常：客户端早已断开时这里不能再抛回调查线程，
     * 否则一次连接抖动就会连带把整次模型解读变成降级结果。
     */
    private static final class SseAnalysisListener implements AnalysisStreamListener {

        private final SseEmitter emitter;
        private final AtomicBoolean finished = new AtomicBoolean();

        private SseAnalysisListener(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void onSnapshot(String text) {
            if (text != null && !text.isEmpty()) {
                send("snapshot", text);
            }
        }

        @Override
        public void onDelta(String chunk) {
            send("delta", chunk);
        }

        @Override
        public void onComplete() {
            if (finished.compareAndSet(false, true)) {
                write("end", "end");
                emitter.complete();
            }
        }

        private void send(String name, String data) {
            if (!finished.get()) {
                write(name, data);
            }
        }

        private void write(String name, String data) {
            try {
                emitter.send(SseEmitter.event().name(name).data(data, EVENT_DATA_TYPE));
            } catch (Exception ex) {
                finished.set(true);
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