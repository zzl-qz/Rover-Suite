package com.rover.demo.controller;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 仅在测试 backend 显式开启；故障控制不位于 Gateway 的业务路由下。 */
@RestController
@ConditionalOnProperty(name = "rover.demo.replay.enabled", havingValue = "true")
public final class ReplayController {

    private final String version;
    private final String token;
    private final AtomicReference<Fault> fault = new AtomicReference<>(new Fault(200, 0));

    public ReplayController(@Value("${rover.nameserver.group:unversioned}") String version,
                            @Value("${rover.demo.replay.token:}") String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("开启故障回放必须设置 rover.demo.replay.token");
        }
        this.version = version;
        this.token = token;
    }

    public record Fault(int status, int delayMillis) {
        public Fault {
            if ((status != 200 && status != 503) || delayMillis < 0 || delayMillis > 2000) {
                throw new IllegalArgumentException("status 只允许 200/503，delayMillis 必须在 0~2000 之间");
            }
        }
    }

    @GetMapping("/__test/replay")
    public Fault state(@RequestHeader(value = "X-Rover-Replay-Token", required = false) String supplied) {
        authorize(supplied);
        return fault.get();
    }

    @PostMapping("/__test/replay")
    public Fault configure(@RequestHeader(value = "X-Rover-Replay-Token", required = false) String supplied,
                            @RequestBody Fault requested) {
        authorize(supplied);
        if (requested == null) {
            throw new IllegalArgumentException("需要故障配置");
        }
        fault.set(requested);
        return requested;
    }

    @GetMapping("/api/replay/order")
    public ResponseEntity<Map<String, Object>> order() {
        Fault snapshot = fault.get();
        int status = snapshot.status();
        try {
            Thread.sleep(snapshot.delayMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            status = 503;
        }
        return ResponseEntity.status(status).header("X-Rover-Replay-Version", version)
                .body(Map.of("version", version, "configuredStatus", snapshot.status(),
                        "delayMillis", snapshot.delayMillis(), "fixture", "rover-agent-fault-replay"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    private void authorize(String supplied) {
        if (!token.equals(supplied)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "故障回放控制令牌不匹配");
        }
    }
}
