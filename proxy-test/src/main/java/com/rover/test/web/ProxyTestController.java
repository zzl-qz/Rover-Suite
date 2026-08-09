package com.rover.test.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 基础端点：回显、状态码、延迟、大响应、Cookie 设置。
 * 网关 user-api 剥离 /api、test-api 剥离 /api/uu 后分别落在 /user/** 与 /api/**，故双前缀映射。
 */
@RestController
public class ProxyTestController {

    @RequestMapping({"/user/echo", "/api/echo"})
    public Map<String, Object> echo(HttpServletRequest req) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", req.getMethod());
        result.put("path", req.getRequestURI());
        result.put("query", req.getQueryString());
        result.put("body", readBody(req));
        Map<String, String> headers = new LinkedHashMap<>();
        req.getHeaderNames().asIterator().forEachRemaining(
                n -> headers.put(n, String.join(";", Collections.list(req.getHeaders(n)))));
        result.put("headers", headers);
        result.put("clientIp", req.getRemoteAddr());
        return result;
    }

    @RequestMapping({"/user/status/{code}", "/api/status/{code}"})
    public ResponseEntity<Map<String, Object>> status(@PathVariable int code) {
        return ResponseEntity.status(code).body(Map.of("code", code, "message", "mock status"));
    }

    @RequestMapping({"/user/slow", "/api/slow"})
    public Map<String, Object> slow(@RequestParam(defaultValue = "3000") long ms)
            throws InterruptedException {
        Thread.sleep(ms);
        return Map.of("code", 200, "message", "slow done", "delayMs", ms);
    }

    @RequestMapping({"/user/big", "/api/big"})
    public byte[] big(@RequestParam(defaultValue = "512") int kb) {
        return "x".repeat(kb * 1024).getBytes(StandardCharsets.UTF_8);
    }

    @RequestMapping({"/user/cookie-set", "/api/cookie-set"})
    public ResponseEntity<Map<String, Object>> cookieSet() {
        return ResponseEntity.ok()
                .header("Set-Cookie", "rover_cookie=rover-cookie-v1; Path=/; SameSite=Lax")
                .body(Map.of("code", 200, "message", "cookie set"));
    }

    static String readBody(HttpServletRequest req) throws IOException {
        byte[] bytes = req.getInputStream().readAllBytes();
        String ce = req.getHeader("Content-Encoding");
        if (ce != null && ce.toLowerCase().contains("gzip")) {
            try (InputStream gz = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                bytes = gz.readAllBytes();
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
