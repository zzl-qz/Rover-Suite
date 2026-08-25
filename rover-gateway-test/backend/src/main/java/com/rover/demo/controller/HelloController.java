package com.rover.demo.controller;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:10:00
 * Description: 最简单的业务接口，方便联调。用于测试网关代理和负载均衡。
 */
@RestController
public class HelloController {

    @Value("${spring.application.name:demo-service}")
    private String serviceName;

    @Value("${server.port:8081}")
    private int port;

    /** 压测用定长响应；上限 8MiB，避免误打爆机器。 */
    private static final int MAX_PAYLOAD_BYTES = 8 * 1024 * 1024;

    /**
     * Hello 接口 - 用于基本连通性测试
     */
    @GetMapping("/api/hello")
    public Map<String, Object> hello() {
        Map<String, Object> result = new HashMap<>();
        result.put("message", "hello from rover-demo");
        result.put("service", serviceName);
        result.put("port", port);
        result.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        System.out.println("请求进来");
        return result;
    }

    /**
     * 定长二进制响应，用来压大体代理（流式 vs 整包）。
     * size 单位字节，缺省 1MiB，最大 8MiB。
     */
    @GetMapping("/api/payload")
    public ResponseEntity<byte[]> payload(
            @RequestParam(name = "size", defaultValue = "1048576") int size) {
        int bytes = Math.min(Math.max(size, 0), MAX_PAYLOAD_BYTES);
        byte[] body = new byte[bytes];
        Arrays.fill(body, (byte) 'P');
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .header("X-Payload-Size", String.valueOf(bytes))
                .body(body);
    }

    /**
     * 丢掉请求体，只回报收了多少字节。用来压入站管道，不让上游解析 JSON。
     */
    @PostMapping("/api/ingest")
    public Map<String, Object> ingest(jakarta.servlet.http.HttpServletRequest request)
            throws java.io.IOException {
        long received = request.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        Map<String, Object> result = new HashMap<>();
        result.put("receivedBytes", received);
        result.put("service", serviceName);
        result.put("port", port);
        return result;
    }

    /**
     * Echo 接口 - 用于测试请求参数传递
     */
    @GetMapping("/api/echo")
    public Map<String, Object> echo(@RequestParam(name = "msg", required = false, defaultValue = "empty") String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("echo", msg);
        result.put("service", serviceName);
        result.put("port", port);
        result.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return result;
    }

    /**
     * Data 接口 - 用于测试 POST 请求
     */
    @PostMapping("/api/data")
    public Map<String, Object> data(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        result.put("received", body != null ? body : Map.of());
        result.put("service", serviceName);
        result.put("port", port);
        result.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return result;
    }

    /**
     * Health 接口 - 健康检查
     */
    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("service", serviceName);
        result.put("port", port);
        return result;
    }

    /**
     * Info 接口 - 服务信息
     */
    @GetMapping("/api/info")
    public Map<String, Object> info() {
        Map<String, Object> result = new HashMap<>();
        result.put("service", serviceName);
        result.put("port", port);
        result.put("version", "1.0.0");
        result.put("description", "Rover Gateway Test Backend");
        return result;
    }

    /**
     * Headers 接口 - 返回所有请求头（用于测试请求头传递）
     */
    @GetMapping("/api/headers")
    public Map<String, Object> headers(jakarta.servlet.http.HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        result.put("service", serviceName);
        result.put("port", port);
        result.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        Map<String, String> headers = new HashMap<>();
        java.util.Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            headers.put(name, request.getHeader(name));
        }
        result.put("headers", headers);

        return result;
    }

    /**
     * Delay 接口 - 模拟延迟响应（用于测试超时）
     */
    @GetMapping("/api/delay")
    public Map<String, Object> delay(@RequestParam(name = "ms", defaultValue = "1000") int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Map<String, Object> result = new HashMap<>();
        result.put("service", serviceName);
        result.put("port", port);
        result.put("delayMs", ms);
        result.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return result;
    }

    /**
     * Batch 接口 - 批量处理（用于测试大数据量）
     */
    @PostMapping("/api/batch")
    public Map<String, Object> batch(@RequestBody java.util.List<Map<String, Object>> items) {
        Map<String, Object> result = new HashMap<>();
        result.put("service", serviceName);
        result.put("port", port);
        result.put("receivedCount", items != null ? items.size() : 0);
        result.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return result;
    }

    /**
     * Error 接口 - 模拟错误（用于测试错误处理），返回真实 HTTP 状态码
     */
    @GetMapping("/api/error")
    public org.springframework.http.ResponseEntity<Map<String, Object>> error(
            @RequestParam(name = "code", defaultValue = "500") int code) {
        Map<String, Object> result = new HashMap<>();
        result.put("service", serviceName);
        result.put("port", port);
        result.put("error", true);
        result.put("errorCode", code);
        result.put("message", "Simulated error for testing");
        result.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return org.springframework.http.ResponseEntity.status(code).body(result);
    }
}
