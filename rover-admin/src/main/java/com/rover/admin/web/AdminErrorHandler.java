package com.rover.admin.web;

import com.rover.admin.client.ManageApiCallException;
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

    /**
     * 下游管理口的拒绝原样透传。
     *
     * 4xx 用下游自己的状态码与文案：版本冲突（409）必须让操作者看到「期望几、当前几」，
     * 否则前端只能按 400 理解成「参数写错了」，于是反复重提同一个过期版本。
     * 5xx 或没拿到响应（statusCode=0）按 502 处理，且不回声下游正文——
     * 下游的堆栈与内部地址不该出现在浏览器里。
     */
    @ExceptionHandler(ManageApiCallException.class)
    public ResponseEntity<Map<String, Object>> downstreamRejected(ManageApiCallException ex) {
        log.warn("Admin 调用下游管理口失败: status={}, message={}", ex.statusCode(), ex.getMessage());
        if (ex.statusCode() >= 400 && ex.statusCode() < 500) {
            return response(HttpStatus.valueOf(ex.statusCode()), ex.getMessage());
        }
        return response(HttpStatus.BAD_GATEWAY, "下游组件不可用或请求处理失败");
    }

    /** 缺静态资源是 404，不是下游挂了，别打成 ERROR。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException ex) {
        log.debug("Admin 静态资源不存在: {}", ex.getResourcePath());
        return response(HttpStatus.NOT_FOUND, "资源不存在");
    }

    /**
     * Admin 自身的状态问题（如模型配置落盘失败）：这不是下游故障，
     * 必须把原因告诉操作者，否则无从排查。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> invalidState(IllegalStateException ex) {
        log.warn("Admin 本地状态异常", ex);
        return response(HttpStatus.BAD_REQUEST, ex.getMessage() == null ? "操作无法完成" : ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> serverError(Exception ex) {
        if (isClientAbort(ex)) {
            // 客户端已断开（关标签页 / 路由跳转），不是服务端故障：不写错误体、不打 ERROR。
            // 若仍按异常处理，响应已提交会再抛一次 HttpMessageNotWritableException，把日志弄脏。
            log.debug("客户端中止连接，忽略：{}", ex.getMessage());
            return null;
        }
        log.error("Admin 请求处理失败", ex);
        return response(HttpStatus.BAD_GATEWAY, "下游组件不可用或请求处理失败");
    }

    /** 沿异常链识别「客户端中止连接」：用类名判断，避免硬依赖 Tomcat 的具体类。 */
    private static boolean isClientAbort(Throwable ex) {
        for (Throwable current = ex; current != null && current != current.getCause(); current = current.getCause()) {
            if ("ClientAbortException".equals(current.getClass().getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private ResponseEntity<Map<String, Object>> response(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status.value());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
