package com.rover.nameserver.core.manage;

import com.rover.common.config.ConfigApplyMode;
import com.rover.common.config.ConfigChangeEvent;
import com.rover.common.config.ConfigItem;
import com.rover.common.json.ManageJson;
import com.rover.common.model.ServiceInstance;
import com.rover.nameserver.core.model.InstanceRecord;
import com.rover.nameserver.core.runtime.NameserverRuntime;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:45:00
 * Description: Nameserver 管理 HTTP API
 *
 * 这个类是什么：管理口的 REST 风格路由与 JSON 响应层。
 * 核心职责：①暴露 /_manage/status、/instances、/configs 等查询接口；
 * ②支持 POST 热更新配置；③统一写 JSON 响应与异常映射。
 * 被谁用：NameserverHttpManageServer 把 HTTP 请求转进来；Admin 或 curl 直接调用。
 */
@Slf4j
public class NameserverManageApi {

    /** 管理 API 路径前缀 */
    public static final String PREFIX = "/_manage";

    /** 运行时引用，读注册表/健康检查/配置等状态 */
    private final NameserverRuntime runtime;

    /**
     * @param runtime Nameserver 运行时
     */
    public NameserverManageApi(NameserverRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * 判断是否为本管理 API 路径。
     *
     * @param path 请求路径
     * @return 以 PREFIX 开头返回 true
     */
    public boolean supports(String path) {
        return path != null && path.startsWith(PREFIX);
    }

    /**
     * 处理一条 HTTP 管理请求：按 method+path 分发到对应 handler。
     *
     * @param ctx     Netty 上下文
     * @param request 完整 HTTP 请求
     * @param path    已解码的路径（不含 query）
     */
    public void handle(ChannelHandlerContext ctx, FullHttpRequest request, String path) {
        try {
            if (HttpMethod.GET.equals(request.method()) && (PREFIX + "/status").equals(path)) {
                writeJson(ctx, HttpResponseStatus.OK, statusJson());
                return;
            }
            if (HttpMethod.GET.equals(request.method()) && (PREFIX + "/instances").equals(path)) {
                writeJson(ctx, HttpResponseStatus.OK, instancesJson());
                return;
            }
            if (HttpMethod.GET.equals(request.method()) && (PREFIX + "/configs").equals(path)) {
                writeJson(ctx, HttpResponseStatus.OK,
                        ManageJson.configItems(runtime.getConfigManager().listConfigs()));
                return;
            }
            if (HttpMethod.POST.equals(request.method()) && (PREFIX + "/configs").equals(path)) {
                handleUpdateConfig(ctx, request);
                return;
            }
            writeJson(ctx, HttpResponseStatus.NOT_FOUND,
                    ManageJson.object(Map.of("message", "unknown manage path: " + path)));
        } catch (IllegalArgumentException | UnsupportedOperationException ex) {
            writeJson(ctx, HttpResponseStatus.BAD_REQUEST,
                    ManageJson.object(Map.of("message", ex.getMessage() == null ? "bad request" : ex.getMessage())));
        } catch (Exception ex) {
            log.warn("Nameserver manage API error, path={}", path, ex);
            writeJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    ManageJson.object(Map.of("message", "manage api error")));
        }
    }

    /** 处理 POST /_manage/configs：解析 key/value 并触发热更新 */
    private void handleUpdateConfig(ChannelHandlerContext ctx, FullHttpRequest request) {
        String body = request.content().toString(StandardCharsets.UTF_8);
        String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
        String[] kv = ManageJson.parseKeyValue(body, contentType);
        if (kv == null) {
            throw new IllegalArgumentException("请求体需要 key/value");
        }
        ConfigChangeEvent event = runtime.getConfigManager().updateConfig(kv[0], kv[1]);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("key", event.getKey());
        resp.put("value", event.getNewValue());
        resp.put("oldValue", event.getOldValue());
        resp.put("applyMode", event.getApplyMode() == null ? "" : event.getApplyMode().name());
        resp.put("message", ConfigApplyMode.HOT_RELOAD.equals(event.getApplyMode())
                ? "已热更新并落盘"
                : "已保存，重启后生效");
        for (ConfigItem item : runtime.getConfigManager().listConfigs()) {
            if (kv[0].equals(item.getKey())) {
                resp.put("description", item.getDescription());
                resp.put("hotReloadable", item.isHotReloadable());
                break;
            }
        }
        writeJson(ctx, HttpResponseStatus.OK, ManageJson.object(resp));
    }

    /** 组装 /status 响应：组件状态、端口、实例数、健康检查参数等 */
    private String statusJson() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("component", "nameserver");
        status.put("up", true);
        status.put("port", runtime.getOptions().getPort());
        status.put("managePort", runtime.getOptions().getManagePort());
        status.put("instanceCount", runtime.getRegistry().listAllRecords().size());
        status.put("pushEnabled", runtime.getPushService().isPushEnabled());
        status.put("healthCheckIntervalMillis", runtime.getHealthChecker().getCheckIntervalMillis());
        status.put("heartbeatTimeoutMillis", runtime.getHealthChecker().getHeartbeatTimeoutMillis());
        status.put("instanceExpireMillis", runtime.getHealthChecker().getInstanceExpireMillis());
        status.put("writeAckMode", runtime.getOptions().getWriteAckMode().name());
        status.put("configOverlay", runtime.getConfigManager().getOverlayStore().getPath().toString());
        return ManageJson.object(status);
    }

    /** 组装 /instances 响应：注册表全部实例的 JSON 数组 */
    private String instancesJson() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (InstanceRecord record : runtime.getRegistry().listAllRecords()) {
            ServiceInstance instance = record.getInstance();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("serviceName", nullToEmpty(instance.getServiceName()));
            row.put("instanceId", nullToEmpty(instance.getInstanceId()));
            row.put("host", nullToEmpty(instance.getHost()));
            row.put("port", instance.getPort());
            row.put("group", nullToEmpty(instance.getGroup()));
            row.put("healthy", instance.isHealthy());
            row.put("ephemeral", instance.isEphemeral());
            row.put("weight", instance.getWeight());
            row.put("lastHeartbeatMillis", record.getLastHeartbeatMillis());
            rows.add(row);
        }
        return ManageJson.arrayOfObjects(rows);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 写 JSON HTTP 响应。
     *
     * @param ctx    Netty 上下文
     * @param status HTTP 状态码
     * @param json   响应体 JSON 字符串
     */
    private static void writeJson(ChannelHandlerContext ctx, HttpResponseStatus status, String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        ctx.writeAndFlush(response);
    }
}
