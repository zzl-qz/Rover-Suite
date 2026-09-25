package com.rover.admin.agent;

import com.rover.agent.core.model.TaskView;
import com.rover.agent.runtime.InvestigationService;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 诊断任务接口；只读取 Gateway 与 Nameserver 管理数据。 */
@RestController
@RequestMapping("/api/agent/diagnoses")
public class DiagnosisController {

    private final InvestigationService investigations;

    public DiagnosisController(InvestigationService investigations) {
        this.investigations = investigations;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody DiagnosisRequest request) {
        try {
            String path = request == null ? null : request.path();
            String question = request == null ? null : request.question();
            return ResponseEntity.accepted().body(investigations.submit(path, question));
        } catch (RejectedExecutionException ex) {
            return ResponseEntity.status(429).body(Map.of("message", "诊断任务繁忙，请稍后再试"));
        }
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<TaskView> get(@PathVariable String taskId) {
        TaskView task = investigations.get(taskId);
        return task == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(task);
    }

    /** 诊断请求：被调查的请求路径与用户问题。 */
    public record DiagnosisRequest(String path, String question) { }
}