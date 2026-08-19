package com.rover.admin.web;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Admin API 统一异常边界：详细异常只进服务端日志，不把下游地址和响应体回显给浏览器。 */
@Slf4j
@RestControllerAdvice
public class AdminErrorHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        log.warn("Admin 请求校验失败", ex);
        return response(HttpStatus.BAD_REQUEST, "请求参数或下游校验失败");
    }

    /** 缺静态资源是 404，不是下游挂了，别打成 ERROR。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException ex) {
        log.debug("Admin 静态资源不存在: {}", ex.getResourcePath());
        return response(HttpStatus.NOT_FOUND, "资源不存在");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> serverError(Exception ex) {
        log.error("Admin 请求处理失败", ex);
        return response(HttpStatus.BAD_GATEWAY, "下游组件不可用或请求处理失败");
    }

    private ResponseEntity<Map<String, Object>> response(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status.value());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
